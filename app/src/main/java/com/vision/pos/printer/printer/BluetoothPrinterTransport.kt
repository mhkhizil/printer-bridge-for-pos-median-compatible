package com.vision.pos.printer.printer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bluetooth receipt printer.
 *
 * Classic RFCOMM/SPP is tried first (what almost every ESC/POS thermal printer
 * speaks). LE-only or dual-mode printers that reject SPP fall back to a minimal
 * BLE GATT write. Device ids are the MAC address.
 */
class BluetoothPrinterTransport(
    private val context: Context,
    /** Requests BLUETOOTH_CONNECT/SCAN and resolves once the operator answers. */
    private val requestBluetoothPermission: suspend () -> Boolean = { true },
) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Paired printers. Requests the Bluetooth permission on demand (the operator's
     * first action may be "Discover", not "Print") and throws a readable message
     * when it is declined.
     */
    suspend fun listDevices(): List<PrinterDevice> {
        ensureBluetoothPermission()
        return bondedDevices()
    }

    /**
     * Bluetooth part of a "discover everything" call: a declined permission must not
     * hide the USB printers, so it simply contributes nothing.
     */
    suspend fun listDevicesOrEmpty(): List<PrinterDevice> = try {
        listDevices()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        emptyList()
    }

    @Suppress("MissingPermission") // guaranteed by ensureBluetoothPermission()
    private fun bondedDevices(): List<PrinterDevice> =
        adapter?.bondedDevices.orEmpty()
            .map { PrinterDevice(it.address, it.name ?: it.address) }

    suspend fun print(rawDeviceId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val localAdapter = adapter ?: error("Bluetooth is not available on this device")
        ensureBluetoothPermission()

        val device = resolveDevice(localAdapter, rawDeviceId)
            ?: error("Bluetooth printer \"$rawDeviceId\" is not paired")

        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            BlePrinterWriter(context).write(device, bytes)
            return@withContext
        }

        try {
            printClassic(localAdapter, device, bytes)
        } catch (_: IOException) {
            // Dual-mode printers sometimes only accept the BLE GATT path.
            BlePrinterWriter(context).write(device, bytes)
        }
    }

    private fun printClassic(bluetoothAdapter: BluetoothAdapter, device: BluetoothDevice, bytes: ByteArray) {
        runCatching { bluetoothAdapter.cancelDiscovery() }
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket.use {
            it.connect()
            it.outputStream.use { output ->
                output.write(bytes)
                output.flush()
            }
        }
    }

    private fun resolveDevice(bluetoothAdapter: BluetoothAdapter, rawDeviceId: String): BluetoothDevice? {
        val bonded = bluetoothAdapter.bondedDevices.orEmpty()
        bonded.firstOrNull { it.address.equals(rawDeviceId, ignoreCase = true) }?.let { return it }
        bonded.firstOrNull { (it.name ?: "").equals(rawDeviceId, ignoreCase = true) }?.let { return it }
        return runCatching { bluetoothAdapter.getRemoteDevice(normalizeAddress(rawDeviceId)) }.getOrNull()
    }

    private fun normalizeAddress(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }.chunked(2).joinToString(":")

    /** Requested on first Bluetooth use, never at launch. */
    private suspend fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (hasConnectPermission()) return
        requestBluetoothPermission()
        check(hasConnectPermission()) {
            "Bluetooth permission is required. Grant \"Nearby devices\" access and try again."
        }
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
