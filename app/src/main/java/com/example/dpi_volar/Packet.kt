package com.example.dpi_volar

class IPv4Packet(private val raw: ByteArray, private val length: Int) {

    val version: Int by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 1) 0 else (raw[0].toInt() shr 4) and 0x0F
    }

    val ihl: Int by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 1) 0 else (raw[0].toInt() and 0x0F) * 4
    }

    private val declaredTotalLength: Int by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 4) 0
        else ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
    }

    /** Longitud efectiva del datagrama, ya acotada a los bytes realmente leídos. */
    val packetLength: Int = when {
        declaredTotalLength in 20..length -> declaredTotalLength
        length in 20..raw.size -> length
        else -> 0
    }

    /** Un paquete malformado o truncado se descarta antes de tocar sus campos. */
    val isValid: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        length >= 20 &&
                raw.size >= length &&
                version == 4 &&
                ihl in 20..packetLength &&
                packetLength <= raw.size
    }

    fun isUdp(): Boolean = isValid && protocol == 17

    fun getUdpSegment(): UdpSegment? {
        if (!isUdp() || packetLength < payloadOffset + 8) return null
        return UdpSegment(raw, payloadOffset, packetLength).takeIf { it.isValid }
    }

    val protocol: Int by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 10) -1 else raw[9].toInt() and 0xFF
    }

    val sourceIp: String by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 16) "0.0.0.0" else ipToString(raw, 12)
    }

    val destIp: String by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 20) "0.0.0.0" else ipToString(raw, 16)
    }

    val sourceIpBytes: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 16) ByteArray(4) else raw.copyOfRange(12, 16)
    }

    val destIpBytes: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        if (raw.size < 20) ByteArray(4) else raw.copyOfRange(16, 20)
    }

    val payloadOffset: Int by lazy(LazyThreadSafetyMode.NONE) { ihl }

    fun isTcp(): Boolean = isValid && protocol == 6

    fun getTcpSegment(): TcpSegment? {
        if (!isTcp() || packetLength < payloadOffset + 20) return null
        return TcpSegment(raw, payloadOffset, packetLength).takeIf { it.isValid }
    }

    private fun ipToString(data: ByteArray, offset: Int): String {
        return "${data[offset].toInt() and 0xFF}." +
                "${data[offset + 1].toInt() and 0xFF}." +
                "${data[offset + 2].toInt() and 0xFF}." +
                "${data[offset + 3].toInt() and 0xFF}"
    }
}

class UdpSegment(private val raw: ByteArray, private val offset: Int, private val totalLength: Int) {

    val sourcePort: Int = if (offset + 2 <= raw.size) {
        ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
    } else 0

    val destPort: Int = if (offset + 4 <= raw.size) {
        ((raw[offset + 2].toInt() and 0xFF) shl 8) or (raw[offset + 3].toInt() and 0xFF)
    } else 0

    val length: Int = if (offset + 6 <= raw.size) {
        ((raw[offset + 4].toInt() and 0xFF) shl 8) or (raw[offset + 5].toInt() and 0xFF)
    } else 0

    val payloadStart: Int = offset + 8
    val payloadLength: Int = (totalLength - payloadStart).coerceAtLeast(0)

    val isValid: Boolean = offset >= 0 &&
            offset + 8 <= totalLength &&
            totalLength <= raw.size

    fun getPayload(): ByteArray {
        if (!isValid || payloadLength <= 0) return ByteArray(0)
        return raw.copyOfRange(payloadStart, payloadStart + payloadLength)
    }
}

class TcpSegment(private val raw: ByteArray, private val offset: Int, private val totalLength: Int) {

    val sourcePort: Int = if (offset + 2 <= raw.size) {
        ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
    } else 0

    val destPort: Int = if (offset + 4 <= raw.size) {
        ((raw[offset + 2].toInt() and 0xFF) shl 8) or (raw[offset + 3].toInt() and 0xFF)
    } else 0

    val dataOffset: Int = if (offset + 13 <= raw.size) {
        ((raw[offset + 12].toInt() shr 4) and 0x0F) * 4
    } else 20

    val seqNum: Long = readUInt32(offset + 4)

    val flags: Int = if (offset + 14 <= raw.size) raw[offset + 13].toInt() and 0xFF else 0
    val isSyn: Boolean = (flags and 0x02) != 0
    val isAck: Boolean = (flags and 0x10) != 0
    val isFin: Boolean = (flags and 0x01) != 0
    val isRst: Boolean = (flags and 0x04) != 0
    val isPsh: Boolean = (flags and 0x08) != 0

    val payloadStart: Int = offset + dataOffset
    val payloadLength: Int = (totalLength - payloadStart).coerceAtLeast(0)

    val isValid: Boolean = offset >= 0 &&
            offset + 20 <= totalLength &&
            dataOffset in 20..(totalLength - offset) &&
            totalLength <= raw.size

    fun hasPayload(): Boolean = isValid && payloadLength > 0

    fun getPayload(): ByteArray {
        if (!hasPayload()) return ByteArray(0)
        return raw.copyOfRange(payloadStart, payloadStart + payloadLength)
    }

    fun isTlsClientHello(): Boolean {
        if (!isValid || payloadLength < 6) return false
        return raw[payloadStart].toInt() and 0xFF == 0x16 &&
                raw[payloadStart + 5].toInt() and 0xFF == 0x01
    }

    private fun readUInt32(pos: Int): Long {
        if (pos < 0 || pos + 4 > raw.size) return 0
        return ((raw[pos].toLong() and 0xFF) shl 24) or
                ((raw[pos + 1].toLong() and 0xFF) shl 16) or
                ((raw[pos + 2].toLong() and 0xFF) shl 8) or
                (raw[pos + 3].toLong() and 0xFF)
    }
}
