package com.example.engine.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal data class ParsedIpv4UdpDnsPacket(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dnsPayload: ByteArray,
)

internal object DnsPacketCodec {

    fun parseIpv4UdpDnsPacket(packet: ByteArray, length: Int): ParsedIpv4UdpDnsPacket? {
        if (length < 28 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl + 8 > length) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // UDP only

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val payloadEnd = minOf(length, ihl + udpLength)
        val payloadStart = ihl + 8
        if (payloadEnd <= payloadStart) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val dnsPayload = packet.copyOfRange(payloadStart, payloadEnd)
        return ParsedIpv4UdpDnsPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload,
        )
    }

    fun buildIpv4UdpDnsResponse(
        query: ParsedIpv4UdpDnsPacket,
        dnsResponsePayload: ByteArray,
        identification: Int = 0x1111,
    ): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + dnsResponsePayload.size
        val totalLen = ipHeaderLen + udpLen
        val out = ByteArray(totalLen)

        // IPv4 Header (20 bytes)
        out[0] = 0x45.toByte() // Version 4, IHL 5
        out[1] = 0x00
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = ((identification ushr 8) and 0xFF).toByte()
        out[5] = (identification and 0xFF).toByte()
        out[6] = 0x40.toByte() // Don't fragment
        out[7] = 0x00
        out[8] = 64.toByte() // TTL
        out[9] = 17.toByte() // Protocol UDP
        out[10] = 0x00
        out[11] = 0x00
        // Swap src <-> dst IPs
        System.arraycopy(query.dstIp, 0, out, 12, 4)
        System.arraycopy(query.srcIp, 0, out, 16, 4)

        val ipChecksum = computeChecksum(out, 0, ipHeaderLen)
        out[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header (8 bytes) - swap src <-> dst ports
        out[20] = ((query.dstPort ushr 8) and 0xFF).toByte()
        out[21] = (query.dstPort and 0xFF).toByte()
        out[22] = ((query.srcPort ushr 8) and 0xFF).toByte()
        out[23] = (query.srcPort and 0xFF).toByte()
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0x00
        out[27] = 0x00

        System.arraycopy(dnsResponsePayload, 0, out, 28, dnsResponsePayload.size)

        val udpChecksum = computeUdpIpv4Checksum(
            srcIp = query.dstIp,
            dstIp = query.srcIp,
            udpSegment = out,
            udpOffset = 20,
            udpLen = udpLen,
        )
        out[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
        out[27] = (udpChecksum and 0xFF).toByte()

        return out
    }

    internal fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun computeUdpIpv4Checksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpSegment: ByteArray,
        udpOffset: Int,
        udpLen: Int,
    ): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 17L // UDP protocol
        sum += udpLen.toLong()

        var i = udpOffset
        val end = udpOffset + udpLen
        while (i + 1 < end) {
            val word = ((udpSegment[i].toInt() and 0xFF) shl 8) or (udpSegment[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (udpSegment[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFFL).toInt()
        return if (result == 0) 0xFFFF else result
    }
}

class CloudflareDnsVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    private var tunnelJob: Job? = null

    private val protectedDohClient: OkHttpClient by lazy {
        val protectedFactory = object : SocketFactory() {
            private val delegate = getDefault()

            override fun createSocket(): Socket = delegate.createSocket().also { protect(it) }
            override fun createSocket(host: String?, port: Int): Socket =
                delegate.createSocket(host, port).also { protect(it) }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).also { protect(it) }
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                delegate.createSocket(host, port).also { protect(it) }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(address, port, localAddress, localPort).also { protect(it) }
        }
        OkHttpClient.Builder()
            .socketFactory(protectedFactory)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startVpnTunnel()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpnTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpnTunnel() {
        if (tunnelJob?.isActive == true && vpnInterface != null) {
            _isVpnActive.value = true
            return
        }

        val pfd = runCatching {
            Builder()
                .setSession("Blackhole 1.1.1.1 VPN")
                .addAddress("10.111.111.1", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .addRoute("1.1.1.1", 32)
                .addRoute("1.0.0.1", 32)
                .setMtu(1500)
                .setBlocking(true)
                .establish()
        }.getOrNull()

        if (pfd == null) {
            _isVpnActive.value = false
            stopSelf()
            return
        }

        vpnInterface = pfd
        _isVpnActive.value = true

        tunnelJob?.cancel()
        tunnelJob = scope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(4096)

            try {
                while (isActive && vpnInterface != null) {
                    val length = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (length <= 0) {
                        if (length < 0) break
                        continue
                    }
                    val packetCopy = buffer.copyOf(length)
                    val parsed = DnsPacketCodec.parseIpv4UdpDnsPacket(packetCopy, length) ?: continue
                    launch {
                        val responseDns = resolveOverCloudflare1111(parsed.dnsPayload) ?: return@launch
                        val responsePacket = DnsPacketCodec.buildIpv4UdpDnsResponse(parsed, responseDns)
                        synchronized(writeLock) {
                            runCatching { output.write(responsePacket) }
                        }
                        _dnsQueriesResolved.value = _dnsQueriesResolved.value + 1L
                    }
                }
            } finally {
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
    }

    private fun resolveOverCloudflare1111(dnsQuery: ByteArray): ByteArray? {
        // Primary: Encrypted Cloudflare 1.1.1.1 DNS-over-HTTPS
        val dohEndpoints = listOf(
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
        )
        val mediaType = "application/dns-message".toMediaType()
        for (endpoint in dohEndpoints) {
            val bytes = runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(dnsQuery.toRequestBody(mediaType))
                    .header("Accept", "application/dns-message")
                    .header("Content-Type", "application/dns-message")
                    .build()
                protectedDohClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            }.getOrNull()
            if (bytes != null && bytes.size >= 12) {
                return bytes
            }
        }

        // Secondary fallback: Protected direct UDP to 1.1.1.1:53
        return runCatching {
            DatagramSocket().use { sock ->
                protect(sock)
                sock.soTimeout = 3500
                val addr = InetAddress.getByName("1.1.1.1")
                sock.send(DatagramPacket(dnsQuery, dnsQuery.size, addr, 53))
                val respBuf = ByteArray(2048)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPacket)
                respBuf.copyOf(respPacket.length)
            }
        }.getOrNull()
    }

    private fun stopVpnTunnel() {
        tunnelJob?.cancel()
        tunnelJob = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        _isVpnActive.value = false
    }

    companion object {
        const val ACTION_START = "com.example.action.VPN_1111_START"
        const val ACTION_STOP = "com.example.action.VPN_1111_STOP"

        private val _isVpnActive = MutableStateFlow(false)
        val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

        private val _dnsQueriesResolved = MutableStateFlow(0L)
        val dnsQueriesResolved: StateFlow<Long> = _dnsQueriesResolved.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, CloudflareDnsVpnService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CloudflareDnsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
