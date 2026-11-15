package com.zenith.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.zenith.audio.MainActivity
import com.zenith.audio.R
import com.zenith.audio.model.ConnectionState
import com.zenith.audio.model.StreamMetrics
import com.zenith.audio.network.UdpAudioReceiver
import com.zenith.audio.receiver.AudioControlReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AudioReceiverService : Service() {
    companion object {
        var instance: AudioReceiverService? = null
            private set

        const val ACTION_START = "com.zenith.audio.START"
        const val ACTION_STOP = "com.zenith.audio.STOP"

        const val EXTRA_SERVER_IP = "EXTRA_SERVER_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_BITRATE = "EXTRA_BITRATE"

        private const val CHANNEL_ID = "zenith_audio_stream_channel_v4"
        const val NOTIFICATION_ID = 1001
    }

    private val binder = LocalBinder()
    private var receiver: UdpAudioReceiver? = null
    private var micRecorder: com.zenith.audio.mic.MicAudioRecorder? = null
    private lateinit var serverDiscovery: com.zenith.audio.network.ServerDiscovery
    private var callDuckingManager: com.zenith.audio.telephony.CallDuckingManager? = null
    private var failoverManager: com.zenith.audio.network.NetworkFailoverManager? = null
    private var aoaDacManager: com.zenith.audio.usb.AoaDacManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var metricsCollectorJob: Job? = null

    private var lastServerIp: String = "192.168.1.9"
    private var lastPort: Int = com.zenith.audio.protocol.ZapProtocol.DEFAULT_PORT
    private var lastBitrate: Int = 320

    inner class LocalBinder : Binder() {
        fun getService(): AudioReceiverService = this@AudioReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this

        receiver = UdpAudioReceiver(this)
        micRecorder = com.zenith.audio.mic.MicAudioRecorder(this) { data ->
            receiver?.sendRaw(data)
        }
        serverDiscovery = com.zenith.audio.network.ServerDiscovery(this)
        serverDiscovery.startDiscovery()

        callDuckingManager = com.zenith.audio.telephony.CallDuckingManager(
            context = this,
            onSetVolume = { duckVol ->
                receiver?.setMasterVolume(duckVol)
            },
            onDuckingStateChanged = { isDucking ->
                receiver?.setCallDuckingActive(isDucking)
            }
        ).apply { start() }

        val prefs = getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        lastServerIp = prefs.getString("server_ip", "192.168.1.9") ?: "192.168.1.9"

        failoverManager = com.zenith.audio.network.NetworkFailoverManager(this) { newTransport, targetIp ->
            receiver?.switchEndpoint(targetIp, transportName = newTransport)
        }.apply { start(lastServerIp) }

        serviceScope.launch {
            serverDiscovery.latestDiscoveredServer.collect { server ->
                if (server != null) {
                    lastServerIp = server.ip
                    failoverManager?.updateWifiServerIp(server.ip)
                }
            }
        }

        serviceScope.launch {
            micRecorder?.isRecording?.collect {
                refreshNotification()
            }
        }

        aoaDacManager = com.zenith.audio.usb.AoaDacManager(this) { isUsb, targetIp ->
            if (isUsb) {
                receiver?.setAoaDacActive(true)
                receiver?.switchEndpoint(targetIp.ifEmpty { "10.81.101.129" }, transportName = "USB AOA 2.0 DAC")
            } else {
                receiver?.setAoaDacActive(false)
                receiver?.switchEndpoint(targetIp.ifEmpty { lastServerIp }, transportName = "Wi-Fi")
            }
        }.apply { start() }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenithAudio::StreamWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_SERVER_IP) 
                    ?: getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).getString("server_ip", lastServerIp) 
                    ?: lastServerIp
                val port = intent.getIntExtra(EXTRA_PORT, com.zenith.audio.protocol.ZapProtocol.DEFAULT_PORT)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 320)
                lastServerIp = ip
                lastPort = port
                lastBitrate = bitrate

                startInForeground()
                startStreaming(ip, port, bitrate)
            }
            ACTION_STOP -> {
                forceShutdown()
            }
        }
        return START_NOT_STICKY
    }

    fun getMetricsFlow(): StateFlow<StreamMetrics>? = receiver?.metrics
    val discoveredServers: StateFlow<List<com.zenith.audio.protocol.ZapProtocol.DiscoveredServer>>
        get() = serverDiscovery.discoveredServers

    val isMicRecording: StateFlow<Boolean>? get() = micRecorder?.isRecording
    val micLevel: StateFlow<Float>? get() = micRecorder?.micLevel

    val isCallDuckingEnabled: StateFlow<Boolean>? get() = callDuckingManager?.isDuckingEnabled
    val isCallActive: StateFlow<Boolean>? get() = callDuckingManager?.isCallActive

    val isAoaEnabled: StateFlow<Boolean>? get() = aoaDacManager?.isAoaEnabled
    val isUsbConnected: StateFlow<Boolean>? get() = aoaDacManager?.isUsbConnected

    val activeDetectedIp: StateFlow<String>? get() = failoverManager?.activeDetectedIp
    val activeTransport: StateFlow<String>? get() = failoverManager?.activeTransport
    val dspEngine: com.zenith.audio.dsp.DspEqualizerEngine? get() = receiver?.dspEngine

    fun setBitrate(bitrateKbps: Int) {
        receiver?.setBitrate(bitrateKbps)
    }

    fun setAutoBitrate(enabled: Boolean) {
        receiver?.setAutoBitrate(enabled)
    }

    fun setGamingMode(enabled: Boolean) {
        receiver?.setGamingMode(enabled)
    }

    fun triggerFastDiscovery() {
        serverDiscovery.triggerFastScan()
    }

    fun setMasterVolume(volumePercent: Int) {
        callDuckingManager?.updateCurrentVolume(volumePercent)
        receiver?.setMasterVolume(volumePercent)
    }

    fun setCallDuckingEnabled(enabled: Boolean) {
        callDuckingManager?.setAutoDuckingEnabled(enabled)
    }

    fun setAoaModeEnabled(enabled: Boolean, fallbackWifiIp: String) {
        aoaDacManager?.setAoaModeEnabled(enabled, fallbackWifiIp)
    }

    fun toggleMic(enable: Boolean): Boolean {
        val mic = micRecorder ?: return false
        return if (enable) {
            mic.start()
        } else {
            mic.stop()
            true
        }
    }

    fun toggleStreamingFromNotification() {
        val currentState = receiver?.metrics?.value?.connectionState ?: ConnectionState.DISCONNECTED
        val isStreamingActive = currentState == ConnectionState.CONNECTED || 
                                currentState == ConnectionState.CONNECTING || 
                                currentState == ConnectionState.RECONNECTING

        if (isStreamingActive) {
            stopStreaming()
        } else {
            val ip = lastServerIp.ifEmpty {
                getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).getString("server_ip", "192.168.1.9") ?: "192.168.1.9"
            }
            startStreaming(ip, lastPort, lastBitrate)
        }
        refreshNotification()
    }

    fun forceShutdown() {
        stopStreaming()
        micRecorder?.stop()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun refreshNotification() {
        try {
            val notification = buildNotification()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun startStreaming(ip: String, port: Int, bitrate: Int) {
        lastServerIp = ip
        lastPort = port
        lastBitrate = bitrate

        failoverManager?.updateWifiServerIp(ip)
        failoverManager?.setStreamingActive(true)
        receiver?.start(ip, port, bitrate)

        metricsCollectorJob?.cancel()
        metricsCollectorJob = serviceScope.launch {
            var lastState: ConnectionState? = null

            receiver?.metrics?.collect { metrics ->
                val stateChanged = metrics.connectionState != lastState
                if (stateChanged) {
                    lastState = metrics.connectionState
                    refreshNotification()
                }
            }
        }
        refreshNotification()
    }

    private fun stopStreaming() {
        metricsCollectorJob?.cancel()
        failoverManager?.setStreamingActive(false)
        receiver?.stop()
    }

    override fun onDestroy() {
        instance = null
        stopStreaming()
        micRecorder?.stop()
        serverDiscovery.stopDiscovery()
        callDuckingManager?.stop()
        failoverManager?.stop()
        aoaDacManager?.stop()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                manager.deleteNotificationChannel("zenith_audio_stream_channel")
                manager.deleteNotificationChannel("zenith_audio_stream_channel_v2")
                manager.deleteNotificationChannel("zenith_audio_stream_channel_v3")
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zenith Studio Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tactile quick-toggle controls for Mic, Stream, and Power"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val metrics = receiver?.metrics?.value ?: StreamMetrics()
        val isMicOn = micRecorder?.isRecording?.value == true
        val isConnected = metrics.connectionState == ConnectionState.CONNECTED
        val isConnecting = metrics.connectionState == ConnectionState.CONNECTING || metrics.connectionState == ConnectionState.RECONNECTING
        val isStreamingActive = isConnected || isConnecting

        val statusText = when {
            isConnected && isMicOn -> "🟢 Connected  •  🎤 Mic Active"
            isConnected && !isMicOn -> "🟢 Connected  •  🔇 Mic Muted"
            isConnecting -> "⏳ Connecting to PC..."
            isMicOn -> "⚪ Standby  •  🎤 Mic Active"
            else -> "⚪ Standby  •  🔇 Mic Muted"
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 1. Mic PendingIntent
        val micIntent = Intent(this, AudioControlReceiver::class.java).apply {
            action = AudioControlReceiver.ACTION_TOGGLE_MIC
            setPackage(packageName)
        }
        val micPendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            micIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Connect / Disconnect PendingIntent
        val connectIntent = Intent(this, AudioControlReceiver::class.java).apply {
            action = AudioControlReceiver.ACTION_TOGGLE_CONNECT
            setPackage(packageName)
        }
        val connectPendingIntent = PendingIntent.getBroadcast(
            this,
            102,
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Force Close PendingIntent
        val closeIntent = Intent(this, AudioControlReceiver::class.java).apply {
            action = AudioControlReceiver.ACTION_FORCE_STOP
            setPackage(packageName)
        }
        val closePendingIntent = PendingIntent.getBroadcast(
            this,
            103,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Custom Compact RemoteViews
        val compactView = RemoteViews(packageName, R.layout.layout_notification_compact).apply {
            setTextViewText(R.id.notif_title, "Zenith Studio")
            setTextViewText(R.id.notif_status_text, statusText)

            // Dynamic background and icon for Mic button
            setInt(
                R.id.notif_btn_mic,
                "setBackgroundResource",
                if (isMicOn) R.drawable.bg_notif_btn_mic_on else R.drawable.bg_notif_btn_mic_off
            )
            setImageViewResource(
                R.id.notif_btn_mic,
                if (isMicOn) R.drawable.ic_notif_mic_on else R.drawable.ic_notif_mic_off
            )

            // Dynamic background and icon for Connect button
            setInt(
                R.id.notif_btn_connect,
                "setBackgroundResource",
                if (isStreamingActive) R.drawable.bg_notif_btn_stream_on else R.drawable.bg_notif_btn_stream_off
            )
            setImageViewResource(
                R.id.notif_btn_connect,
                if (isStreamingActive) R.drawable.ic_notif_disconnect else R.drawable.ic_notif_connect
            )

            // Close button
            setInt(R.id.notif_btn_close, "setBackgroundResource", R.drawable.bg_notif_btn_close)
            setImageViewResource(R.id.notif_btn_close, R.drawable.ic_notif_stop)

            setOnClickPendingIntent(R.id.notif_btn_mic, micPendingIntent)
            setOnClickPendingIntent(R.id.notif_btn_connect, connectPendingIntent)
            setOnClickPendingIntent(R.id.notif_btn_close, closePendingIntent)
        }

        // Custom Expanded RemoteViews
        val expandedView = RemoteViews(packageName, R.layout.layout_notification_expanded).apply {
            setTextViewText(R.id.notif_exp_title, "Zenith Studio Master")
            setTextViewText(R.id.notif_exp_status, statusText)

            setTextViewText(
                R.id.notif_exp_badge,
                if (isConnected) "LIVE" else if (isConnecting) "SYNCING" else "STANDBY"
            )
            setTextColor(
                R.id.notif_exp_badge,
                if (isConnected) 0xFF00E676.toInt() else if (isConnecting) 0xFFFFB300.toInt() else 0xFF9E9E9E.toInt()
            )

            // Mic tile
            setInt(
                R.id.notif_exp_ic_mic,
                "setBackgroundResource",
                if (isMicOn) R.drawable.bg_notif_btn_mic_on else R.drawable.bg_notif_btn_mic_off
            )
            setImageViewResource(
                R.id.notif_exp_ic_mic,
                if (isMicOn) R.drawable.ic_notif_mic_on else R.drawable.ic_notif_mic_off
            )
            setTextViewText(
                R.id.notif_exp_txt_mic,
                if (isMicOn) "Mic ON" else "Mic OFF"
            )
            setTextColor(
                R.id.notif_exp_txt_mic,
                if (isMicOn) 0xFF00E676.toInt() else 0xFFFF3D71.toInt()
            )

            // Connect tile
            setInt(
                R.id.notif_exp_ic_connect,
                "setBackgroundResource",
                if (isStreamingActive) R.drawable.bg_notif_btn_stream_on else R.drawable.bg_notif_btn_stream_off
            )
            setImageViewResource(
                R.id.notif_exp_ic_connect,
                if (isStreamingActive) R.drawable.ic_notif_disconnect else R.drawable.ic_notif_connect
            )
            setTextViewText(
                R.id.notif_exp_txt_connect,
                if (isStreamingActive) "Disconnect" else "Connect"
            )
            setTextColor(
                R.id.notif_exp_txt_connect,
                if (isStreamingActive) 0xFFFFB300.toInt() else 0xFF00E5FF.toInt()
            )

            // Close tile
            setInt(R.id.notif_exp_ic_close, "setBackgroundResource", R.drawable.bg_notif_btn_close)
            setImageViewResource(R.id.notif_exp_ic_close, R.drawable.ic_notif_stop)

            // Click listeners on both container tile and button
            setOnClickPendingIntent(R.id.notif_exp_btn_mic, micPendingIntent)
            setOnClickPendingIntent(R.id.notif_exp_ic_mic, micPendingIntent)

            setOnClickPendingIntent(R.id.notif_exp_btn_connect, connectPendingIntent)
            setOnClickPendingIntent(R.id.notif_exp_ic_connect, connectPendingIntent)

            setOnClickPendingIntent(R.id.notif_exp_btn_close, closePendingIntent)
            setOnClickPendingIntent(R.id.notif_exp_ic_close, closePendingIntent)
        }

        val smallIconRes = if (isMicOn) R.drawable.ic_notif_mic_on else R.drawable.ic_launcher_foreground

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setCustomContentView(compactView)
            .setCustomBigContentView(expandedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                if (isMicOn) R.drawable.ic_notif_mic_off else R.drawable.ic_notif_mic_on,
                if (isMicOn) "Mute Mic" else "Unmute Mic",
                micPendingIntent
            )
            .addAction(
                if (isStreamingActive) R.drawable.ic_notif_disconnect else R.drawable.ic_notif_connect,
                if (isStreamingActive) "Disconnect" else "Connect",
                connectPendingIntent
            )
            .addAction(
                R.drawable.ic_notif_stop,
                "Force Close",
                closePendingIntent
            )

        return builder.build()
    }
}
