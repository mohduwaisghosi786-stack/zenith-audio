package com.zenith.audio.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
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
                Log.w(TAG, "Force Stop requested! Dismissing notification and terminating process.")
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(AudioReceiverService.NOTIFICATION_ID)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not cancel notification: ${e.message}")
                }

                try {
                    service?.forceShutdown()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not shutdown service cleanly: ${e.message}")
                }

                // Immediate hard kill so the service and app stop completely
                Process.killProcess(Process.myPid())
                exitProcess(0)
            }
        }
    }
}
