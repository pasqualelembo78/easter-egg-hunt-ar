package com.intelligame.easteregghuntar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Suoni sintetizzati via AudioTrack — nessun file audio esterno.
 * Ogni suono e' una melodia generata come PCM 16-bit mono 22050 Hz.
 */
object SoundManager {

    private const val SR = 22050  // sample rate

    // Frequenze note musicali
    private const val C4  = 261.63f;  private const val D4  = 293.66f
    private const val E4  = 329.63f;  private const val F4  = 349.23f
    private const val G4  = 392.00f;  private const val A4  = 440.00f
    private const val B4  = 493.88f;  private const val C5  = 523.25f
    private const val D5  = 587.33f;  private const val E5  = 659.25f
    private const val G5  = 783.99f;  private const val A5  = 880.00f

    var enabled = true

    // ─── API pubblica ────────────────────────────────────────────
    /** Jingle di apertura app */
    fun playIntro()     = play(listOf(C4 to 90, E4 to 90, G4 to 90, C5 to 90, E5 to 300))
    /** Uovo reale catturato */
    fun playEggFound()  = play(listOf(G4 to 80, C5 to 80, E5 to 80, G5 to 350))
    /** Trappola scattata */
    fun playTrap()      = play(listOf(G5 to 120, E5 to 120, C5 to 120, A4 to 120, F4 to 350))
    /** Cassaforte che si apre */
    fun playSafeOpen()  = play(listOf(
        C4 to 70, E4 to 70, G4 to 70, C5 to 70,
        E5 to 70, G5 to 70, A5 to 400
    ))
    /** Chiave inserita */
    fun playKeyInsert() = play(listOf(A4 to 90, C5 to 200))
    /** Vittoria finale */
    fun playVictory()   = play(listOf(
        C4 to 80, E4 to 80, G4 to 80, C5 to 80, G4 to 80,
        E4 to 80, G4 to 80, C5 to 80, E5 to 80, G5 to 80, A5 to 500
    ))
    /** Passa il turno */
    fun playTurnSwitch() = play(listOf(E5 to 100, C5 to 100, E5 to 200))
    /** Lancio cestino */
    fun playThrow()     = play(listOf(A4 to 60, E5 to 120))

    // ─── Motore ──────────────────────────────────────────────────
    private fun play(notes: List<Pair<Float, Int>>) {
        if (!enabled) return
        thread(isDaemon = true) {
            try {
                val buf = buildBuffer(notes)
                val at = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                at.write(buf, 0, buf.size)
                at.play()
                val durationMs = (buf.size.toLong() * 1000L / SR)
                Thread.sleep(durationMs + 80)
                at.stop(); at.release()
            } catch (_: Exception) {}
        }
    }

    private fun buildBuffer(notes: List<Pair<Float, Int>>): ShortArray {
        val totalSamples = notes.sumOf { (_, ms) -> SR * ms / 1000 }
        val buf = ShortArray(totalSamples)
        var offset = 0
        for ((freq, durMs) in notes) {
            val n = SR * durMs / 1000
            val attack  = (n * 0.06).toInt().coerceAtLeast(1)
            val release = (n * 0.30).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val angle = 2.0 * PI * i.toDouble() * freq / SR
                val env = when {
                    i < attack       -> i.toDouble() / attack
                    i > n - release  -> (n - i).toDouble() / release
                    else             -> 1.0
                }.coerceIn(0.0, 1.0)
                // Blend sine + a bit of square for more character
                val sine   = sin(angle)
                val square = if (sin(angle) >= 0) 0.3 else -0.3
                buf[offset + i] = ((sine * 0.7 + square) * env * 0.72 * Short.MAX_VALUE)
                    .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            offset += n
        }
        return buf
    }
}
