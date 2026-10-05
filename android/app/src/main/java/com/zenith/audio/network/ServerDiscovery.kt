package com.zenith.audio.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.zenith.audio.protocol.ZapProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class ServerDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "ServerDiscovery"
        private const val DISCOVERY_PORT = ZapProtocol.DEFAULT_PORT
    }

    private val _discoveredServers = MutableStateFlow<List<ZapProtocol.DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<ZapProtocol.DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return

        discoveryJob = scope.launch {
            var socket: DatagramSocket? = null
            var multicastLock: WifiManager.MulticastLock? = null

            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("ZenithDiscoveryLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }

                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1500
                    bind(InetSocketAddress(0)) // Ephemeral port to receive probe responses
                }

                val rxBuffer = ByteArray(1024)
                val rxPacket = DatagramPacket(rxBuffer, rxBuffer.size)
                val probeData = ZapProtocol.buildDiscoveryProbePacket()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val probePacket = DatagramPacket(probeData, probeData.size, broadcastAddr, DISCOVERY_PORT)

                val serverMap = mutableMapOf<String, ZapProtocol.DiscoveredServer>()

                while (isActive) {
                    // Send probe broadcast
                    try {
                        socket.send(probePacket)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to broadcast discovery probe: ${e.message}")
                    }

                    // Listen for beacons/responses
                    val windowStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - windowStart < 2000L && isActive) {
                        try {
                            rxPacket.length = rxBuffer.size
                            socket.receive(rxPacket)

                            val ip = rxPacket.address.hostAddress ?: continue
                            val byteBuffer = ByteBuffer.wrap(rxPacket.data, rxPacket.offset, rxPacket.length)
                            val header = ZapProtocol.parseHeader(byteBuffer)

                            if (header != null && (header.type == ZapProtocol.PKT_DISCOVERY_BEACON || header.type == ZapProtocol.PKT_SERVER_ANNOUNCE)) {
                                val payloadBuffer = ByteBuffer.wrap(
                                    rxPacket.data,
                                    rxPacket.offset + ZapProtocol.HEADER_SIZE,
                                    rxPacket.length - ZapProtocol.HEADER_SIZE
                                )
                                val server = ZapProtocol.parseDiscoveryBeacon(payloadBuffer, ip)
                                if (server != null) {
                                    serverMap[server.ip] = server
                                    _discoveredServers.value = serverMap.values.toList()
                                    Log.i(TAG, "Discovered server: ${server.serverName} at ${server.ip}:${server.port}")
                                }
                            }
                        } catch (_: Exception) {
                            // Socket timeout or packet read timeout
                        }
                    }

                    delay(3000L) // Scan cycle every 3 seconds
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            } finally {
                socket?.close()
                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }
}
