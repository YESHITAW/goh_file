package com.example.answeringmachine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class GreetingPlayer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    fun playGreeting(
        useCustom: Boolean,
        customFile: File?,
        inCallMode: Boolean = false,
        onProgress: (progress: Float, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
        onCompleted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        stop()

        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (inCallMode) AudioAttributes.USAGE_VOICE_COMMUNICATION
                else AudioAttributes.USAGE_MEDIA
            )
            .setContentType(
                if (inCallMode) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_MUSIC
            )
            .build()

        val player = MediaPlayer()
        mediaPlayer = player

        try {
            player.setAudioAttributes(attributes)
            if (useCustom && customFile != null && customFile.exists() && customFile.length() > 0L) {
                player.setDataSource(customFile.absolutePath)
            } else {
                val afd = context.resources.openRawResourceFd(R.raw.greeting)
                afd.use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }

            player.setOnPreparedListener { mp ->
                val totalMs = mp.duration.coerceAtLeast(1).toLong()
                mp.start()
                progressJob?.cancel()
                progressJob = scope.launch {
                    while (isActive && mediaPlayer === mp && mp.isPlaying) {
                        val posMs = mp.currentPosition.toLong()
                        val fraction = (posMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                        onProgress(fraction, posMs, totalMs)
                        delay(50L)
                    }
                }
            }

            player.setOnCompletionListener {
                progressJob?.cancel()
                onProgress(1f, it.duration.toLong(), it.duration.toLong())
                releasePlayer()
                onCompleted()
            }

            player.setOnErrorListener { _, what, extra ->
                progressJob?.cancel()
                releasePlayer()
                onError("Greeting playback failed ($what/$extra)")
                true
            }

            player.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            onError(e.message ?: "Unable to play greeting")
        }
    }

    fun playBeep(inCallMode: Boolean = false, onFinished: () -> Unit = {}) {
        scope.launch {
            try {
                val streamType = if (inCallMode) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
                toneGenerator?.release()
                val tg = ToneGenerator(streamType, 85)
                toneGenerator = tg
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 350)
                delay(400L)
                tg.release()
                if (toneGenerator === tg) {
                    toneGenerator = null
                }
            } catch (_: Exception) {
                delay(250L)
            }
            onFinished()
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        releasePlayer()
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }

    private fun releasePlayer() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }
}
