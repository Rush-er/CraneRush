package it.bitwire.craterush

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import it.bitwire.craterush.game.Engine.Sfx
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Monophonic square-wave beeper, like a phone buzzer. */
class Beeper {
    private val rate = 22050
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var alive = true
    @Volatile var muted = false

    private val sounds: Map<Sfx, ShortArray> = mapOf(
        Sfx.START to synth(784 to 60, 1046 to 90),
        Sfx.JUMP to synth(660 to 25, 880 to 30),
        Sfx.PUSH to synth(196 to 30),
        Sfx.LAND to synth(110 to 18),
        Sfx.CLEAR to synth(523 to 60, 659 to 60, 784 to 60, 1046 to 140),
        Sfx.LEVEL to synth(784 to 50, 988 to 50, 1175 to 50, 1568 to 160),
        Sfx.DEATH to synth(440 to 100, 330 to 100, 247 to 100, 165 to 260),
        Sfx.MENU to synth(988 to 15),
        Sfx.SMASH to synth(147 to 25, 1175 to 20, 784 to 30),
        Sfx.BOSS to synth(440 to 90, 220 to 90, 440 to 90, 220 to 90, 440 to 90, 220 to 200),
        Sfx.HIT to synth(330 to 40, 660 to 40, 330 to 60),
        Sfx.BOSS_DOWN to synth(523 to 70, 392 to 70, 523 to 70, 659 to 70, 784 to 70, 1046 to 260),
        Sfx.BOOM to synth(98 to 40, 73 to 40, 55 to 90),
        Sfx.WIN to synth(523 to 100, 659 to 100, 784 to 100, 1046 to 200, 784 to 100, 1046 to 400),
    )

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(
            max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 20 * 2)
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    private val worker = Thread({
        track.play()
        while (alive) {
            val buf = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            track.write(buf, 0, buf.size)
        }
        track.stop()
        track.release()
    }, "beeper").apply { isDaemon = true; start() }

    fun play(s: Sfx) {
        if (muted) return
        // Like a single buzzer: a new sound replaces whatever is queued.
        if (s in priority) queue.clear()
        if (queue.size < 3) sounds[s]?.let { queue.offer(it) }
    }

    private val priority = setOf(Sfx.CLEAR, Sfx.DEATH, Sfx.LEVEL, Sfx.BOSS, Sfx.BOSS_DOWN, Sfx.WIN)

    fun release() { alive = false }

    private fun synth(vararg notes: Pair<Int, Int>): ShortArray {
        val total = notes.sumOf { it.second * rate / 1000 }
        val out = ShortArray(total)
        var i = 0
        val amp = (Short.MAX_VALUE * 0.16f).toInt()
        for ((freq, ms) in notes) {
            val n = ms * rate / 1000
            val period = rate.toFloat() / freq
            for (k in 0 until n) {
                val fade = if (k > n - 40) (n - k) / 40f else 1f   // de-click
                out[i++] = (if ((k % period) < period / 2) amp else -amp).times(fade).toInt().toShort()
            }
        }
        return out
    }
}
