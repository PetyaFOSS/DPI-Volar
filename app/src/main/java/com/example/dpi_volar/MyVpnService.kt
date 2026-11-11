package com.example.dpi_volar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var tunWriter: TunWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<SessionKey, TcpSession>()

    companion object {
        const val TAG = "MyVpnService"
        const val ACTION_START = "com.example.dpi_volar.START"
        const val ACTION_STOP = "com.example.dpi_volar.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "dpi_volar_channel"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
        private const val FORWARD_BUFFER_SIZE = 32767

        private val _state = MutableStateFlow<VpnState>(VpnState.Stopped)

        /** Estado real del servicio. La actividad lo observa para pintar la UI. */
        val state: StateFlow<VpnState> = _state.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        internal fun publish(newState: VpnState) {
            isRunning = newState is VpnState.Running
            _state.value = newState
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Protección DPI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado de la protección contra DPI"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("DPI-Volar activo")
            .setContentText("Tu conexión está protegida contra bloqueos DPI")
            .setSmallIcon(R.drawable.ic_stat_dpi_volar)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_stat_dpi_volar, "Detener", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                startVpn()
                return if (isRunning) START_STICKY else START_NOT_STICKY
            }
        }
    }

    private suspend fun handleDnsQuery(
        queryPayload: ByteArray,
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        tunWriter: TunWriter
    ) {
        val cachedResponse = DnsCache.get(queryPayload)
        if (cachedResponse != null) {
            val replyPacket = PacketBuilder.buildUdpPacket(
                srcIp = dstIpBytes, dstIp = srcIpBytes,
                srcPort = dstPort, dstPort = srcPort,
                payload = cachedResponse
            )
            tunWriter.write(replyPacket)
            return
        }

        try {
            java.net.DatagramSocket().use { socket ->
                protect(socket)

                val dnsServerAddr = java.net.InetAddress.getByAddress(dstIpBytes)
                val outPacket = java.net.DatagramPacket(queryPayload, queryPayload.size, dnsServerAddr, dstPort)

                withContext(NetworkDispatcher.IO) {
                    socket.soTimeout = 5000
                    socket.send(outPacket)

                    val responseBuffer = ByteArray(4096)
                    val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)

                    val responseData = responseBuffer.copyOf(responsePacket.length)

                    DnsCache.put(queryPayload, responseData)

                    val replyPacket = PacketBuilder.buildUdpPacket(
                        srcIp = dstIpBytes, dstIp = srcIpBytes,
                        srcPort = dstPort, dstPort = srcPort,
                        payload = responseData
                    )
                    tunWriter.write(replyPacket)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolviendo DNS: ${e.message}")
        }
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            publish(VpnState.Running)
            return
        }

        createNotificationChannel()

        try {
            startForegroundCompat(buildNotification())
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo iniciar el servicio en primer plano", t)
            publish(VpnState.Error("No se pudo iniciar el servicio en primer plano."))
            stopSelf()
            return
        }

        publish(VpnState.Starting)

        val builder = Builder()
            .setSession("DPI-Volar")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(MTU)

        val newInterface = try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish() falló", t)
            null
        }

        if (newInterface == null) {
            Log.e(TAG, "No se pudo establecer la interfaz VPN")
            publish(
                VpnState.Error(
                    "Android no permitió crear la VPN. Revisa el permiso de VPN e inténtalo de nuevo."
                )
            )
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
            return
        }

        vpnInterface = newInterface
        Log.i(TAG, "VPN establecida, iniciando bucle de reenvío")
        publish(VpnState.Running)
        job = scope.launch { forwardPackets() }
    }

    private suspend fun forwardPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val writer = TunWriter(output, scope)
        writer.start()
        tunWriter = writer
        val buffer = ByteArray(FORWARD_BUFFER_SIZE)

        try {
            while (coroutineContext.isActive) {
                val length = try {
                    input.read(buffer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error leyendo de la TUN: ${e.message}")
                    break
                }

                if (length < 0) {
                    Log.e(TAG, "read() de la TUN devolvió -1, deteniendo bucle de reenvío")
                    break
                }
                if (length == 0) {
                    delay(5)
                    continue
                }

                val packet = IPv4Packet(buffer, length)
                if (!packet.isValid) continue

                if (packet.isUdp()) {
                    val udp = packet.getUdpSegment() ?: continue
                    if (udp.destPort == 53) {
                        val queryPayload = udp.getPayload()
                        if (queryPayload.isNotEmpty()) {
                            val srcIpBytes = packet.sourceIpBytes
                            val dstIpBytes = packet.destIpBytes
                            val srcPort = udp.sourcePort
                            val dstPort = udp.destPort

                            scope.launch {
                                handleDnsQuery(queryPayload, srcIpBytes, dstIpBytes, srcPort, dstPort, writer)
                            }
                        }
                    }
                    continue
                }

                if (!packet.isTcp()) continue

                val tcp = packet.getTcpSegment() ?: continue
                if (!tcp.isValid) continue
                val sessionKey = SessionKey(packet.sourceIp, tcp.sourcePort, packet.destIp, tcp.destPort)

                if (tcp.isRst) {
                    sessions[sessionKey]?.onClientRst()
                    continue
                }

                if (tcp.isSyn && !tcp.isAck) {
                    val existing = sessions[sessionKey]
                    if (existing != null) existing.close()
                    val session = TcpSession(
                        key = sessionKey,
                        clientIpBytes = packet.sourceIpBytes,
                        serverIpBytes = packet.destIpBytes,
                        vpnService = this,
                        tunWriter = writer,
                        scope = scope,
                        onClosed = { key -> sessions.remove(key) }
                    )
                    sessions[sessionKey] = session
                    scope.launch { session.start(tcp.seqNum) }
                    continue
                }

                val session = sessions[sessionKey] ?: continue
                if (tcp.hasPayload()) {
                    if (tcp.isTlsClientHello()) {
                        Log.i(TAG, "ClientHello detectado: ${packet.sourceIp}:${tcp.sourcePort} -> ${packet.destIp}:${tcp.destPort}")
                    }
                    session.onClientData(tcp.getPayload(), tcp.seqNum)
                }
                if (tcp.isFin) {
                    session.onClientFin()
                }
            }
        } finally {
            // Si el bucle terminó sin que nadie pidiera detener la VPN, fue un fallo:
            // no dejamos la UI en "activa" mientras el túnel ya no procesa nada.
            if (isRunning) {
                Log.e(TAG, "El bucle de reenvío terminó inesperadamente")
                stopVpn("El túnel VPN se cerró inesperadamente. Inténtalo de nuevo.")
            }
        }
    }

    private fun stopVpn(errorMessage: String? = null) {
        isRunning = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        job?.cancel()
        job = null
        tunWriter?.close()
        tunWriter = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando interfaz: ${e.message}")
        }
        vpnInterface = null
        DnsCache.clear()

        // No pisamos el mensaje de error con "detenido" cuando onDestroy()
        // vuelve a llamar a stopVpn() después de un fallo.
        when {
            errorMessage != null -> publish(VpnState.Error(errorMessage))
            _state.value !is VpnState.Error -> publish(VpnState.Stopped)
        }
        stopSelf()
    }

    override fun onRevoke() {
        Log.w(TAG, "El permiso de VPN fue revocado por el sistema")
        stopVpn("La VPN se desconectó porque el sistema revocó el permiso.")
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }
}
