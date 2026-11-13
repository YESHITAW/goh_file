package com.example.answeringmachine.audio

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib

/**
 * Pure-Java LAME MP3 encoder wired without desktop javax.sound / java.beans classes
 * so it runs cleanly on all Android devices and ABIs.
 */
class Mp3Encoder(
    sampleRate: Int,
    channels: Int = 1,
    bitrateKbps: Int = 96,
    quality: Int = 5
) {
    private val lame = Lame()
    private val gf: LameGlobalFlags
    private var isClosed = false

    init {
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val flags = lame.lame_init()
        flags.in_samplerate = sampleRate
        flags.out_samplerate = sampleRate
        flags.num_channels = channels
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        flags.brate = bitrateKbps
        flags.quality = quality
        flags.VBR = VbrMode.vbr_off
        flags.bWriteVbrTag = false
        flags.write_id3tag_automatic = false

        val initStatus = lame.lame_init_params(flags)
        require(initStatus >= 0) { "Failed to initialize MP3 encoder (code=$initStatus)" }
        gf = flags
    }

    fun encodePcm16(pcm: ShortArray, sampleCount: Int): ByteArray {
        if (isClosed || sampleCount <= 0) return ByteArray(0)
        // Jump3r's lame_encode_buffer_int multiplies int samples by 2^-16,
        // so 16-bit PCM must be shifted left by 16 bits for full dynamic range.
        val intLeft = IntArray(sampleCount) { idx -> pcm[idx].toInt() shl 16 }
        val maxMp3Size = (1.25 * sampleCount + 7200).toInt()
        val mp3Buffer = ByteArray(maxMp3Size)
        val encodedBytes = lame.lame_encode_buffer_int(
            gf,
            intLeft,
            intLeft,
            sampleCount,
            mp3Buffer,
            0,
            mp3Buffer.size
        )
        return if (encodedBytes > 0) {
            mp3Buffer.copyOf(encodedBytes)
        } else {
            ByteArray(0)
        }
    }

    fun flush(): ByteArray {
        if (isClosed) return ByteArray(0)
        val mp3Buffer = ByteArray(8192)
        val flushed = lame.lame_encode_flush(gf, mp3Buffer, 0, mp3Buffer.size)
        return if (flushed > 0) {
            mp3Buffer.copyOf(flushed)
        } else {
            ByteArray(0)
        }
    }

    fun close() {
        if (!isClosed) {
            isClosed = true
            runCatching { lame.lame_close(gf) }
        }
    }
}
