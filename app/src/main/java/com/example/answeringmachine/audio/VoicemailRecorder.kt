package com.example.answeringmachine.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Records live microphone or call audio via AudioRecord and encodes directly to .mp3
 * using Mp3Encoder. Automatically negotiates supported sample rates and audio sources.
 */
class VoicemailRecorder(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private val keepRecording = AtomicBoolean(false)

    private var startTimeMillis: Long = 0L
    private var currentFile: File? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording(
        outputFile: File,
        audioSource: Int = MediaRecorder.AudioSource.MIC
    ): Boolean {
        if (_isRecording.value) {
            stopRecording()
        }
        _lastError.value = null
        outputFile.parentFile?.mkdirs()

        val initialized = createInitializedAudioRecord(audioSource)
        if (initialized == null) {
            _lastError.value = "Microphone hardware could not be initialized."
            return false
        }

        val (audioRecord, sampleRate, bufferBytes) = initialized
        val encoder = try {
            Mp3Encoder(sampleRate = sampleRate, channels = 1, bitrateKbps = 96, quality = 5)
        } catch (e: Exception) {
            runCatching { audioRecord.release() }
            _lastError.value = e.message ?: "Failed to initialize MP3 encoder."
            return false
        }

        return try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { audioRecord.release() }
                encoder.close()
                _lastError.value = "AudioRecord failed to enter recording state."
                return false
            }

            currentFile = outputFile
            startTimeMillis = System.currentTimeMillis()
            keepRecording.set(true)
            _isRecording.value = true
            _elapsedMillis.value = 0L
            _amplitude.value = 0f

            recordingJob = scope.launch {
                val readSizeShorts = (bufferBytes / 2).coerceAtLeast(1024)
                val pcmBuffer = ShortArray(readSizeShorts)
                var mp3Out: BufferedOutputStream? = null

                try {
                    mp3Out = BufferedOutputStream(FileOutputStream(outputFile))
                    while (keepRecording.get()) {
                        val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                        if (read > 0) {
                            var peak = 0
                            for (i in 0 until read) {
                                peak = max(peak, abs(pcmBuffer[i].toInt()))
                            }
                            _amplitude.value = (peak / 18000f).coerceIn(0f, 1f)
                            _elapsedMillis.value = System.currentTimeMillis() - startTimeMillis

                            val mp3Bytes = encoder.encodePcm16(pcmBuffer, read)
                            if (mp3Bytes.isNotEmpty()) {
                                mp3Out.write(mp3Bytes)
                            }
                        } else if (read < 0) {
                            break
                        }
                    }

                    val tailBytes = encoder.flush()
                    if (tailBytes.isNotEmpty()) {
                        mp3Out.write(tailBytes)
                    }
                    mp3Out.flush()
                } catch (e: Exception) {
                    _lastError.value = e.message ?: "Error while writing MP3 recording."
                } finally {
                    runCatching { mp3Out?.close() }
                    runCatching {
                        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            audioRecord.stop()
                        }
                    }
                    runCatching { audioRecord.release() }
                    encoder.close()
                    _isRecording.value = false
                    _amplitude.value = 0f
                }
            }
            true
        } catch (e: Exception) {
            keepRecording.set(false)
            runCatching { audioRecord.release() }
            encoder.close()
            runCatching { outputFile.delete() }
            currentFile = null
            _isRecording.value = false
            _amplitude.value = 0f
            _elapsedMillis.value = 0L
            _lastError.value = e.message ?: "Unable to start MP3 recording."
            false
        }
    }

    fun stopRecording(): Long {
        val wasRecording = keepRecording.getAndSet(false) || _isRecording.value
        val job = recordingJob
        recordingJob = null

        if (job != null) {
            runBlocking {
                withTimeoutOrNull(2500L) {
                    job.join()
                }
            }
        }

        val duration = if (wasRecording && startTimeMillis > 0L) {
            (System.currentTimeMillis() - startTimeMillis).coerceAtLeast(0L)
        } else {
            0L
        }

        _isRecording.value = false
        _amplitude.value = 0f
        currentFile = null
        return duration
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(preferredSource: Int): Triple<AudioRecord, Int, Int>? {
        val sources = buildList {
            add(preferredSource)
            if (preferredSource != MediaRecorder.AudioSource.MIC) {
                add(MediaRecorder.AudioSource.MIC)
            }
            add(MediaRecorder.AudioSource.DEFAULT)
        }.distinct()

        val sampleRates = intArrayOf(44100, 22050, 16000, 8000)

        for (source in sources) {
            for (rate in sampleRates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue

                val bufferSize = max(minBuf * 2, 4096)
                val recorder = runCatching {
                    AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                }.getOrNull() ?: continue

                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return Triple(recorder, rate, bufferSize)
                } else {
                    runCatching { recorder.release() }
                }
            }
        }
        return null
    }
}
