package com.example.answeringmachine.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.answeringmachine.audio.GreetingPlayer
import com.example.answeringmachine.audio.VoicemailRecorder
import com.example.answeringmachine.storage.AnsweringSettings
import com.example.answeringmachine.storage.SettingsRepository
import com.example.answeringmachine.storage.VoicemailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class AnsweringInCallService : InCallService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var voicemailRepository: VoicemailRepository
    private lateinit var greetingPlayer: GreetingPlayer
    private lateinit var voicemailRecorder: VoicemailRecorder
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var activeCall: Call? = null
    private var activeCallCallback: Call.Callback? = null
    private var callFlowJob: Job? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTimestamp: Long = 0L
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var hasCapturedCall = false

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        voicemailRepository = VoicemailRepository.getInstance(applicationContext)
        greetingPlayer = GreetingPlayer(applicationContext)
        voicemailRecorder = VoicemailRecorder(applicationContext)
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            call.details.callDirection == Call.Details.DIRECTION_INCOMING ||
                call.state == Call.STATE_RINGING
        } else {
            call.state == Call.STATE_RINGING
        }

        if (!isIncoming) return

        // Tear down any previous tracked call flow if a new incoming call takes over
        cleanupActiveCall(saveIfRecording = true)

        activeCall = call
        hasCapturedCall = false
        val callerNumber = resolveCallerNumber(call)
        val callerName = call.details.callerDisplayName?.takeIf { it.isNotBlank() }

        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            registerCallback(call, callerNumber, callerName, settings)

            if (settings.isEnabled) {
                startAutoAnswerFlow(call, callerNumber, settings)
            } else {
                postIncomingCallNotification(
                    title = "Incoming call: $callerNumber",
                    text = "CallDeck answering machine is on standby."
                )
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (call === activeCall) {
            cleanupActiveCall(saveIfRecording = true)
        }
    }

    override fun onDestroy() {
        cleanupActiveCall(saveIfRecording = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerCallback(
        call: Call,
        callerNumber: String,
        callerName: String?,
        settings: AnsweringSettings
    ) {
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                when (state) {
                    Call.STATE_ACTIVE -> {
                        if (settings.isEnabled && !hasCapturedCall) {
                            hasCapturedCall = true
                            beginGreetingAndRecording(call, callerNumber, callerName, settings)
                        }
                    }

                    Call.STATE_DISCONNECTED -> {
                        cleanupActiveCall(saveIfRecording = true)
                    }
                }
            }
        }
        activeCallCallback = callback
        call.registerCallback(callback)
    }

    private fun startAutoAnswerFlow(
        call: Call,
        callerNumber: String,
        settings: AnsweringSettings
    ) {
        callFlowJob?.cancel()
        callFlowJob = serviceScope.launch {
            val delaySec = settings.ringDelaySeconds.coerceAtLeast(0)
            for (remaining in delaySec downTo 1) {
                if (!isActive || call.state != Call.STATE_RINGING) return@launch
                AnsweringEngineState.update(
                    LiveCallStatus(
                        phase = CallPhase.RINGING_COUNTDOWN,
                        callerNumber = callerNumber,
                        secondsCounter = remaining
                    )
                )
                postIncomingCallNotification(
                    title = "Answering $callerNumber in ${remaining}s",
                    text = "CallDeck auto-answer countdown active"
                )
                delay(1_000L)
            }
            if (isActive && call.state == Call.STATE_RINGING) {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }
    }

    private fun beginGreetingAndRecording(
        call: Call,
        callerNumber: String,
        callerName: String?,
        settings: AnsweringSettings
    ) {
        callFlowJob?.cancel()
        callFlowJob = serviceScope.launch {
            previousAudioMode = audioManager.mode
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }

            AnsweringEngineState.update(
                LiveCallStatus(
                    phase = CallPhase.PLAYING_GREETING,
                    callerNumber = callerNumber,
                    secondsCounter = 0
                )
            )
            postIncomingCallNotification(
                title = "Playing greeting to $callerNumber",
                text = "Outgoing message in progress"
            )

            val customFile = voicemailRepository.getCustomGreetingFile()
            val useCustom = settings.useCustomGreeting && customFile.exists() && customFile.length() > 0L

            greetingPlayer.playGreeting(
                useCustom = useCustom,
                customFile = customFile,
                inCallMode = true,
                onCompleted = {
                    if (useCustom) {
                        greetingPlayer.playBeep(inCallMode = true) {
                            startCallerRecording(call, callerNumber, callerName, settings)
                        }
                    } else {
                        startCallerRecording(call, callerNumber, callerName, settings)
                    }
                },
                onError = {
                    startCallerRecording(call, callerNumber, callerName, settings)
                }
            )
        }
    }

    private fun startCallerRecording(
        call: Call,
        callerNumber: String,
        callerName: String?,
        settings: AnsweringSettings
    ) {
        if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) {
            cleanupActiveCall(saveIfRecording = false)
            return
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            AnsweringEngineState.resetToIdle("Microphone permission needed to record voicemail")
            call.disconnect()
            return
        }

        val timestamp = System.currentTimeMillis()
        val targetFile = voicemailRepository.createRecordingTargetFile(timestamp)
        val started = voicemailRecorder.startRecording(
            outputFile = targetFile,
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        ) || voicemailRecorder.startRecording(
            outputFile = targetFile,
            audioSource = MediaRecorder.AudioSource.MIC
        )

        if (!started) {
            AnsweringEngineState.resetToIdle("Audio recorder busy during call")
            call.disconnect()
            return
        }

        currentRecordingFile = targetFile
        recordingStartTimestamp = timestamp

        callFlowJob?.cancel()
        callFlowJob = serviceScope.launch {
            val maxSec = settings.maxRecordingSeconds.coerceAtLeast(15)
            for (elapsed in 0..maxSec) {
                if (!isActive || !voicemailRecorder.isRecording.value) break
                AnsweringEngineState.update(
                    LiveCallStatus(
                        phase = CallPhase.RECORDING_VOICEMAIL,
                        callerNumber = callerNumber,
                        secondsCounter = elapsed
                    )
                )
                postIncomingCallNotification(
                    title = "Recording voicemail: $callerNumber",
                    text = "Recording ${elapsed}s / ${maxSec}s"
                )
                if (elapsed == maxSec) {
                    finalizeAndSaveRecording(callerNumber, callerName)
                    call.disconnect()
                    break
                }
                delay(1_000L)
            }
        }
    }

    private fun cleanupActiveCall(saveIfRecording: Boolean) {
        callFlowJob?.cancel()
        callFlowJob = null
        greetingPlayer.stop()

        val call = activeCall
        val callerNumber = call?.let { resolveCallerNumber(it) } ?: "Unknown Caller"
        val callerName = call?.details?.callerDisplayName?.takeIf { it.isNotBlank() }

        if (saveIfRecording && voicemailRecorder.isRecording.value) {
            finalizeAndSaveRecording(callerNumber, callerName)
        } else if (voicemailRecorder.isRecording.value) {
            voicemailRecorder.stopRecording()
            currentRecordingFile?.delete()
            currentRecordingFile = null
        }

        activeCallCallback?.let { cb ->
            runCatching { activeCall?.unregisterCallback(cb) }
        }
        activeCallCallback = null
        activeCall = null
        hasCapturedCall = false

        runCatching { audioManager.mode = previousAudioMode }
        notificationManager.cancel(CALL_NOTIFICATION_ID)
        if (AnsweringEngineState.status.value.phase != CallPhase.IDLE) {
            AnsweringEngineState.resetToIdle()
        }
    }

    private fun finalizeAndSaveRecording(callerNumber: String, callerName: String?) {
        val durationMs = voicemailRecorder.stopRecording()
        val file = currentRecordingFile
        currentRecordingFile = null
        if (file != null && file.exists() && file.length() > 0L) {
            val capturedTimestamp = recordingStartTimestamp
            serviceScope.launch {
                voicemailRepository.registerCompletedRecording(
                    file = file,
                    callerNumber = callerNumber,
                    callerName = callerName,
                    timestampMillis = capturedTimestamp,
                    durationMillis = durationMs
                )
                AnsweringEngineState.resetToIdle("Saved voicemail from $callerNumber")
            }
        } else {
            AnsweringEngineState.resetToIdle()
        }
    }

    private fun resolveCallerNumber(call: Call): String {
        val number = call.details.handle?.schemeSpecificPart
        return if (!number.isNullOrBlank()) number else getString(R.string.unknown_caller)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_calls_desc)
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun postIncomingCallNotification(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(CALL_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "calldeck_calls"
        private const val CALL_NOTIFICATION_ID = 4201
    }
}
