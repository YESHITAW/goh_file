package com.example.answeringmachine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallPhase {
    IDLE,
    RINGING_COUNTDOWN,
    PLAYING_GREETING,
    RECORDING_VOICEMAIL
}

data class LiveCallStatus(
    val phase: CallPhase = CallPhase.IDLE,
    val callerNumber: String = "",
    val secondsCounter: Int = 0,
    val lastCompletedNote: String? = null
)

object AnsweringEngineState {
    private val _status = MutableStateFlow(LiveCallStatus())
    val status: StateFlow<LiveCallStatus> = _status.asStateFlow()

    fun update(status: LiveCallStatus) {
        _status.value = status
    }

    fun resetToIdle(completedNote: String? = null) {
        _status.value = LiveCallStatus(
            phase = CallPhase.IDLE,
            callerNumber = "",
            secondsCounter = 0,
            lastCompletedNote = completedNote ?: _status.value.lastCompletedNote
        )
    }
}
