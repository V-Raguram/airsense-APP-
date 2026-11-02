package com.airsense.companion

import com.airsense.companion.network.MqttWire
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MqttWireTest {
    @Test fun `connect sets clean session and optional auth flags`() {
        val clean = MqttWire.connect("phone", "", "")
        assertEquals(4, clean[6].toInt())
        assertEquals(2, clean[7].toInt())
        val auth = MqttWire.connect("phone", "user", "secret")
        assertEquals(194, auth[7].toInt() and 255)
    }
    @Test fun `multi byte remaining length round trips`() {
        val body = ByteArray(20000) { (it % 251).toByte() }
        val bytes = ByteArrayOutputStream()
        MqttWire.send(bytes, 0x30, body)
        val result = MqttWire.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(3, result.type)
        assertArrayEquals(body, result.body)
    }
    @Test fun `publish extracts QoS1 packet id and retained flag`() {
        val packet = MqttWire.Packet(0x33, MqttWire.utf8("esp32/sensors") + byteArrayOf(0x12, 0x34) + "{\"nox\":1.25}".toByteArray())
        val publish = MqttWire.publish(packet)
        assertEquals("esp32/sensors", publish.topic)
        assertEquals(0x1234, publish.packetId)
        assertTrue(publish.retained)
        assertEquals("{\"nox\":1.25}", publish.payload.toString(Charsets.UTF_8))
    }
    @Test(expected = IllegalArgumentException::class) fun `excessive continuation lengths are rejected`() {
        MqttWire.read(ByteArrayInputStream(byteArrayOf(0x30, -1, -1, -1, -1)))
    }
    @Test(expected = IllegalArgumentException::class) fun `oversized packets are rejected before allocation`() {
        MqttWire.read(ByteArrayInputStream(byteArrayOf(0x30, -1, -1, 0x7f)))
    }
    @Test(expected = IllegalArgumentException::class) fun `null bytes cannot enter MQTT strings`() { MqttWire.utf8("topic\u0000suffix") }
    @Test(expected = IllegalArgumentException::class) fun `truncated topic is rejected`() {
        MqttWire.publish(MqttWire.Packet(0x30, byteArrayOf(0, 99, 65)))
    }
    @Test fun `subscriber framing interoperates with a socket broker handshake`() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val broker = executor.submit<Unit> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    assertEquals(1, MqttWire.read(input).type)
                    MqttWire.send(output, 0x20, byteArrayOf(0, 0))
                    val subscription = MqttWire.read(input)
                    assertEquals(8, subscription.type)
                    assertEquals(2, subscription.header and 15)
                    MqttWire.send(output, 0x90, byteArrayOf(0, 1, 0))
                    MqttWire.send(output, 0x30, MqttWire.utf8("esp32/sensors") + "{\"nox\":0.7}".toByteArray())
                    assertEquals(12, MqttWire.read(input).type)
                    MqttWire.send(output, 0xD0)
                }
            }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                MqttWire.send(output, 0x10, MqttWire.connect("test-phone", "", ""))
                assertEquals(2, MqttWire.read(input).type)
                MqttWire.send(output, 0x82, byteArrayOf(0, 1) + MqttWire.utf8("esp32/sensors") + byteArrayOf(0))
                assertEquals(9, MqttWire.read(input).type)
                assertEquals("{\"nox\":0.7}", MqttWire.publish(MqttWire.read(input)).payload.toString(Charsets.UTF_8))
                MqttWire.send(output, 0xC0)
                assertEquals(13, MqttWire.read(input).type)
            }
            broker.get(5, TimeUnit.SECONDS)
        }
        executor.shutdownNow()
    }
}
