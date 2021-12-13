@file:Suppress("unused")

package com.gitee.xuankaicat.communicate

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.nio.charset.Charset
import java.util.*
import kotlin.concurrent.thread

class MQTT : MQTTCommunicate {
    private var client: MqttClient? = null
    private var _qos = 2
    override var qos: MqttQuality
        get() = MqttQuality.values()[_qos]
        set(value) {
            _qos = value.ordinal
        }

    private val retained = false
    override var options: MqttConnectOptions? = null

    override var username: String = ""
    override var password: String = ""
    override var clientId: String = ""

    override var publishTopic: String
        get() = inMessageTopic
        set(value) { inMessageTopic = value }

    override var responseTopic: String
        get() = outMessageTopic
        set(value) { outMessageTopic = value }

    override var inMessageTopic: String = ""
    override var outMessageTopic: String = ""

    override var serverPort = 9000
    override var address: String = ""

    private val serverURI
        get() = "tcp://${address}:${serverPort}"

    override var inCharset: Charset = Charsets.UTF_8
    override var outCharset: Charset = Charsets.UTF_8

    private var isReceiving = false
    private var onReceive: OnReceiveFunc = {false}

    override fun send(message: String) {
        thread {
            try {
                //参数分别为：主题、消息的字节数组、服务质量、是否在服务器保留断开连接后的最后一条消息
                client?.publish(outMessageTopic, message.toByteArray(outCharset), _qos, retained)
                Log.v("MQTT", "发送消息 {uri: '${serverURI}', topic: '${outMessageTopic}', message: '${message}'}")
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }
    }

    override fun startReceive(onReceive: OnReceiveFunc): Boolean {
        if(client == null) return false
        if(isReceiving) return false
        isReceiving = true
        this.onReceive = onReceive
        client?.subscribe(inMessageTopic, _qos)//订阅主题，参数：主题、服务质量
        return true
    }

    override fun stopReceive() {
        if(!isReceiving) return
        this.onReceive = {false}
        isReceiving = false
        client?.unsubscribe(inMessageTopic)
    }

    override fun open(onOpenCallback: OnOpenCallback) {
        var success = false
        thread {
            val tmpDir = System.getProperty("java.io.tmpdir")
            val dataStore = MqttDefaultFilePersistence(tmpDir)

            if(clientId == "") clientId = UUID.randomUUID().toString()
            client = MqttClient(serverURI, clientId, dataStore)
            client?.setCallback(mqttCallback)

            var doConnect = true
            if(options == null) options = MqttConnectOptions()
            else {
                options!!.apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 20
                    userName = this@MQTT.username
                    password = this@MQTT.password.toCharArray()
                }
            }
            val message = "{\"terminal_uid\":\"$clientId\"}"

            do {
                try {
                    options!!.apply {
                        try {
                            setWill(inMessageTopic, message.toByteArray(), _qos, retained)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            doConnect = false
                        }
                    }

                    if(doConnect) {
                        doClientConnection()
                        success = true
                        onOpenCallback.success(this)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    if(!success) success = onOpenCallback.failure(this)
                }
            } while (!success)
        }
    }

    override fun close() {
        client?.disconnect()
    }

    private fun doClientConnection() {
        if(client?.isConnected == false) {
            var success = true
            var times = 1
            do {
                try {
                    client?.connect(options)
                } catch (e: Exception) {
                    Log.e("MQTT", "第${times}次连接失败 {uri: '${serverURI}', username: '${username}', password: '${password}'}")
                    e.printStackTrace()
                    success = false
                    times++
                    Thread.sleep(1000)
                }
            } while (!success && times <= 5)
            if(success) {
                Log.v("MQTT", "连接成功于第${times}次 {uri: '${serverURI}', username: '${username}', password: '${password}'}")
            }
        }
    }

    //订阅主题的回调
    private val mqttCallback: MqttCallback = object : MqttCallback {
        override fun messageArrived(topic: String, message: MqttMessage) {
            //收到消息 String(message.payload)
            if(!this@MQTT.onReceive.invoke(String(message.payload, inCharset))) {
                stopReceive()
            }
        }

        override fun deliveryComplete(arg0: IMqttDeliveryToken) {}
        override fun connectionLost(arg0: Throwable) {
            doClientConnection() //连接断开，重连
        }
    }
}