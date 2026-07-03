package com.tethershot.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tethershot.app.export.Exporter
import com.tethershot.app.lut.CubeLut
import com.tethershot.app.lut.LutProcessor
import com.tethershot.app.presets.Preset
import com.tethershot.app.presets.PresetRepository
import com.tethershot.app.ptp.PtpCamera
import com.tethershot.app.ptp.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val presetRepo = PresetRepository(app)
    private val exporter = Exporter(app)

    val status = MutableLiveData("Chưa kết nối máy ảnh")
    val cameraName = MutableLiveData<String?>(null)
    val presets = MutableLiveData(presetRepo.listPresets())
    val selectedPreset = MutableLiveData(Preset.NONE)
    val lastPreview = MutableLiveData<Bitmap?>(null)
    val exportCount = MutableLiveData(0)
    val autoExport = MutableLiveData(true)

    private var camera: PtpCamera? = null
    private var tetherJob: Job? = null
    private var activeLut: CubeLut? = null
    private val knownHandles = HashSet<Int>()

    fun refreshPresets() {
        presets.value = presetRepo.listPresets()
    }

    fun importPreset(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val preset = presetRepo.importPreset(uri)
                withContext(Dispatchers.Main) {
                    refreshPresets()
                    selectPreset(preset)
                    status.value = "Đã thêm preset: ${preset.name}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.value = "Lỗi import preset: ${e.message}"
                }
            }
        }
    }

    fun selectPreset(preset: Preset) {
        selectedPreset.value = preset
        viewModelScope.launch(Dispatchers.IO) {
            activeLut = try {
                presetRepo.loadLut(preset)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status.value = "Lỗi đọc LUT: ${e.message}" }
                null
            }
        }
    }

    fun deletePreset(preset: Preset) {
        if (preset.file == null) return
        presetRepo.deletePreset(preset)
        if (selectedPreset.value == preset) selectPreset(Preset.NONE)
        refreshPresets()
    }

    fun connectCamera(device: UsbDevice) {
        val usbManager = getApplication<Application>().getSystemService(UsbManager::class.java)
        disconnectCamera()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cam = PtpCamera(usbManager, device)
                cam.open()
                camera = cam
                knownHandles.clear()
                knownHandles.addAll(cam.getObjectHandles())
                withContext(Dispatchers.Main) {
                    cameraName.value = cam.name
                    status.value = "Đã kết nối: ${cam.name} — sẵn sàng chụp"
                }
                startTetherLoop(cam)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.value = "Lỗi kết nối: ${e.message}"
                    cameraName.value = null
                }
            }
        }
    }

    fun disconnectCamera() {
        tetherJob?.cancel()
        tetherJob = null
        camera?.close()
        camera = null
        cameraName.postValue(null)
        status.postValue("Chưa kết nối máy ảnh")
    }

    private fun startTetherLoop(cam: PtpCamera) {
        tetherJob = viewModelScope.launch(Dispatchers.IO) {
            var pollCounter = 0
            while (isActive && cam.isOpen) {
                try {
                    // Preferred: interrupt-endpoint ObjectAdded events
                    val evtHandle = cam.pollObjectAddedEvent(1000)
                    if (evtHandle != null) {
                        handleNewObject(cam, evtHandle)
                        continue
                    }
                    // Fallback for cameras without usable event endpoint: poll handle list
                    if (++pollCounter >= 3) {
                        pollCounter = 0
                        val handles = cam.getObjectHandles()
                        for (h in handles) {
                            if (knownHandles.add(h)) handleNewObject(cam, h)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        status.value = "Mất kết nối máy ảnh: ${e.message}"
                    }
                    break
                }
            }
        }
    }

    private suspend fun handleNewObject(cam: PtpCamera, handle: Int) {
        knownHandles.add(handle)
        val info = try {
            cam.getObjectInfo(handle)
        } catch (e: Exception) {
            return
        }
        if (info.format != PtpConstants.FMT_EXIF_JPEG) return // skip RAW/folders
        withContext(Dispatchers.Main) { status.value = "Đang tải ${info.filename}…" }
        val bytes = cam.getObject(handle)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        processAndExport(bitmap, info.filename.substringBeforeLast('.').ifBlank { "IMG" })
    }

    /** Manual processing path (also used for testing without a camera). */
    fun processImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw Exception("Không đọc được ảnh")
                processAndExport(bitmap, "IMG")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status.value = "Lỗi xử lý ảnh: ${e.message}" }
            }
        }
    }

    private suspend fun processAndExport(bitmap: Bitmap, baseName: String) {
        val lut = activeLut
        val processed = if (lut != null) LutProcessor.apply(bitmap, lut) else bitmap
        withContext(Dispatchers.Main) { lastPreview.value = processed }
        if (autoExport.value == true) {
            try {
                exporter.export(processed, baseName)
                withContext(Dispatchers.Main) {
                    exportCount.value = (exportCount.value ?: 0) + 1
                    status.value = "Đã xuất ảnh vào Pictures/TetherShot (${exportCount.value})"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status.value = "Lỗi xuất file: ${e.message}" }
            }
        } else {
            withContext(Dispatchers.Main) { status.value = "Đã xử lý ảnh (tự động xuất đang tắt)" }
        }
    }

    override fun onCleared() {
        disconnectCamera()
    }
}
