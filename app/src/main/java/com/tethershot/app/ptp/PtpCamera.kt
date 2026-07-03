package com.tethershot.app.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vendor-independent PTP (Picture Transfer Protocol) camera session over USB.
 * Works with any camera exposing the USB Still Image Capture class (Canon,
 * Nikon, Sony, Fujifilm, etc.).
 */
class PtpCamera(
    private val usbManager: UsbManager,
    val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var epEvent: UsbEndpoint? = null
    private var transactionId = 0
    @Volatile var isOpen = false
        private set

    val name: String
        get() = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ").ifBlank { device.deviceName }

    companion object {
        fun findPtpInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == PtpConstants.USB_CLASS_STILL_IMAGE) return intf
            }
            // Some cameras expose vendor-specific class but still speak PTP
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var bulkIn = false
                var bulkOut = false
                var intr = false
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    when {
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_IN -> bulkIn = true
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_OUT -> bulkOut = true
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT -> intr = true
                    }
                }
                if (bulkIn && bulkOut && intr) return intf
            }
            return null
        }
    }

    @Throws(IOException::class)
    fun open() {
        val intf = findPtpInterface(device) ?: throw IOException("No PTP interface found")
        val conn = usbManager.openDevice(device) ?: throw IOException("Cannot open USB device")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("Cannot claim USB interface")
        }
        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            when {
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN -> epIn = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT -> epOut = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_INT -> epEvent = ep
            }
        }
        if (epIn == null || epOut == null) {
            conn.releaseInterface(intf)
            conn.close()
            throw IOException("Missing bulk endpoints")
        }
        connection = conn
        usbInterface = intf
        transactionId = 0
        openSession()
        isOpen = true
    }

    fun close() {
        isOpen = false
        try {
            sendCommand(PtpConstants.OP_CLOSE_SESSION, intArrayOf())
            readResponse()
        } catch (_: Exception) {
        }
        usbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
        usbInterface = null
    }

    @Throws(IOException::class)
    private fun openSession() {
        sendCommand(PtpConstants.OP_OPEN_SESSION, intArrayOf(1))
        val rsp = readResponse()
        if (rsp != PtpConstants.RSP_OK) throw IOException("OpenSession failed: 0x${rsp.toString(16)}")
    }

    /** Returns all object handles on the camera (all storages). */
    @Throws(IOException::class)
    fun getObjectHandles(): List<Int> {
        sendCommand(PtpConstants.OP_GET_OBJECT_HANDLES, intArrayOf(-1, 0, 0))
        val data = readData()
        readResponse()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        return List(count) { buf.int }
    }

    data class ObjectInfo(val format: Int, val size: Int, val filename: String)

    @Throws(IOException::class)
    fun getObjectInfo(handle: Int): ObjectInfo {
        sendCommand(PtpConstants.OP_GET_OBJECT_INFO, intArrayOf(handle))
        val data = readData()
        readResponse()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // storage id
        val format = buf.short.toInt() and 0xFFFF
        buf.short // protection
        val size = buf.int
        // skip thumb format(2) thumb size(4) thumb w(4) thumb h(4) img w(4) img h(4)
        // img depth(4) parent(4) assoc type(2) assoc desc(4) seq num(4)
        buf.position(buf.position() + 2 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 2 + 4 + 4)
        val filename = readPtpString(buf)
        return ObjectInfo(format, size, filename)
    }

    @Throws(IOException::class)
    fun getObject(handle: Int): ByteArray {
        sendCommand(PtpConstants.OP_GET_OBJECT, intArrayOf(handle))
        val data = readData()
        readResponse()
        return data
    }

    /** Polls the interrupt endpoint for an ObjectAdded event. Returns handle or null. */
    fun pollObjectAddedEvent(timeoutMs: Int): Int? {
        val ep = epEvent ?: return null
        val conn = connection ?: return null
        val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(24))
        val len = conn.bulkTransfer(ep, buf, buf.size, timeoutMs)
        if (len < 16) return null
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        bb.int // length
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        bb.int // transaction id
        if (type == PtpConstants.TYPE_EVENT && code == PtpConstants.EVT_OBJECT_ADDED && bb.remaining() >= 4) {
            return bb.int
        }
        return null
    }

    private fun readPtpString(buf: ByteBuffer): String {
        val len = buf.get().toInt() and 0xFF
        if (len == 0) return ""
        val sb = StringBuilder()
        repeat(len) {
            val c = buf.short.toInt() and 0xFFFF
            if (c != 0) sb.append(c.toChar())
        }
        return sb.toString()
    }

    @Throws(IOException::class)
    private fun sendCommand(opCode: Int, params: IntArray) {
        val conn = connection ?: throw IOException("Not connected")
        val out = epOut ?: throw IOException("Not connected")
        transactionId++
        val len = 12 + params.size * 4
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(len)
        buf.putShort(PtpConstants.TYPE_COMMAND.toShort())
        buf.putShort(opCode.toShort())
        buf.putInt(transactionId)
        params.forEach { buf.putInt(it) }
        val sent = conn.bulkTransfer(out, buf.array(), len, 10000)
        if (sent != len) throw IOException("USB write failed")
    }

    @Throws(IOException::class)
    private fun readData(): ByteArray {
        val (type, _, payload) = readContainer()
        if (type != PtpConstants.TYPE_DATA) throw IOException("Expected data container, got $type")
        return payload
    }

    @Throws(IOException::class)
    private fun readResponse(): Int {
        val (type, code, _) = readContainer()
        if (type != PtpConstants.TYPE_RESPONSE) throw IOException("Expected response container, got $type")
        return code
    }

    @Throws(IOException::class)
    private fun readContainer(): Triple<Int, Int, ByteArray> {
        val conn = connection ?: throw IOException("Not connected")
        val inEp = epIn ?: throw IOException("Not connected")
        val chunk = ByteArray(inEp.maxPacketSize.coerceAtLeast(512) * 32)
        var read = conn.bulkTransfer(inEp, chunk, chunk.size, 20000)
        if (read < 12) throw IOException("USB read failed ($read)")
        val header = ByteBuffer.wrap(chunk, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val totalLen = header.int
        val type = header.short.toInt() and 0xFFFF
        val code = header.short.toInt() and 0xFFFF
        header.int // transaction id
        val out = ByteArrayOutputStream(totalLen - 12)
        out.write(chunk, 12, read - 12)
        var remaining = totalLen - read
        while (remaining > 0) {
            read = conn.bulkTransfer(inEp, chunk, minOf(chunk.size, remaining), 20000)
            if (read < 0) throw IOException("USB read failed mid-transfer")
            out.write(chunk, 0, read)
            remaining -= read
        }
        return Triple(type, code, out.toByteArray())
    }
}
