package com.example.dpi_volar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketBuilderTest {

    private val client = byteArrayOf(10, 0, 0, 2)
    private val server = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    @Test
    fun tcpSynAckHasValidChecksums() {
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = server, dstIp = client,
            srcPort = 80, dstPort = 12345,
            seqNum = 1_000_000L, ackNum = 2_000_001L,
            flags = PacketBuilder.TCP_SYN or PacketBuilder.TCP_ACK
        )

        assertEquals("IP checksum", 0, Checksum.compute(packet, 0, 20))

        val tcpSegment = packet.copyOfRange(20, packet.size)
        assertEquals(
            "TCP checksum",
            0,
            Checksum.tcpChecksum(server, client, tcpSegment, tcpSegment.size)
        )
    }

    @Test
    fun tcpDataPacketHasValidChecksums() {
        val payload = "hola mundo".toByteArray()
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = server, dstIp = client,
            srcPort = 443, dstPort = 54321,
            seqNum = 5L, ackNum = 7L,
            flags = PacketBuilder.TCP_ACK or PacketBuilder.TCP_PSH,
            payload = payload
        )

        assertEquals(packet.size - 20 - 20, payload.size)
        assertEquals(0, Checksum.compute(packet, 0, 20))
        val tcpSegment = packet.copyOfRange(20, packet.size)
        assertEquals(
            0,
            Checksum.tcpChecksum(server, client, tcpSegment, tcpSegment.size)
        )
    }

    @Test
    fun udpPacketHasValidIpChecksum() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet = PacketBuilder.buildUdpPacket(
            srcIp = server, dstIp = client,
            srcPort = 53, dstPort = 5353,
            payload = payload
        )
        assertEquals(0, Checksum.compute(packet, 0, 20))
        assertTrue(packet.size == 20 + 8 + payload.size)
    }
}
