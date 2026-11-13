package com.example.answeringmachine.ui

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.answeringmachine.audio.GreetingPlayer
import com.example.answeringmachine.audio.PlaybackState
import com.example.answeringmachine.audio.VoicemailPlaybackController
import com.example.answeringmachine.audio.VoicemailRecorder
import com.example.answeringmachine.service.AnsweringEngineState
import com.example.answeringmachine.service.LiveCallStatus
import com.example.answeringmachine.storage.AnsweringSettings
import com.example.answeringmachine.storage.SettingsRepository
import com.example.answeringmachine.storage.VoicemailItem
import com.example.answeringmachine.storage.VoicemailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SystemReadiness(
    val isDefaultDialer: Boolean = false,
    val hasRecordAudio: Boolean = false,
    val hasPhoneState: Boolean = false,
    val hasAnswerCalls: Boolean = false,
    val hasCallLog: Boolean = false,
    val hasNotifications: Boolean = false
) {
    val hasCorePermissions: Boolean
        get() = hasRecordAudio && hasPhoneState && hasAnswerCalls

    val isFullyReady: Boolean
        get() = isDefaultDialer && hasCorePermissions
}

data class GreetingPreviewState(
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L
)

data class GreetingStudioState(
    val isRecording: Boolean = false,
    val amplitude: Float = 0f,
    val elapsedMillis: Long = 0L,
    val preview: GreetingPreviewState = GreetingPreviewState()
)

data class AnsweringUiState(
    val settings: AnsweringSettings = AnsweringSettings(),
    val voicemails: List<VoicemailItem> = emptyList(),
    val hasCustomGreeting: Boolean = false,
    val playback: PlaybackState = PlaybackState(),
    val liveCallStatus: LiveCallStatus = LiveCallStatus(),
    val readiness: SystemReadiness = SystemReadiness(),
    val studio: GreetingStudioState = GreetingStudioState(),
    val statusBannerMessage: String? = null
)

class AnsweringViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepository = SettingsRepository.getInstance(appContext)
    private val voicemailRepository = VoicemailRepository.getInstance(appContext)
    private val playbackController = VoicemailPlaybackController()
    private val greetingPlayer = GreetingPlayer(appContext)
    private val greetingRecorder = VoicemailRecorder(appContext)

    private val _readiness = MutableStateFlow(computeReadiness(appContext))
    private val _previewState = MutableStateFlow(GreetingPreviewState())
    private val _bannerMessage = MutableStateFlow<String?>(null)

    private val studioStateFlow = combine(
        greetingRecorder.isRecording,
        greetingRecorder.amplitude,
        greetingRecorder.elapsedMillis,
        _previewState
    ) { isRec, amp, elapsed, preview ->
        GreetingStudioState(
            isRecording = isRec,
            amplitude = amp,
            elapsedMillis = elapsed,
            preview = preview
        )
    }

    private val baseDataFlow = combine(
        settingsRepository.settingsFlow,
        voicemailRepository.voicemails,
        voicemailRepository.hasCustomGreeting,
        playbackController.state,
        AnsweringEngineState.status
    ) { settings, voicemails, hasCustom, playback, liveStatus ->
        Triple(settings, voicemails, hasCustom) to (playback to liveStatus)
    }

    val uiState: StateFlow<AnsweringUiState> = combine(
        baseDataFlow,
        _readiness,
        studioStateFlow,
        _bannerMessage
    ) { base, readiness, studio, banner ->
        val (settings, voicemails, hasCustom) = base.first
        val (playback, liveStatus) = base.second
        AnsweringUiState(
            settings = settings,
            voicemails = voicemails,
            hasCustomGreeting = hasCustom,
            playback = playback,
            liveCallStatus = liveStatus,
            readiness = readiness,
            studio = studio,
            statusBannerMessage = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AnsweringUiState(readiness = computeReadiness(appContext))
    )

    fun refreshSystemStatus() {
        _readiness.value = computeReadiness(appContext)
        viewModelScope.launch {
            voicemailRepository.refresh()
        }
    }

    fun clearBannerMessage() {
        _bannerMessage.value = null
    }

    fun setAnsweringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEnabled(enabled)
        }
    }

    fun setRingDelaySeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setRingDelaySeconds(seconds)
        }
    }

    fun setMaxRecordingSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxRecordingSeconds(seconds)
        }
    }

    fun setUseCustomGreeting(useCustom: Boolean) {
        viewModelScope.launch {
            if (useCustom && !voicemailRepository.hasCustomGreeting.value) {
                _bannerMessage.value = "Record a custom greeting first before selecting it."
                return@launch
            }
            settingsRepository.setUseCustomGreeting(useCustom)
        }
    }

    fun toggleVoicemailPlayback(item: VoicemailItem) {
        if (_previewState.value.isPlaying) {
            stopGreetingPreview()
        }
        playbackController.togglePlayback(
            item = item,
            onStarted = {
                viewModelScope.launch {
                    voicemailRepository.markAsHeard(item.id)
                }
            },
            onError = { message ->
                _bannerMessage.value = message
            }
        )
    }

    fun seekVoicemail(fraction: Float) {
        playbackController.seekTo(fraction)
    }

    fun deleteVoicemail(item: VoicemailItem) {
        playbackController.stopIfMatching(item.id)
        viewModelScope.launch {
            voicemailRepository.deleteVoicemail(item.id)
        }
    }

    fun buildShareIntent(item: VoicemailItem): Intent? {
        val uri = voicemailRepository.getShareableUri(item) ?: return null
        val mime = if (item.filePath.endsWith(".mp3", ignoreCase = true)) {
            "audio/mpeg"
        } else {
            "audio/mp4"
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Voicemail from ${item.callerNumber}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun toggleGreetingPreview() {
        if (_previewState.value.isPlaying) {
            stopGreetingPreview()
            return
        }
        playbackController.stop()
        val settings = uiState.value.settings
        val customFile = voicemailRepository.getCustomGreetingFile()
        val useCustom = settings.useCustomGreeting && customFile.exists()

        _previewState.value = GreetingPreviewState(isPlaying = true)
        greetingPlayer.playGreeting(
            useCustom = useCustom,
            customFile = customFile,
            inCallMode = false,
            onProgress = { progress, posMs, durMs ->
                _previewState.value = GreetingPreviewState(
                    isPlaying = true,
                    progress = progress,
                    positionMillis = posMs,
                    durationMillis = durMs
                )
            },
            onCompleted = {
                _previewState.value = GreetingPreviewState()
            },
            onError = { message ->
                _previewState.value = GreetingPreviewState()
                _bannerMessage.value = message
            }
        )
    }

    fun stopGreetingPreview() {
        greetingPlayer.stop()
        _previewState.value = GreetingPreviewState()
    }

    fun playTestBeep() {
        greetingPlayer.playBeep(inCallMode = false)
    }

    fun startRecordingCustomGreeting() {
        _readiness.value = computeReadiness(appContext)
        if (!_readiness.value.hasRecordAudio) {
            _bannerMessage.value = "Microphone permission is required to record a greeting."
            return
        }
        stopGreetingPreview()
        playbackController.stop()

        val target = voicemailRepository.getCustomGreetingWriteTarget()
        val started = greetingRecorder.startRecording(
            outputFile = target,
            audioSource = MediaRecorder.AudioSource.MIC
        )
        if (!started) {
            _bannerMessage.value = greetingRecorder.lastError.value
                ?: "Unable to start microphone MP3 recording."
        }
    }

    fun stopRecordingCustomGreeting() {
        val duration = greetingRecorder.stopRecording()
        val recorderError = greetingRecorder.lastError.value
        voicemailRepository.refreshCustomGreetingStatus()
        viewModelScope.launch {
            if (recorderError != null) {
                voicemailRepository.deleteCustomGreeting()
                _bannerMessage.value = recorderError
            } else if (duration >= 400L && voicemailRepository.hasCustomGreeting.value) {
                settingsRepository.setUseCustomGreeting(true)
                _bannerMessage.value = "Custom MP3 greeting saved and selected."
            } else {
                voicemailRepository.deleteCustomGreeting()
                _bannerMessage.value = "Recording was too short. Hold or tap longer to record."
            }
        }
    }

    fun importCustomGreeting(uri: Uri) {
        stopGreetingPreview()
        playbackController.stop()
        viewModelScope.launch {
            val imported = voicemailRepository.importCustomGreetingFromUri(uri)
            if (imported) {
                settingsRepository.setUseCustomGreeting(true)
                _bannerMessage.value = "Custom MP3 greeting imported and selected."
            } else {
                _bannerMessage.value = "Could not import that audio file. Please pick a valid MP3."
            }
        }
    }

    fun deleteCustomGreeting() {
        if (greetingRecorder.isRecording.value) {
            greetingRecorder.stopRecording()
        }
        stopGreetingPreview()
        voicemailRepository.deleteCustomGreeting()
        viewModelScope.launch {
            settingsRepository.setUseCustomGreeting(false)
            _bannerMessage.value = "Reverted to built-in studio greeting."
        }
    }

    fun createDefaultDialerIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = appContext.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            } else {
                null
            }
        } else {
            @Suppress("DEPRECATION")
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    appContext.packageName
                )
            }
        }
    }

    fun getRequiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun computeReadiness(context: Context): SystemReadiness {
        val isDialer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom?.defaultDialerPackage == context.packageName
        }

        val hasMic = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        val hasPhone = hasPermission(context, Manifest.permission.READ_PHONE_STATE)
        val hasCallLog = hasPermission(context, Manifest.permission.READ_CALL_LOG)
        val hasAnswer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
        } else {
            true
        }
        val hasNotifs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        return SystemReadiness(
            isDefaultDialer = isDialer,
            hasRecordAudio = hasMic,
            hasPhoneState = hasPhone,
            hasAnswerCalls = hasAnswer,
            hasCallLog = hasCallLog,
            hasNotifications = hasNotifs
        )
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onCleared() {
        playbackController.stop()
        greetingPlayer.stop()
        if (greetingRecorder.isRecording.value) {
            greetingRecorder.stopRecording()
        }
        super.onCleared()
    }
}
