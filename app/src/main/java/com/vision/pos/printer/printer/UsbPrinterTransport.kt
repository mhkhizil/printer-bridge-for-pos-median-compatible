package com.vision.pos.printer.printer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * USB (OTG) receipt printer via UsbManager bulk transfers.
 *
 * Device ids look like `usb:04b8:0e15` (VID:PID). Android shows a per-device
 * permission prompt the first time each printer is used; we wait for it.
 */
class UsbPrinterTransport(private val context: Context) {

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun listDevices(): List<PrinterDevice> =
        usbManager?.deviceList?.values.orEmpty()
            .map { PrinterDevice(deviceId(it), deviceLabel(it)) }

    suspend fun print(rawDeviceId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val manager = usbManager ?: error("USB host is not available on this device")
        val device = findDevice(manager, rawDeviceId)
            ?: error("USB printer \"$rawDeviceId\" is not connected")

        check(ensurePermission(manager, device)) {
            "USB permission was denied for ${deviceLabel(device)}. Accept the system prompt and try again."
        }

        val connection = manager.openDevice(device)
            ?: error("Unable to open USB device ${deviceLabel(device)}")

        try {
            val usbInterface = device.getInterface(0)
            check(connection.claimInterface(usbInterface, true)) { "Unable to claim USB interface" }
            try {
                val endpoint = bulkOutEndpoint(usbInterface)
                    ?: error("No USB bulk OUT endpoint on ${deviceLabel(device)}")
                writeBulk(connection, endpoint, bytes)
            } finally {
                connection.releaseInterface(usbInterface)
            }
        } finally {
            connection.close()
        }
    }

    private fun writeBulk(connection: UsbDeviceConnection, endpoint: UsbEndpoint, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(USB_CHUNK_SIZE, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + length)
            val written = connection.bulkTransfer(endpoint, chunk, length, USB_TIMEOUT_MS)
            if (written < 0) error("USB bulk transfer failed (code $written)")
            if (written == 0) error("USB bulk transfer stalled")
            offset += written
        }
    }

    private fun bulkOutEndpoint(usbInterface: UsbInterface): UsbEndpoint? =
        (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_OUT
            }

    private fun findDevice(manager: UsbManager, rawDeviceId: String): UsbDevice? {
        val devices = manager.deviceList.values.toList()
        return devices.firstOrNull { deviceId(it) == rawDeviceId }
            ?: devices.firstOrNull { it.deviceName == rawDeviceId }
            ?: devices.firstOrNull { deviceLabel(it).equals(rawDeviceId, ignoreCase = true) }
    }

    private suspend fun ensurePermission(manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true

        val granted = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val target = intent.usbDeviceExtra()
                if (target != null && target.deviceName != device.deviceName) return
                granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        return try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { granted.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun deviceId(device: UsbDevice): String =
        "usb:%04x:%04x".format(device.vendorId, device.productId)

    private fun deviceLabel(device: UsbDevice): String =
        device.productName?.takeIf { it.isNotBlank() }
            ?: device.manufacturerName?.takeIf { it.isNotBlank() }
            ?: deviceId(device)

    private val ACTION_USB_PERMISSION: String
        get() = "${context.packageName}.usb.PRINTER_PERMISSION"

    private companion object {
        const val USB_CHUNK_SIZE = 16 * 1024
        const val USB_TIMEOUT_MS = 15_000
        const val PERMISSION_TIMEOUT_MS = 30_000L
    }
}
