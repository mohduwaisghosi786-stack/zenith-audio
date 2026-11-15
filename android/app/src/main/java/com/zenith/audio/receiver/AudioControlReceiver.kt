package com.zenith.audio.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.zenith.audio.MainActivity
import com.zenith.audio.service.AudioReceiverService
import kotlin.system.exitProcess

class AudioControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AudioControlReceiver"
        const val ACTION_TOGGLE_MIC = "com.zenith.audio.ACTION_TOGGLE_MIC"
        const val ACTION_TOGGLE_CONNECT = "com.zenith.audio.ACTION_TOGGLE_CONNECT"
        const val ACTION_FORCE_STOP = "com.zenith.audio.ACTION_FORCE_STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Notification action received: $action")

        val service = AudioReceiverService.instance

        when (action) {
            ACTION_TOGGLE_MIC -> {
                if (service != null) {
                    val isCurrentlyRecording = service.isMicRecording?.value == true
                    service.toggleMic(!isCurrentlyRecording)
                    service.refreshNotification()
                }
            }
            ACTION_TOGGLE_CONNECT -> {
                service?.toggleStreamingFromNotification()
            }
            ACTION_FORCE_STOP -> {
                Log.w(TAG, "Force Stop requested! Terminating all services, activities, and process.")
                // 1. Collapse status bar / notification shade
                try {
                    @Suppress("DEPRECATION")
                    context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Exception) {}

                // 2. Stop service cleanly & remove foreground notification
                try {
                    service?.forceShutdown()
                } catch (_: Exception) {}

                // 3. Explicitly cancel notification
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(AudioReceiverService.NOTIFICATION_ID)
                } catch (_: Exception) {}

                // 4. Finish all activities if MainActivity is open
                try {
                    MainActivity.instance?.finishAffinity()
                } catch (_: Exception) {}

                // 5. Allow Binder call to reach system_server before killing process
                Thread {
                    try {
                        Thread.sleep(150)
                    } catch (_: Exception) {}
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }.start()
            }
        }
    }
}
