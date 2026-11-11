package com.example.dpi_volar

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class SessionKey(
    val srcIp: String, val srcPort: Int,
    val dstIp: String, val dstPort: Int
)

class TcpSession(
    val key: SessionKey,
    private val clientIpBytes: ByteArray,
    private val serverIpBytes: ByteArray,
    private val vpnService: VpnService,
    private val tunWriter: TunWriter,
    private val scope: CoroutineScope,
    private val onClosed: (SessionKey) -> Unit
) {
    companion object {
        const val TAG = "TcpSession"
        private const val SOCKET_READ_TIMEOUT_MS = 60_000
        private const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * Carga TCP máxima por paquete. El MTU de la interfaz es 1500 y la
         * cabecera IP+TCP ocupa 40 bytes, pero dejamos margen para no generar
         * paquetes sobredimensionados en redes con MTU algo menor.
         */
        private const val MAX_TCP_PAYLOAD = 1_400
    }

    @Volatile
    private var clientSeq: Long = 0

    @Volatile
    private var serverSeq: Long = 0

    @Volatile
    private var closed = false

    private lateinit var socket: Socket

    @Volatile
    private var clientFinReceived = false
    private var finSentToClient = false
    private var firstDataSent = false
    private var currentTechnique: DpiTechnique = DpiTechnique.NONE

    private data class OutgoingChunk(val payload: ByteArray, val seq: Long)

    private val writeQueue = Channel<OutgoingChunk>(Channel.UNLIMITED)
    private val socketReady = CompletableDeferred<Unit>()
    private val ackSignal = Channel<Unit>(Channel.CONFLATED)

    suspend fun start(initialClientSeq: Long) {
        clientSeq = initialClientSeq + 1
        serverSeq = (0..Int.MAX_VALUE).random().toLong()

        socket = Socket()

        // Se responde el SYN-ACK de inmediato (ahorra un RTT) mientras la
        // conexión real se establece en paralelo.
        sendControl(PacketBuilder.TCP_SYN or PacketBuilder.TCP_ACK)
        serverSeq += 1

        scope.launch {
            for (unit in ackSignal) {
                sendControl(PacketBuilder.TCP_ACK)
            }
        }

        scope.launch { processWriteQueue() }

        try {
            withContext(NetworkDispatcher.IO) {
                socket.bind(InetSocketAddress(0))
            }

            val protected = vpnService.protect(socket)
            Log.d(TAG, "protect() resultado: $protected para ${key.dstIp}:${key.dstPort}")

            withContext(NetworkDispatcher.IO) {
                val addr = InetAddress.getByAddress(serverIpBytes)
                socket.connect(InetSocketAddress(addr, key.dstPort), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
            }

            socketReady.complete(Unit)
            scope.launch { readFromServer() }
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "No se pudo conectar a ${key.dstIp}:${key.dstPort} -> ${e.message}")
                sendControl(PacketBuilder.TCP_RST)
            }
            socketReady.completeExceptionally(e)
            close()
        }
    }

    fun onClientData(payload: ByteArray, incomingSeq: Long) {
        if (closed || clientFinReceived || payload.isEmpty()) return

        val payloadEnd = incomingSeq + payload.size
        if (payloadEnd <= clientSeq) {
            ackSignal.trySend(Unit)
            return
        }

        clientSeq = payloadEnd
        ackSignal.trySend(Unit)
        writeQueue.trySend(OutgoingChunk(payload, incomingSeq))
    }

    /**
     * FIN del cliente: no hay que cerrar la sesión todavía. Solo se cierra la
     * mitad de escritura hacia el servidor; la respuesta que el servidor aún
     * pueda mandar debe seguir llegando al cliente.
     */
    fun onClientFin() {
        if (closed || clientFinReceived) return
        Log.d(TAG, "FIN del cliente para ${key.dstIp}:${key.dstPort}")
        clientFinReceived = true
        ackSignal.trySend(Unit)
        writeQueue.close()
    }

    /** RST del cliente: la conexión se aborta de verdad. */
    fun onClientRst() {
        if (closed) return
        Log.d(TAG, "RST del cliente para ${key.dstIp}:${key.dstPort}")
        close()
    }

    private suspend fun processWriteQueue() {
        try {
            socketReady.await()

            for (chunk in writeQueue) {
                val isClientHello = !firstDataSent &&
                        chunk.payload.size > 6 &&
                        (chunk.payload[0].toInt() and 0xFF) == 0x16 &&
                        (chunk.payload[5].toInt() and 0xFF) == 0x01

                if (isClientHello && DpiConfig.enabled) {
                    currentTechnique = TechniqueStats.techniqueToUse(key.dstIp, key.dstPort)
                    applyTechnique(currentTechnique, chunk.payload)
                } else {
                    writePayload(chunk.payload)
                }

                firstDataSent = true
            }

            // El cliente terminó de enviar (FIN). Cerramos el lado de escritura
            // del socket real, pero seguimos leyendo hasta que el servidor cierre.
            if (clientFinReceived && !closed) {
                withContext(NetworkDispatcher.IO) {
                    try {
                        socket.shutdownOutput()
                        Log.d(TAG, "Mitad de escritura cerrada (FIN del cliente) para ${key.dstIp}:${key.dstPort}")
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "Error escribiendo al servidor real: ${e.message}")
                if (currentTechnique != DpiTechnique.NONE) {
                    TechniqueStats.reportFailure(key.dstIp, key.dstPort, currentTechnique)
                }
            }
            close()
        }
    }

    private suspend fun applyTechnique(technique: DpiTechnique, data: ByteArray) {
        when (technique) {
            DpiTechnique.SPLIT -> sendSplit(data, reverse = false)
            DpiTechnique.DISORDER -> sendSplit(data, reverse = true)
            DpiTechnique.FAKE_PACKET -> sendFakePacketThenReal(data)
            DpiTechnique.NONE -> writePayload(data)
        }
    }

    private suspend fun writePayload(data: ByteArray) {
        withContext(NetworkDispatcher.IO) {
            socket.getOutputStream().write(data)
            socket.getOutputStream().flush()
        }
    }

    private suspend fun sendSplit(data: ByteArray, reverse: Boolean) {
        val splitAt: Int
        val reason: String

        if (DpiConfig.useSniSplit) {
            val sniInfo = TlsParser.findSniHostname(data)
            if (sniInfo != null) {
                val (hostnameStart, hostnameLen) = sniInfo
                splitAt = (hostnameStart + hostnameLen / 2).coerceIn(1, data.size - 1)
                reason = "dentro del SNI"
            } else {
                splitAt = DpiConfig.splitPosition.coerceIn(1, data.size - 1)
                reason = "SNI no encontrado, posición fija"
            }
        } else {
            splitAt = DpiConfig.splitPosition.coerceIn(1, data.size - 1)
            reason = "corte fijo"
        }

        val part1 = data.copyOfRange(0, splitAt)
        val part2 = data.copyOfRange(splitAt, data.size)

        Log.i(TAG, "Técnica ${if (reverse) "DISORDER" else "SPLIT"}: ${part1.size}+${part2.size}B ($reason) para ${key.dstIp}:${key.dstPort}")

        withContext(NetworkDispatcher.IO) {
            val out = socket.getOutputStream()
            if (reverse) {
                out.write(part2); out.flush()
                if (DpiConfig.fragmentDelayMs > 0) delay(DpiConfig.fragmentDelayMs)
                out.write(part1); out.flush()
            } else {
                out.write(part1); out.flush()
                if (DpiConfig.fragmentDelayMs > 0) delay(DpiConfig.fragmentDelayMs)
                out.write(part2); out.flush()
            }
        }
    }

    private suspend fun sendFakePacketThenReal(data: ByteArray) {
        Log.i(TAG, "Técnica FAKE_PACKET para ${key.dstIp}:${key.dstPort}")

        withContext(NetworkDispatcher.IO) {
            val out = socket.getOutputStream()

            val fakeHello = buildFakeClientHello(data.size.coerceAtMost(64))
            out.write(fakeHello); out.flush()

            if (DpiConfig.fragmentDelayMs > 0) delay(DpiConfig.fragmentDelayMs)

            out.write(data); out.flush()
        }
    }

    private fun buildFakeClientHello(size: Int): ByteArray {
        val safeSize = size.coerceAtLeast(6)
        val fake = ByteArray(safeSize)
        fake[0] = 0x16
        fake[1] = 0x03; fake[2] = 0x01
        fake[3] = 0; fake[4] = (safeSize - 5).toByte()
        fake[5] = 0x01
        for (i in 6 until safeSize) fake[i] = (0..255).random().toByte()
        return fake
    }

    private suspend fun readFromServer() {
        val buffer = ByteArray(16384)
        var reportedSuccess = false
        try {
            while (!closed) {
                val n = try {
                    withContext(NetworkDispatcher.IO) { socket.getInputStream().read(buffer) }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Timeout de lectura sin actividad, cerrando sesión inactiva ${key.dstIp}:${key.dstPort}")
                    break
                }
                if (n <= 0) break

                if (!reportedSuccess && currentTechnique != DpiTechnique.NONE) {
                    TechniqueStats.reportSuccess(key.dstIp, key.dstPort, currentTechnique)
                    reportedSuccess = true
                }

                sendData(buffer.copyOf(n))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "Conexión con servidor real cerrada: ${e.message}")
            }
        } finally {
            close(sendFin = true)
        }
    }

    /**
     * Envía los datos del servidor al cliente troceados para que ningún
     * paquete IP supere el MTU de la interfaz TUN.
     */
    private fun sendData(data: ByteArray) {
        var offset = 0
        while (offset < data.size && !closed) {
            val chunkSize = minOf(MAX_TCP_PAYLOAD, data.size - offset)
            val payload = if (offset == 0 && chunkSize == data.size) {
                data
            } else {
                data.copyOfRange(offset, offset + chunkSize)
            }

            val packet = PacketBuilder.buildTcpPacket(
                srcIp = serverIpBytes, dstIp = clientIpBytes,
                srcPort = key.dstPort, dstPort = key.srcPort,
                seqNum = serverSeq, ackNum = clientSeq,
                flags = PacketBuilder.TCP_ACK or PacketBuilder.TCP_PSH,
                payload = payload
            )
            writeToTun(packet)
            serverSeq += chunkSize
            offset += chunkSize
        }
    }

    private fun sendControl(flags: Int) {
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = serverIpBytes, dstIp = clientIpBytes,
            srcPort = key.dstPort, dstPort = key.srcPort,
            seqNum = serverSeq, ackNum = clientSeq,
            flags = flags
        )
        writeToTun(packet)
    }

    private fun writeToTun(packet: ByteArray) {
        tunWriter.write(packet)
    }

    fun close(sendFin: Boolean = false) {
        if (closed) return
        closed = true

        if (sendFin && !finSentToClient) {
            finSentToClient = true
            sendControl(PacketBuilder.TCP_FIN or PacketBuilder.TCP_ACK)
        }

        writeQueue.close()
        ackSignal.close()
        try {
            if (::socket.isInitialized) socket.close()
        } catch (_: Exception) {
        }
        onClosed(key)
    }
}
