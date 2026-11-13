package com.example.answeringmachine.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.answeringmachine.storage.VoicemailItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val activeVoicemailId: String? = null,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L
) {
    val progress: Float
        get() = if (durationMillis > 0L) {
            (positionMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

class VoicemailPlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun togglePlayback(item: VoicemailItem, onStarted: () -> Unit = {}) {
        val current = _state.value
        if (current.activeVoicemailId == item.id && mediaPlayer != null) {
            val player = mediaPlayer ?: return
            if (player.isPlaying) {
                player.pause()
                progressJob?.cancel()
                _state.value = current.copy(
                    isPlaying = false,
                    positionMillis = player.currentPosition.toLong()
                )
            } else {
                player.start()
                startProgressLoop(item.id, player)
                _state.value = current.copy(isPlaying = true)
            }
            return
        }

        stop()
        val file = File(item.filePath)
        if (!file.exists()) return

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { mp ->
                val dur = mp.duration.toLong().coerceAtLeast(item.durationMillis)
                mp.start()
                _state.value = PlaybackState(
                    activeVoicemailId = item.id,
                    isPlaying = true,
                    positionMillis = 0L,
                    durationMillis = dur
                )
                startProgressLoop(item.id, mp)
                onStarted()
            }
            player.setOnCompletionListener {
                progressJob?.cancel()
                val dur = _state.value.durationMillis
                _state.value = PlaybackState(
                    activeVoicemailId = null,
                    isPlaying = false,
                    positionMillis = 0L,
                    durationMillis = dur
                )
                releasePlayer()
            }
            player.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stop()
        }
    }

    fun seekTo(fraction: Float) {
        val player = mediaPlayer ?: return
        val current = _state.value
        if (current.durationMillis <= 0L) return
        val targetMs = (fraction.coerceIn(0f, 1f) * current.durationMillis).toInt()
        runCatching {
            player.seekTo(targetMs)
            _state.value = current.copy(positionMillis = targetMs.toLong())
        }
    }

    fun stopIfMatching(voicemailId: String) {
        if (_state.value.activeVoicemailId == voicemailId) {
            stop()
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        releasePlayer()
        _state.value = PlaybackState()
    }

    private fun startProgressLoop(id: String, player: MediaPlayer) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && mediaPlayer === player && player.isPlaying) {
                val dur = player.duration.toLong().coerceAtLeast(1L)
                val pos = player.currentPosition.toLong().coerceAtMost(dur)
                _state.value = PlaybackState(
                    activeVoicemailId = id,
                    isPlaying = true,
                    positionMillis = pos,
                    durationMillis = dur
                )
                delay(80L)
            }
        }
    }

    private fun releasePlayer() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }
}
