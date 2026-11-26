package com.rox.sleeptimer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rox.sleeptimer.MainActivity
import com.rox.sleeptimer.model.TimerStatus
import com.rox.sleeptimer.model.TransitionMode
import com.rox.sleeptimer.model.VolumeScheduleMath
import com.rox.sleeptimer.model.VolumeStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.roundToInt

class VolumeTimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var activeSteps: List<VolumeStep> = emptyList()
    private var activeTotalSeconds: Int = 0
    private var activeMode: TransitionMode = TransitionMode.GRADUAL
    private var activePauseAtEnd: Boolean = true
    private var activeStartVolume: Int = 50

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTimerAndService(finishedNormally = false)
                return START_NOT_STICKY
            }

            ACTION_EXTEND -> {
                val extraSec = intent.getIntExtra(EXTRA_EXTEND_SECONDS, 600)
                extendSchedule(extraSec)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val stepsJson = intent.getStringExtra(EXTRA_STEPS_JSON).orEmpty()
                val startVolumePercent = intent.getIntExtra(EXTRA_START_VOLUME, readCurrentMediaPercent())
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: TransitionMode.GRADUAL.name
                val mode = runCatching { TransitionMode.valueOf(modeName) }.getOrDefault(TransitionMode.GRADUAL)
                val pauseAtEnd = intent.getBooleanExtra(EXTRA_PAUSE_AT_END, true)

                val steps = parseSteps(stepsJson)
                if (steps.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startVolumeSchedule(
                    startVolumePercent = startVolumePercent.coerceIn(0, 100),
                    steps = steps,
                    mode = mode,
                    pauseAtEnd = pauseAtEnd
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun extendSchedule(extraSec: Int) {
        if (!_status.value.isRunning || activeSteps.isEmpty()) return
        val extraMin = (extraSec / 60).coerceAtLeast(1)
        activeSteps = activeSteps.map { it.copy(minute = it.minute + extraMin) }
        activeTotalSeconds += extraSec
        acquireWakeLock(timeoutMillis = (activeTotalSeconds + 120L) * 1000L)
        val current = _status.value
        val updated = current.copy(
            totalSeconds = activeTotalSeconds,
            nextStepMinute = activeSteps.firstOrNull { it.minute * 60 > current.elapsedSeconds }?.minute
        )
        _status.value = updated
        notificationManager.notify(NOTIFICATION_ID, buildNotification(updated))
    }

    private fun startVolumeSchedule(
        startVolumePercent: Int,
        steps: List<VolumeStep>,
        mode: TransitionMode,
        pauseAtEnd: Boolean
    ) {
        timerJob?.cancel()
        releaseWakeLock()

        val sortedSteps = steps.filter { it.minute > 0 }.sortedBy { it.minute }
        val totalSeconds = sortedSteps.last().minute * 60

        activeSteps = sortedSteps
        activeTotalSeconds = totalSeconds
        activeMode = mode
        activePauseAtEnd = pauseAtEnd
        activeStartVolume = startVolumePercent

        acquireWakeLock(timeoutMillis = (totalSeconds + 120L) * 1000L)

        val initialNext = sortedSteps.firstOrNull()
        val initialStatus = TimerStatus(
            isRunning = true,
            startVolumePercent = startVolumePercent,
            currentVolumePercent = startVolumePercent,
            elapsedSeconds = 0,
            totalSeconds = totalSeconds,
            nextStepMinute = initialNext?.minute,
            nextStepVolume = initialNext?.volumePercent
        )
        _status.value = initialStatus

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(initialStatus),
            foregroundType
        )

        val startElapsedRealtime = SystemClock.elapsedRealtime()
        val maxStreamVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var lastAppliedIndex = -1

        // Aplica el volumen de inicio inmediatamente
        lastAppliedIndex = applyMediaVolumePercent(startVolumePercent, maxStreamVolume, lastAppliedIndex)

        timerJob = serviceScope.launch {
            while (isActive) {
                val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtime
                val elapsedSecondsDouble = elapsedMs / 1000.0
                val currentTotal = activeTotalSeconds
                val elapsedSecInt = elapsedSecondsDouble.toInt().coerceAtMost(currentTotal)

                val currentSteps = activeSteps

                val targetPercent = VolumeScheduleMath.computeVolumePercent(
                    elapsedSeconds = elapsedSecondsDouble,
                    startVolumePercent = activeStartVolume,
                    steps = currentSteps,
                    mode = activeMode
                )

                lastAppliedIndex = applyMediaVolumePercent(targetPercent, maxStreamVolume, lastAppliedIndex)

                val nextStep = currentSteps.firstOrNull { it.minute * 60 > elapsedSecInt }
                val updatedStatus = TimerStatus(
                    isRunning = true,
                    startVolumePercent = activeStartVolume,
                    currentVolumePercent = targetPercent,
                    elapsedSeconds = elapsedSecInt,
                    totalSeconds = currentTotal,
                    nextStepMinute = nextStep?.minute,
                    nextStepVolume = nextStep?.volumePercent
                )
                _status.value = updatedStatus
                notificationManager.notify(NOTIFICATION_ID, buildNotification(updatedStatus))

                if (elapsedSecInt >= currentTotal) {
                    if (activePauseAtEnd) {
                        pauseActiveMediaPlayback()
                    }
                    stopTimerAndService(finishedNormally = true)
                    break
                }

                delay(1000L)
            }
        }
    }

    private fun applyMediaVolumePercent(
        targetPercent: Int,
        maxStreamVolume: Int,
        lastAppliedIndex: Int
    ): Int {
        val targetIndex = ((targetPercent.coerceIn(0, 100) / 100.0) * maxStreamVolume)
            .roundToInt()
            .coerceIn(0, maxStreamVolume)

        if (targetIndex != lastAppliedIndex) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
            return targetIndex
        }
        return lastAppliedIndex
    }

    /**
     * Pausa de forma limpia cualquier reproductor activo (iVoox, YouTube, Spotify, etc.)
     * solicitando foco de audio exclusivo y enviando el evento multimedia de pausa.
     */
    private fun pauseActiveMediaPlayback() {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(focusRequest)

            val now = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        }
    }

    private fun stopTimerAndService(finishedNormally: Boolean) {
        timerJob?.cancel()
        timerJob = null
        releaseWakeLock()

        val previous = _status.value
        _status.value = previous.copy(
            isRunning = false,
            lastFinishedAt = if (finishedNormally) System.currentTimeMillis() else previous.lastFinishedAt
        )

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock(timeoutMillis: Long) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SleepTimerRoxV1::VolumeFadeWakeLock"
        )
        lock.setReferenceCounted(false)
        lock.acquire(timeoutMillis)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                runCatching { it.release() }
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Temporizador Sleep Timer Rox",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene activas las bajadas programadas de volumen con la pantalla apagada"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: TimerStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VolumeTimerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remaining = (status.totalSeconds - status.elapsedSeconds).coerceAtLeast(0)
        val remainingText = VolumeScheduleMath.formatDuration(remaining)
        val contentText = buildString {
            append("Volumen: ").append(status.currentVolumePercent).append("%")
            append(" · Restante: ").append(remainingText)
            if (status.nextStepMinute != null && status.nextStepVolume != null) {
                append(" · Min ").append(status.nextStepMinute).append(" → ").append(status.nextStepVolume).append("%")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Sleep Timer Rox V2 activo")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .build()
    }

    private fun readCurrentMediaPercent(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return ((current.toDouble() / max.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun parseSteps(rawJson: String): List<VolumeStep> {
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        VolumeStep(
                            id = obj.optLong("id", (i + 1).toLong()),
                            minute = obj.optInt("minute", 10).coerceAtLeast(1),
                            volumePercent = obj.optInt("volume", 0).coerceIn(0, 100)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timerJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.rox.sleeptimer.ACTION_START"
        const val ACTION_STOP = "com.rox.sleeptimer.ACTION_STOP"
        const val ACTION_EXTEND = "com.rox.sleeptimer.ACTION_EXTEND"

        const val EXTRA_STEPS_JSON = "extra_steps_json"
        const val EXTRA_START_VOLUME = "extra_start_volume"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PAUSE_AT_END = "extra_pause_at_end"
        const val EXTRA_EXTEND_SECONDS = "extra_extend_seconds"

        private const val CHANNEL_ID = "sleep_timer_rox_channel"
        private const val NOTIFICATION_ID = 1042

        private val _status = MutableStateFlow(TimerStatus())
        val status: StateFlow<TimerStatus> = _status.asStateFlow()

        fun encodeStepsForIntent(steps: List<VolumeStep>): String {
            val array = JSONArray()
            steps.forEach { step ->
                array.put(
                    org.json.JSONObject().apply {
                        put("id", step.id)
                        put("minute", step.minute)
                        put("volume", step.volumePercent)
                    }
                )
            }
            return array.toString()
        }
    }
}
