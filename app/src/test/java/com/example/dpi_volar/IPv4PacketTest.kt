package com.example.dpi_volar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IPv4PacketTest {

    private val client = byteArrayOf(10, 0, 0, 2)
    private val server = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    @Test
    fun parseaUnPaqueteTcpConstruidoPorPacketBuilder() {
        val payload = "datos".toByteArray()
        val raw = PacketBuilder.buildTcpPacket(
            srcIp = server, dstIp = client,
            srcPort = 443, dstPort = 54321,
            seqNum = 123456L, ackNum = 654321L,
            flags = PacketBuilder.TCP_ACK or PacketBuilder.TCP_PSH,
            payload = payload
        )

        val packet = IPv4Packet(raw, raw.size)
        assertTrue(packet.isValid)
        assertTrue(packet.isTcp())
        assertFalse(packet.isUdp())
        assertEquals("93.184.216.34", packet.sourceIp)
        assertEquals("10.0.0.2", packet.destIp)

        val tcp = packet.getTcpSegment()
        assertNotNull(tcp)
        tcp!!
        assertTrue(tcp.isValid)
        assertEquals(443, tcp.sourcePort)
        assertEquals(54321, tcp.destPort)
        assertEquals(123456L, tcp.seqNum)
        assertEquals(PacketBuilder.TCP_ACK or PacketBuilder.TCP_PSH, tcp.flags)
        assertEquals(payload.toList(), tcp.getPayload().toList())
        assertNull(packet.getUdpSegment())
    }

    @Test
    fun parseaUnPaqueteUdp() {
        val payload = byteArrayOf(0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        val raw = PacketBuilder.buildUdpPacket(
            srcIp = client, dstIp = server,
            srcPort = 5353, dstPort = 53,
            payload = payload
        )

        val packet = IPv4Packet(raw, raw.size)
        assertTrue(packet.isValid)
        assertTrue(packet.isUdp())
        val udp = packet.getUdpSegment()
        assertNotNull(udp)
        udp!!
        assertTrue(udp.isValid)
        assertEquals(5353, udp.sourcePort)
        assertEquals(53, udp.destPort)
        assertEquals(payload.toList(), udp.getPayload().toList())
        assertNull(packet.getTcpSegment())
    }

    @Test
    fun descartaPaquetesTruncadosSinLanzarExcepciones() {
        val short = IPv4Packet(ByteArray(10), 10)
        assertFalse(short.isValid)
        assertFalse(short.isTcp())
        assertFalse(short.isUdp())
        assertNull(short.getTcpSegment())
        assertNull(short.getUdpSegment())
        assertEquals(4, short.sourceIpBytes.size)

        val raw = PacketBuilder.buildTcpPacket(
            srcIp = server, dstIp = client,
            srcPort = 443, dstPort = 1234,
            seqNum = 1L, ackNum = 2L,
            flags = PacketBuilder.TCP_SYN or PacketBuilder.TCP_ACK,
            payload = ByteArray(100)
        )
        // Solo llegan los primeros 30 bytes: cabecera IP completa, TCP incompleto.
        val truncated = IPv4Packet(raw, 30)
        assertTrue(truncated.isValid)
        assertTrue(truncated.isTcp())
        assertNull(truncated.getTcpSegment())
    }

    @Test
    fun rechazaLongitudesDeCabeceraImposibles() {
        val raw = ByteArray(40)
        raw[0] = 0x45 // IPv4, IHL 5
        raw[2] = 0
        raw[3] = 40
        raw[9] = 6

        val packet = IPv4Packet(raw, 40)
        assertTrue(packet.isValid)
        assertTrue(packet.isTcp())
        assertEquals(20, packet.payloadOffset)

        raw[0] = 0x44 // IHL 4 (menor que el mínimo de 20 bytes)
        val invalid = IPv4Packet(raw, 40)
        assertFalse(invalid.isValid)
        assertFalse(invalid.isTcp())
    }
}
