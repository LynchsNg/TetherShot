package com.tethershot.app.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import com.tethershot.app.R
import com.tethershot.app.databinding.ActivityMainBinding
import com.tethershot.app.presets.Preset
import com.tethershot.app.ptp.PtpCamera

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val importLutLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importPreset(it) }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.processImageFromUri(it) }
        }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = getUsbDeviceExtra(intent)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                viewModel.connectCamera(device)
            } else {
                Toast.makeText(context, R.string.usb_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerUsbReceiver()
        setupUi()
        observeViewModel()
        handleUsbAttachIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttachIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }

    private fun setupUi() {
        binding.btnConnect.setOnClickListener { findAndConnectCamera() }
        binding.btnDisconnect.setOnClickListener { viewModel.disconnectCamera() }
        binding.btnImportLut.setOnClickListener {
            importLutLauncher.launch(arrayOf("*/*"))
        }
        binding.btnProcessImage.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }
        binding.switchAutoExport.setOnCheckedChangeListener { _, checked ->
            viewModel.autoExport.value = checked
        }
        binding.spinnerPreset.setOnItemClickListener { _, _, position, _ ->
            viewModel.presets.value?.getOrNull(position)?.let { viewModel.selectPreset(it) }
        }
        binding.btnDeletePreset.setOnClickListener {
            val preset = viewModel.selectedPreset.value ?: return@setOnClickListener
            if (preset.file == null) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_preset_confirm, preset.name))
                .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.deletePreset(preset) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun observeViewModel() {
        viewModel.status.observe(this) { binding.txtStatus.text = it }
        viewModel.cameraName.observe(this) { name ->
            binding.txtCamera.text = name ?: getString(R.string.no_camera)
            binding.btnConnect.isEnabled = name == null
            binding.btnDisconnect.isEnabled = name != null
        }
        viewModel.presets.observe(this) { presets ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                presets.map { it.name })
            binding.spinnerPreset.setAdapter(adapter)
            val selected = viewModel.selectedPreset.value ?: Preset.NONE
            binding.spinnerPreset.setText(
                presets.firstOrNull { it.file?.path == selected.file?.path }?.name
                    ?: Preset.NONE.name, false)
        }
        viewModel.selectedPreset.observe(this) { preset ->
            binding.spinnerPreset.setText(preset.name, false)
            binding.btnDeletePreset.isEnabled = preset.file != null
        }
        viewModel.lastPreview.observe(this) { bmp ->
            if (bmp != null) binding.imgPreview.setImageBitmap(bmp)
        }
        viewModel.exportCount.observe(this) { count ->
            binding.txtExportCount.text = getString(R.string.export_count, count)
        }
    }

    private fun handleUsbAttachIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            getUsbDeviceExtra(intent)?.let { requestPermissionAndConnect(it) }
        }
    }

    private fun findAndConnectCamera() {
        val usbManager = getSystemService(UsbManager::class.java)
        val camera = usbManager.deviceList.values.firstOrNull {
            PtpCamera.findPtpInterface(it) != null
        }
        if (camera == null) {
            Toast.makeText(this, R.string.no_camera_found, Toast.LENGTH_LONG).show()
            return
        }
        requestPermissionAndConnect(camera)
    }

    private fun requestPermissionAndConnect(device: UsbDevice) {
        val usbManager = getSystemService(UsbManager::class.java)
        if (usbManager.hasPermission(device)) {
            viewModel.connectCamera(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tethershot.app.USB_PERMISSION"
    }
}
