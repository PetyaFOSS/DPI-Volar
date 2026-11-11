package com.example.dpi_volar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DnsCacheTest {

    @Before
    fun limpiarCache() {
        DnsCache.clear()
    }

    @Test
    fun devuelveLaRespuestaYReescribeElIdDeTransaccion() {
        val query = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00)
        val response = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01)

        DnsCache.put(query, response)

        // La misma consulta con otro ID de transacción debe recibir la
        // respuesta cacheada, pero con el ID de la consulta nueva.
        val otraConsulta = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x01, 0x00, 0x00, 0x01, 0x00, 0x00)
        val cached = DnsCache.get(otraConsulta)

        assertNotNull(cached)
        cached!!
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01),
            cached
        )
    }

    @Test
    fun noDevuelveNadaParaConsultasNoCacheadas() {
        assertNull(DnsCache.get(byteArrayOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun lasRespuestasSeCopianAlEntrarYSalir() {
        val query = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val response = byteArrayOf(0x01, 0x02, 0x09, 0x09)

        DnsCache.put(query, response)
        response[3] = 0x00

        val cached = DnsCache.get(query)
        assertNotNull(cached)
        cached!!
        // El byte 3 original no se pierde aunque el arreglo original se modifique.
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x09, 0x09), cached)

        cached[2] = 0x00
        val cached2 = DnsCache.get(query)
        assertNotNull(cached2)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x09, 0x09), cached2)
    }
}
