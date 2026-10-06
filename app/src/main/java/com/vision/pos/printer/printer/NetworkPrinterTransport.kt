package com.vision.pos.printer.printer

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Raw ESC/POS over TCP, the protocol receipt printers expose on port 9100. */
class NetworkPrinterTransport {

    suspend fun print(host: String?, port: Int?, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(!host.isNullOrBlank()) { "Network printer IP address is required" }
        val targetPort = port?.takeIf { it in 1..65535 } ?: DEFAULT_PORT

        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, targetPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            socket.getOutputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 9100
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val IO_TIMEOUT_MS = 15_000
    }
}
