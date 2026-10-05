package com.zenith.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.zenith.audio.MainActivity
import com.zenith.audio.model.ConnectionState
import com.zenith.audio.model.StreamMetrics
import com.zenith.audio.network.UdpAudioReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AudioReceiverService : Service() {
    companion object {
        const val ACTION_START = "com.zenith.audio.START"
        const val ACTION_STOP = "com.zenith.audio.STOP"
        const val EXTRA_SERVER_IP = "EXTRA_SERVER_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_BITRATE = "EXTRA_BITRATE"

        private const val CHANNEL_ID = "zenith_audio_stream_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val binder = LocalBinder()
    private var receiver: UdpAudioReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var metricsCollectorJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioReceiverService = this@AudioReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        receiver = UdpAudioReceiver(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenithAudio::StreamWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_SERVER_IP) ?: "192.168.1.9"
                val port = intent.getIntExtra(EXTRA_PORT, com.zenith.audio.protocol.ZapProtocol.DEFAULT_PORT)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 320)

                startForeground(NOTIFICATION_ID, buildNotification("Connecting to $ip..."))
                startStreaming(ip, port, bitrate)
            }
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun getMetricsFlow(): StateFlow<StreamMetrics>? = receiver?.metrics

    fun setBitrate(bitrateKbps: Int) {
        receiver?.setBitrate(bitrateKbps)
    }

    private fun startStreaming(ip: String, port: Int, bitrate: Int) {
        receiver?.start(ip, port, bitrate)

        metricsCollectorJob?.cancel()
        metricsCollectorJob = serviceScope.launch {
            var lastNotificationTime = 0L
            var lastState: ConnectionState? = null

            receiver?.metrics?.collect { metrics ->
                val now = System.currentTimeMillis()
                val stateChanged = metrics.connectionState != lastState
                val timePassed = (now - lastNotificationTime) >= 3000L

                if (stateChanged || timePassed) {
                    lastState = metrics.connectionState
                    lastNotificationTime = now

                    val text = when (metrics.connectionState) {
                        ConnectionState.CONNECTED -> "Streaming: ${metrics.bitrateKbps} kbps | Latency: ${metrics.totalEstimatedLatencyMs.toInt()}ms | ${metrics.outputDeviceName}"
                        ConnectionState.CONNECTING -> "Connecting to ${metrics.serverIp}..."
                        ConnectionState.RECONNECTING -> "Reconnecting to ${metrics.serverIp}..."
                        ConnectionState.DISCONNECTED -> "Disconnected"
                    }
                    val notification = buildNotification(text)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun stopStreaming() {
        metricsCollectorJob?.cancel()
        receiver?.stop()
    }

    override fun onDestroy() {
        stopStreaming()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zenith Audio Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low-latency audio streaming from Linux"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zenith Audio Engine")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
