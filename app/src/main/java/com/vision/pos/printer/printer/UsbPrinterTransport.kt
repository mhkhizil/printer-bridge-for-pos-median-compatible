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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * USB (OTG) receipt printer via UsbManager bulk transfers.
 *
 * Device ids look like `usb:04b8:0e15` (VID:PID). Android shows a per-device
 * permission prompt the first time each printer is used; we wait for it.
 */
class UsbPrinterTransport(private val context: Context) {

    /**
     * USB allows one owner per interface, so a second job started while the first
     * one is still writing (a customer and a merchant copy queued together) would
     * lose `claimInterface` and fail the print. Serially queue them instead.
     */
    private val printLock = Mutex()

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun listDevices(): List<PrinterDevice> =
        usbManager?.deviceList?.values.orEmpty()
            .map { PrinterDevice(deviceId(it), deviceLabel(it)) }
            .also { Log.i(TAG, "discover: ${it.size} USB device(s) ${it.map { device -> device.id }}") }

    suspend fun print(rawDeviceId: String, bytes: ByteArray) = printLock.withLock {
        withContext(Dispatchers.IO) {
            val manager = usbManager ?: error("USB host is not available on this device")
            val device = findDevice(manager, rawDeviceId)
                ?: error("USB printer \"$rawDeviceId\" is not connected")
            Log.i(
                TAG,
                "print: id=$rawDeviceId device=${device.deviceName} class=${device.deviceClass} " +
                    "interfaces=${device.interfaceCount}",
            )

            val granted = ensurePermission(manager, device)
            Log.i(TAG, "print: permission ${if (granted) "granted" else "denied"}")
            check(granted) {
                "USB permission was denied for ${deviceLabel(device)}. Accept the system prompt and try again."
            }

            val connection = openConnection(manager, device)
                ?: error("Unable to open USB device ${deviceLabel(device)}")

            try {
                val usbInterface = dataInterface(device)
                    ?: error("No USB bulk OUT endpoint on ${deviceLabel(device)}")
                Log.i(
                    TAG,
                    "print: interface=${usbInterface.id} class=${usbInterface.interfaceClass} " +
                        "protocol=${usbInterface.interfaceProtocol} " +
                        "endpoints=${usbInterface.endpointCount}",
                )
                check(connection.claimInterface(usbInterface, true)) { "Unable to claim USB interface" }
                try {
                    val endpoint = bulkOutEndpoint(usbInterface)
                        ?: error("No USB bulk OUT endpoint on ${deviceLabel(device)}")
                    writeBulk(connection, endpoint, bytes)
                    Log.i(TAG, "print: wrote ${bytes.size} byte(s)")
                } finally {
                    connection.releaseInterface(usbInterface)
                }
            } finally {
                connection.close()
            }
        }
    }

    /**
     * `openDevice` can answer null for a moment right after the grant, before the
     * device is attached to this uid, so it is retried briefly instead of failing
     * the job the operator just allowed.
     */
    private suspend fun openConnection(
        manager: UsbManager,
        device: UsbDevice,
    ): UsbDeviceConnection? {
        repeat(OPEN_ATTEMPTS) { attempt ->
            manager.openDevice(device)?.let { return it }
            if (attempt < OPEN_ATTEMPTS - 1) delay(OPEN_RETRY_MS)
        }
        return null
    }

    /**
     * The interface that actually carries the receipt data. Printers normally put
     * it on interface 0 with the printer class, but combination devices (barcode
     * scanner + printer, HID + printer, a serial bridge next to the printer
     * function) expose other interfaces first, and writing to one of those leaves
     * the printer silent while the transfer still "succeeds".
     */
    private fun dataInterface(device: UsbDevice): UsbInterface? {
        val writable = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .filter { bulkOutEndpoint(it) != null }
        return writable.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
            ?: writable.firstOrNull()
    }

    private fun writeBulk(connection: UsbDeviceConnection, endpoint: UsbEndpoint, bytes: ByteArray) {
        Log.i(
            TAG,
            "write: endpoint=${endpoint.address.toString(16)} " +
                "maxPacket=${endpoint.maxPacketSize} bytes=${bytes.size}",
        )
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
        val wantedId = rawDeviceId.trim()
        val vendorProduct = parseVendorProduct(wantedId)
        return devices.firstOrNull { deviceId(it).equals(wantedId, ignoreCase = true) }
            ?: devices.firstOrNull { it.deviceName == wantedId }
            // An id stored before ids were pinned to ASCII hex, or typed/padded by
            // hand (`04B8:0E15`, `usb:4b8:e15`), still resolves through VID:PID.
            ?: devices.firstOrNull {
                vendorProduct != null &&
                    it.vendorId == vendorProduct.first &&
                    it.productId == vendorProduct.second
            }
            ?: devices.firstOrNull { deviceLabel(it).equals(wantedId, ignoreCase = true) }
    }

    /** Parses `usb:04b8:0e15`, `04B8:0E15` or `usb:4b8:e15` into vendor/product ids. */
    private fun parseVendorProduct(rawDeviceId: String): Pair<Int, Int>? {
        val body = rawDeviceId.trim()
            .lowercase(Locale.ROOT)
            .removePrefix("usb:")
            .removePrefix("usb")
            .trim()
        val parts = body.split(':')
        if (parts.size != 2) return null
        val vendorId = parts[0].trim().removePrefix("0x").toIntOrNull(16) ?: return null
        val productId = parts[1].trim().removePrefix("0x").toIntOrNull(16) ?: return null
        if (vendorId !in 0..0xFFFF || productId !in 0..0xFFFF) return null
        return vendorId to productId
    }

    private suspend fun ensurePermission(manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true

        val answered = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val target = intent.usbDeviceExtra()
                if (target != null && target.deviceName != device.deviceName) return
                answered.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        return try {
            manager.requestPermission(device, permissionIntent(device))
            awaitPermission(manager, device, answered)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Waits for the operator's answer, treating `hasPermission` as the source of
     * truth. A few ROMs deliver the permission broadcast late, without
     * `EXTRA_PERMISSION_GRANTED`, or not at all while still granting the device;
     * trusting only the broadcast made every later job fail with "permission was
     * denied" until the app was restarted.
     */
    private suspend fun awaitPermission(
        manager: UsbManager,
        device: UsbDevice,
        answered: CompletableDeferred<Boolean>,
    ): Boolean = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
        while (!manager.hasPermission(device)) {
            if (answered.isCompleted) break
            delay(PERMISSION_POLL_MS)
        }
        if (answered.isCompleted) answered.await() || manager.hasPermission(device)
        else manager.hasPermission(device)
    } ?: manager.hasPermission(device)

    /**
     * A distinct request code per device: sharing one code made `FLAG_UPDATE_CURRENT`
     * rewrite the extras of a prompt that was still on screen, so the answer could
     * be attributed to the other printer and the job timed out.
     */
    private fun permissionIntent(device: UsbDevice): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /**
     * `String.format` renders digits in the default locale, so `%04x` produced
     * Burmese/Arabic-Indic/Devanagari digits on those tablets and the POS stored an
     * id that never matched the documented `usb:<vid>:<pid>` ASCII form. Pinning the
     * locale keeps the id identical on every device.
     */
    private fun deviceId(device: UsbDevice): String =
        "usb:%04x:%04x".format(Locale.ROOT, device.vendorId, device.productId)

    private fun deviceLabel(device: UsbDevice): String =
        device.productName?.takeIf { it.isNotBlank() }
            ?: device.manufacturerName?.takeIf { it.isNotBlank() }
            ?: deviceId(device)

    private val ACTION_USB_PERMISSION: String
        get() = "${context.packageName}.usb.PRINTER_PERMISSION"

    private companion object {
        const val TAG = "UsbPrinterTransport"
        const val USB_CHUNK_SIZE = 16 * 1024
        const val USB_TIMEOUT_MS = 15_000
        const val PERMISSION_TIMEOUT_MS = 30_000L

        /** Granularity of the `hasPermission` fallback while the prompt is open. */
        const val PERMISSION_POLL_MS = 100L

        /** `openDevice` attempts right after a grant, and the pause between them. */
        const val OPEN_ATTEMPTS = 3
        const val OPEN_RETRY_MS = 100L
    }
}
