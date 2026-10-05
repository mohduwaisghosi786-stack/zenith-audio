package com.zenith.audio.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AoaDacManager(
    private val context: Context,
    private val onSwitchTransport: (isUsb: Boolean, targetIp: String) -> Unit
) {
    companion object {
        private const val TAG = "AoaDacManager"
        const val USB_LOCAL_IP = "127.0.0.1"
    }

    private val _isAoaEnabled = MutableStateFlow(false)
    val isAoaEnabled: StateFlow<Boolean> = _isAoaEnabled.asStateFlow()

    private val _isUsbConnected = MutableStateFlow(false)
    val isUsbConnected: StateFlow<Boolean> = _isUsbConnected.asStateFlow()

    private var usbManager: UsbManager? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    Log.i(TAG, "USB Accessory Attached: ${accessory?.description}")
                    _isUsbConnected.value = true
                    if (_isAoaEnabled.value) {
                        onSwitchTransport(true, USB_LOCAL_IP)
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Log.i(TAG, "USB Accessory Detached")
                    _isUsbConnected.value = false
                    if (_isAoaEnabled.value) {
                        onSwitchTransport(false, "")
                    }
                }
            }
        }
    }

    fun start() {
        usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    fun setAoaModeEnabled(enabled: Boolean, fallbackWifiIp: String) {
        _isAoaEnabled.value = enabled
        Log.i(TAG, "AOA 2.0 Hardware USB DAC mode toggled: $enabled")

        if (enabled) {
            // Switch to direct USB zero-latency endpoint
            onSwitchTransport(true, USB_LOCAL_IP)
        } else {
            // Switch back to Wi-Fi
            onSwitchTransport(false, fallbackWifiIp)
        }
    }
}
