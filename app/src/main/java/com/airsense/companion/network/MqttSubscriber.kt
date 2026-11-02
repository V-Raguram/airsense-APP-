package com.airsense.companion.network

import android.os.SystemClock
import com.airsense.companion.data.DeviceSettings
import com.airsense.companion.data.LiveReading
import com.airsense.companion.data.Sensor
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

class MqttSubscriber(private val socketFactory: SocketFactory) : AutoCloseable {
    @Volatile private var socket: Socket? = null
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    /** Blocking call, run on Dispatchers.IO. Closing the socket cancels a blocked read. */
    fun run(settings: DeviceSettings, onConnected: () -> Unit, onReading: (LiveReading) -> Unit, onInvalid: () -> Unit) {
        socketFactory.createSocket().use { current ->
            socket = current
            if (closed.get()) return
            current.connect(InetSocketAddress(settings.host, settings.mqttPort), 8000)
            current.soTimeout = 30_000
            val input = current.getInputStream()
            val output = current.getOutputStream()
            synchronized(current) { MqttWire.send(output, 0x10, MqttWire.connect("airsense-${UUID.randomUUID().toString().take(8)}", settings.mqttUsername, settings.mqttPassword)) }
            val ack = MqttWire.read(input)
            require(ack.type == 2 && ack.body.size == 2 && ack.body[1] == 0.toByte()) {
                "MQTT refused the connection. Check broker access and MQTT credentials."
            }
            synchronized(current) { MqttWire.send(output, 0x82, byteArrayOf(0, 1) + MqttWire.utf8(settings.mqttTopic) + byteArrayOf(0)) }
            var subscribed = false
            while (!closed.get()) {
                val packet = MqttWire.read(input)
                when (packet.type) {
                    9 -> {
                        require(packet.body.size == 3 && packet.body[0] == 0.toByte() && packet.body[1] == 1.toByte() && packet.body[2] == 0.toByte()) {
                            "The MQTT broker rejected the sensor subscription."
                        }
                        subscribed = true
                        ready.set(true)
                        onConnected()
                    }
                    3 -> {
                        val message = MqttWire.publish(packet)
                        message.packetId?.let { synchronized(current) { MqttWire.send(output, 0x40, byteArrayOf((it ushr 8).toByte(), it.toByte())) } }
                        // Retained packets are historical and must not appear fresh.
                        if (subscribed && !message.retained && message.topic == settings.mqttTopic) {
                            runCatching {
                                val json = JSONObject(String(message.payload, Charsets.UTF_8))
                                require(json.length() == 0 || json.has("date") || json.has("time") || Sensor.entries.any { json.has(it.key) }) { "No Airsense fields." }
                                val values = Sensor.entries.associateWith { sensor ->
                                    (json.opt(sensor.key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
                                }
                                onReading(LiveReading(values,
                                    listOf(json.optString("date", ""), json.optString("time", "")).filter { it.isNotBlank() }.joinToString(" "),
                                    SystemClock.elapsedRealtime()))
                            }.onFailure { onInvalid() }
                        }
                    }
                }
                // Keeping outbound traffic below 20 seconds satisfies MQTT keepalive.
                // A separate heartbeat thread also covers a completely idle sensor topic.
            }
        }
    }

    fun ping() {
        val current = socket ?: return
        if (!closed.get() && ready.get() && current.isConnected) synchronized(current) {
            MqttWire.send(current.getOutputStream(), 0xC0)
        }
    }
    override fun close() { closed.set(true); ready.set(false); runCatching { socket?.close() }; socket = null }
}
