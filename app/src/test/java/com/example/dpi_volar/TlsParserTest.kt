package com.example.dpi_volar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TlsParserTest {

    @Test
    fun encuentraElSniDeUnClientHelloValido() {
        val host = "example.com"
        val (hello, expectedOffset) = buildClientHello(host)

        val result = TlsParser.findSniHostname(hello)

        assertEquals(expectedOffset to host.length, result)
    }

    @Test
    fun devuelveNullSiNoEsUnClientHello() {
        assertNull(TlsParser.findSniHostname(ByteArray(0)))
        assertNull(TlsParser.findSniHostname(byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x00)))
        assertNull(TlsParser.findSniHostname(ByteArray(64)))
    }

    /** Devuelve el ClientHello y el offset donde empieza el nombre del SNI. */
    private fun buildClientHello(host: String): Pair<ByteArray, Int> {
        val name = host.toByteArray(Charsets.US_ASCII)
        val serverNameListLen = 1 + 2 + name.size        // tipo + longitud + nombre
        val sniExtensionLen = 2 + serverNameListLen      // longitud de la lista + lista
        val extensionBlockLen = 4 + sniExtensionLen      // tipo + longitud + datos
        val handshakeBodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extensionBlockLen
        val recordLen = 4 + handshakeBodyLen

        val data = ByteArray(5 + recordLen)
        data[0] = 0x16
        data[1] = 0x03
        data[2] = 0x03
        data[3] = ((recordLen shr 8) and 0xFF).toByte()
        data[4] = (recordLen and 0xFF).toByte()

        data[5] = 0x01
        data[6] = ((handshakeBodyLen shr 16) and 0xFF).toByte()
        data[7] = ((handshakeBodyLen shr 8) and 0xFF).toByte()
        data[8] = (handshakeBodyLen and 0xFF).toByte()

        var p = 9
        // legacy_version
        data[p++] = 0x03
        data[p++] = 0x03
        // random
        p += 32
        // session_id (vacío)
        data[p++] = 0
        // cipher_suites (uno)
        data[p++] = 0
        data[p++] = 2
        data[p++] = 0x13
        data[p++] = 0x01
        // compression_methods (uno)
        data[p++] = 1
        data[p++] = 0
        // extensions length
        data[p++] = ((extensionBlockLen shr 8) and 0xFF).toByte()
        data[p++] = (extensionBlockLen and 0xFF).toByte()
        // extensión server_name (tipo 0)
        data[p++] = 0
        data[p++] = 0
        data[p++] = ((sniExtensionLen shr 8) and 0xFF).toByte()
        data[p++] = (sniExtensionLen and 0xFF).toByte()
        // server_name_list
        data[p++] = ((serverNameListLen shr 8) and 0xFF).toByte()
        data[p++] = (serverNameListLen and 0xFF).toByte()
        data[p++] = 0 // host_name
        data[p++] = ((name.size shr 8) and 0xFF).toByte()
        data[p++] = (name.size and 0xFF).toByte()

        val offset = p
        name.copyInto(data, p)
        p += name.size

        assertEquals(data.size, p)
        return data to offset
    }
}
