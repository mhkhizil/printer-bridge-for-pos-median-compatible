package com.vision.pos.printer.printer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Routes bridge calls to the right transport. */
class PrinterManager(
    context: Context,
    /**
     * Requests the Bluetooth runtime permissions from the host and resolves once
     * the operator answers. Invoked lazily on the first Bluetooth print or
     * Bluetooth discovery so Wi-Fi/USB-only venues never see the prompt.
     */
    bluetoothPermissionRequester: suspend () -> Boolean = { true },
) {

    private val network = NetworkPrinterTransport()
    private val usb = UsbPrinterTransport(context)
    private val bluetooth = BluetoothPrinterTransport(context, bluetoothPermissionRequester)

    /** Nothing to pre-open: each transport connects per job. */
    fun connect() = Unit

    fun disconnect() = Unit

    suspend fun discover(transport: PrinterTransport?): List<PrinterDevice> = when (transport) {
        PrinterTransport.NETWORK -> emptyList() // host/port are typed by the operator
        PrinterTransport.USB -> usb.listDevices()
        PrinterTransport.BLUETOOTH -> bluetooth.listDevices()
        // "Everything": a declined Bluetooth permission must not hide USB printers.
        null -> usb.listDevices() + bluetooth.listDevicesOrEmpty()
    }

    suspend fun printRaw(
        transport: PrinterTransport,
        host: String?,
        port: Int?,
        deviceId: String?,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        when (transport) {
            PrinterTransport.NETWORK -> network.print(host, port, bytes)
            PrinterTransport.USB ->
                usb.print(requireNotNull(deviceId) { "USB printer deviceId is required" }, bytes)
            PrinterTransport.BLUETOOTH ->
                bluetooth.print(requireNotNull(deviceId) { "Bluetooth printer deviceId is required" }, bytes)
        }
    }
}
