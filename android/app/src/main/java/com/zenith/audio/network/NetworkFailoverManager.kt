package com.zenith.audio.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkFailoverManager(
    private val context: Context,
    private val onFailoverTriggered: (newTransport: String, targetIp: String) -> Unit
) {
    companion object {
        private const val TAG = "FailoverManager"
    }

    private val _isWifiAvailable = MutableStateFlow(false)
    val isWifiAvailable: StateFlow<Boolean> = _isWifiAvailable.asStateFlow()

    private val _isUsbAvailable = MutableStateFlow(false)
    val isUsbAvailable: StateFlow<Boolean> = _isUsbAvailable.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var cachedWifiIp: String = "192.168.1.9"
    private var activeTransport: String = "Wi-Fi"

    fun start(initialWifiIp: String) {
        cachedWifiIp = initialWifiIp
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cm = connectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val hasUsbEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)

                _isWifiAvailable.value = hasWifi
                _isUsbAvailable.value = hasUsbEthernet

                // Automatic Seamless Failover logic:
                if (hasUsbEthernet && activeTransport != "USB") {
                    val usbIp = com.zenith.audio.usb.UsbIpResolver.resolveUsbHostIp()
                    Log.i(TAG, "High-speed USB network detected -> Hot-switching to USB 0.5ms tunnel ($usbIp)")
                    activeTransport = "USB"
                    onFailoverTriggered("USB 0.5ms Direct", usbIp)
                } else if (!hasUsbEthernet && hasWifi && activeTransport != "Wi-Fi") {
                    Log.i(TAG, "USB disconnected -> Zero-drop hot-failover back to Wi-Fi ($cachedWifiIp)")
                    activeTransport = "Wi-Fi"
                    onFailoverTriggered("Wi-Fi", cachedWifiIp)
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network connection lost on interface. Checking fallback...")
                if (activeTransport == "USB" && _isWifiAvailable.value) {
                    activeTransport = "Wi-Fi"
                    onFailoverTriggered("Wi-Fi", cachedWifiIp)
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            Log.i(TAG, "Network failover monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    fun stop() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    fun updateWifiServerIp(ip: String) {
        if (ip.isNotBlank() && ip != "127.0.0.1") {
            cachedWifiIp = ip
        }
    }

    fun manualSwitch(transport: String, targetIp: String) {
        activeTransport = transport
        onFailoverTriggered(transport, targetIp)
    }
}
