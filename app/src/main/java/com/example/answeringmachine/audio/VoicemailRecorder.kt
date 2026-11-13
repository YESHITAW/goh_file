package com.example.answeringmachine.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
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

class VoicemailRecorder(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaRecorder: MediaRecorder? = null
    private var meterJob: Job? = null
    private var startTimeMillis: Long = 0L
    private var currentFile: File? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    fun startRecording(
        outputFile: File,
        audioSource: Int = MediaRecorder.AudioSource.MIC
    ): Boolean {
        if (_isRecording.value) {
            stopRecording()
        }
        outputFile.parentFile?.mkdirs()

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            recorder.apply {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            currentFile = outputFile
            startTimeMillis = System.currentTimeMillis()
            _isRecording.value = true
            _elapsedMillis.value = 0L
            _amplitude.value = 0f

            meterJob?.cancel()
            meterJob = scope.launch {
                while (isActive && _isRecording.value) {
                    val rawAmp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = (rawAmp / 22000f).coerceIn(0f, 1f)
                    _amplitude.value = normalized
                    _elapsedMillis.value = System.currentTimeMillis() - startTimeMillis
                    delay(60L)
                }
            }
            true
        } catch (_: Exception) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            runCatching { outputFile.delete() }
            mediaRecorder = null
            currentFile = null
            _isRecording.value = false
            _amplitude.value = 0f
            _elapsedMillis.value = 0L
            false
        }
    }

    fun stopRecording(): Long {
        meterJob?.cancel()
        meterJob = null

        val duration = if (_isRecording.value && startTimeMillis > 0L) {
            (System.currentTimeMillis() - startTimeMillis).coerceAtLeast(0L)
        } else {
            0L
        }

        val recorder = mediaRecorder
        mediaRecorder = null
        _isRecording.value = false
        _amplitude.value = 0f

        if (recorder != null) {
            val stopSucceeded = runCatching {
                recorder.stop()
                true
            }.getOrDefault(false)
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            if (!stopSucceeded) {
                currentFile?.delete()
                currentFile = null
                return 0L
            }
        }
        currentFile = null
        return duration
    }
}
