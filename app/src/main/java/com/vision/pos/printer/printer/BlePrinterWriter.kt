package com.vision.pos.printer.printer

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Minimal BLE GATT writer for ESC/POS printers that expose a write
 * characteristic instead of classic SPP.
 *
 * The first writable characteristic found on the device is used. Common ESC/POS
 * BLE services (FFF0/FFE1, 18F0/2AF1) expose exactly one writable characteristic,
 * so a general "first writable" search is the most portable approach.
 */
class BlePrinterWriter(private val context: Context) {

    suspend fun write(device: BluetoothDevice, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val session = GattSession(context, device)
        val gatt = session.open()
        try {
            val characteristic = session.writableCharacteristic()
                ?: error("No writable BLE characteristic on ${device.address}")
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(DEFAULT_CHUNK_SIZE, bytes.size - offset)
                session.write(gatt, characteristic, bytes.copyOfRange(offset, offset + length))
                offset += length
            }
        } finally {
            session.close(gatt)
        }
    }

    private class GattSession(private val context: Context, private val device: BluetoothDevice) {
        private var gattRef: BluetoothGatt? = null
        private val ready = CompletableDeferred<Unit>()
        private var writeSignal: CompletableDeferred<Unit>? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED ->
                        fail(IllegalStateException("BLE link to ${device.address} dropped"))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (!ready.isCompleted) ready.complete(Unit)
                } else {
                    fail(IllegalStateException("BLE service discovery failed (status $status)"))
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val signal = writeSignal ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) signal.complete(Unit)
                else signal.completeExceptionally(IllegalStateException("BLE write failed (status $status)"))
            }
        }

        suspend fun open(): BluetoothGatt {
            val gatt = device.connectGatt(context, false, callback)
                ?: error("Unable to start a BLE connection to ${device.address}")
            gattRef = gatt
            return try {
                withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
                gatt
            } catch (caught: Throwable) {
                close(gatt)
                throw caught
            }
        }

        fun writableCharacteristic(): BluetoothGattCharacteristic? {
            val characteristics = gattRef?.services.orEmpty().flatMap { it.characteristics }
            return characteristics.firstOrNull {
                it.supports(BluetoothGattCharacteristic.PROPERTY_WRITE)
            } ?: characteristics.firstOrNull {
                it.supports(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
            }
        }

        suspend fun write(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            chunk: ByteArray,
        ) {
            val signal = CompletableDeferred<Unit>()
            writeSignal = signal

            val withResponse = characteristic.supports(BluetoothGattCharacteristic.PROPERTY_WRITE)
            val writeType = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, chunk, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            check(started) { "Unable to start BLE write" }

            withTimeout(WRITE_TIMEOUT_MS) { signal.await() }
        }

        fun close(gatt: BluetoothGatt) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }

        private fun fail(caught: Throwable) {
            if (!ready.isCompleted) ready.completeExceptionally(caught)
            writeSignal?.completeExceptionally(caught)
        }

        private fun BluetoothGattCharacteristic.supports(property: Int): Boolean =
            (properties and property) != 0
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 20
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
