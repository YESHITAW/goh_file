package com.example

import com.example.answeringmachine.audio.Mp3Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun mp3Encoder_producesValidMpegFrames() {
        val sampleRate = 44100
        val encoder = Mp3Encoder(sampleRate = sampleRate, channels = 1, bitrateKbps = 96)
        val samples = ShortArray(sampleRate / 2) { i ->
            (sin(2.0 * PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
        }

        val encoded = encoder.encodePcm16(samples, samples.size)
        val flushed = encoder.flush()
        encoder.close()

        val allBytes = encoded + flushed
        assertTrue("Expected non-empty MP3 byte output", allBytes.size > 1000)

        // Verify MPEG audio frame sync header (0xFF followed by 0xE0 mask)
        val hasSyncWord = (0 until allBytes.size - 1).any { idx ->
            (allBytes[idx].toInt() and 0xFF) == 0xFF &&
                (allBytes[idx + 1].toInt() and 0xE0) == 0xE0
        }
        assertTrue("Expected valid MPEG audio frame sync header", hasSyncWord)
    }
}
