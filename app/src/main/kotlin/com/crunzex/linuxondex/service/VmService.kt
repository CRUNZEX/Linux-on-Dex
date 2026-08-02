package com.crunzex.linuxondex.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.crunzex.linuxondex.LinuxOnDexApp
import com.crunzex.linuxondex.R
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.ui.MainActivity
import com.crunzex.linuxondex.vm.VmState

/**
 * Foreground service that owns the running VM: keeps the process alive in
 * the background, holds a partial wake lock (a paused CPU would freeze the
 * guest kernel), and reflects VM state in its notification.
 */
class VmService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateObserverStarted = false
    private var recoveryJob: Job? = null
    private val prootRecoveryPolicy = ProotRecoveryPolicy()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.info(SCOPE, "received ${intent?.action ?: "null action"} (startId=$startId)")
        when (intent?.action) {
            ACTION_START_VM -> handleStart()
            ACTION_STOP_VM -> handleStop(intent.getStringExtra(EXTRA_STOP_ORIGIN))
            else -> AppLog.warn(SCOPE, "unknown action: ${intent?.action}")
        }
        // If Android reclaims the app process under desktop load, replay the
        // command that owned this foreground runtime. A fresh process then
        // cleans any orphaned guest children and starts the selected session
        // again instead of leaving DeX with a silently dead desktop.
        return START_REDELIVER_INTENT
    }

    private fun handleStart() {
        promoteToForeground(getString(R.string.notification_vm_running))
        acquireWakeLock()
        observeVmStateOnce()

        val controller = (application as LinuxOnDexApp).container.vmController
        serviceScope.launch {
            try {
                controller.startPrimaryVm()
            } catch (error: Exception) {
                // Usually the state observer sees Failed and winds down; this
                // covers failures that never produced a state transition.
                AppLog.error(SCOPE, "VM start failed in service", error)
                val state = controller.vmState.value
                if (!state.isBusy && !state.isRunning) windDown()
            }
        }
    }

    private fun handleStop(origin: String?) {
        AppLog.info(SCOPE, "stop requested from ${origin ?: "unknown origin"}")
        val controller = (application as LinuxOnDexApp).container.vmController
        serviceScope.launch {
            try {
                controller.stopVm()
            } catch (error: Exception) {
                AppLog.error(SCOPE, "VM stop failed in service", error)
                controller.forceStopVm()
            }
        }
    }

    private fun observeVmStateOnce() {
        if (stateObserverStarted) return
        stateObserverStarted = true
        val controller = (application as LinuxOnDexApp).container.vmController
        serviceScope.launch {
            // The flow replays the current (usually Idle/Stopped) state the
            // moment we subscribe; winding down on it would cancel the very
            // start job this service was created for. Only terminal states
            // seen *after* a live state may stop the service.
            var vmWasLive = false
            controller.vmState.collect { state ->
                when (state) {
                    is VmState.Preparing -> {
                        vmWasLive = true
                        updateNotification(state.stepDescription)
                    }
                    is VmState.Starting -> {
                        vmWasLive = true
                        updateNotification("Starting virtual machine…")
                    }
                    is VmState.Running -> {
                        vmWasLive = true
                        updateNotification(getString(R.string.notification_vm_running))
                    }
                    is VmState.Stopping -> updateNotification("Shutting down…")
                    is VmState.Failed -> {
                        if (!vmWasLive) return@collect
                        val recoveryDelayMillis = prootRecoveryPolicy.nextDelayMillis(
                            controller.activeEngineKind.value,
                        )
                        if (recoveryDelayMillis == null) {
                            windDown()
                        } else {
                            scheduleProotRecovery(controller, recoveryDelayMillis)
                        }
                    }
                    is VmState.Stopped, VmState.Idle -> {
                        if (vmWasLive) windDown()
                    }
                }
            }
        }
    }

    /** Restarts only PRoot, and only a bounded number of times. */
    private fun scheduleProotRecovery(
        controller: com.crunzex.linuxondex.vm.VmController,
        recoveryDelayMillis: Long,
    ) {
        if (recoveryJob?.isActive == true) return
        AppLog.warn(
            SCOPE,
            "PRoot session ended unexpectedly; restarting in ${recoveryDelayMillis}ms",
        )
        updateNotification("Recovering Linux desktop…")
        recoveryJob = serviceScope.launch {
            delay(recoveryDelayMillis)
            if (controller.vmState.value !is VmState.Failed) return@launch
            try {
                controller.startPrimaryVm()
            } catch (error: Exception) {
                // VmController publishes Failed with the precise reason. Its
                // state observer schedules the next bounded attempt or winds
                // this service down after the recovery budget is exhausted.
                AppLog.error(SCOPE, "automatic PRoot recovery failed", error)
            }
        }
    }

    private fun windDown() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(text: String) {
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VmService::class.java)
                .setAction(ACTION_STOP_VM)
                .putExtra(EXTRA_STOP_ORIGIN, STOP_ORIGIN_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.notification_vm_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_vm),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire() }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SCOPE = "VmService"
        private const val CHANNEL_ID = "vm-runtime"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "LinuxOnDex:vm"
        private const val ACTION_START_VM = "com.crunzex.linuxondex.action.START_VM"
        private const val ACTION_STOP_VM = "com.crunzex.linuxondex.action.STOP_VM"
        private const val EXTRA_STOP_ORIGIN = "stop_origin"
        private const val STOP_ORIGIN_NOTIFICATION = "notification"
        private const val STOP_ORIGIN_APP_UI = "app UI"

        fun requestStart(context: Context) {
            val intent = Intent(context, VmService::class.java).setAction(ACTION_START_VM)
            context.startForegroundService(intent)
        }

        fun requestStop(context: Context) {
            val intent = Intent(context, VmService::class.java)
                .setAction(ACTION_STOP_VM)
                .putExtra(EXTRA_STOP_ORIGIN, STOP_ORIGIN_APP_UI)
            context.startService(intent)
        }
    }
}
