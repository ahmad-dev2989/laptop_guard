package com.ahmad.laptopguard.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a loud alarm using Android's ALARM stream (bypasses Silent/Vibrate mode)
 * with vibration and synthesized fallback tone if no system alarm sound exists.
 */
class AlarmPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackThread: Thread? = null
    @Volatile
    private var fallbackRunning = false
    private var previousAlarmVolume: Int? = null
    private var isPlaying = false

    fun start(boostVolume: Boolean) {
        if (isPlaying) return
        isPlaying = true

        if (boostVolume) {
            boostAlarmStream()
        }

        startSound()
        startVibration()
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false

        fallbackRunning = false
        fallbackThread?.interrupt()
        fallbackThread = null

        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (_: Exception) {
        }
        mediaPlayer = null

        stopVibration()
        restoreAlarmStream()
    }

    private fun boostAlarmStream() {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val targetVol = (maxVol * 0.90f).toInt().coerceAtLeast(1).coerceAtMost(maxVol)
            if (currentVol < targetVol) {
                previousAlarmVolume = currentVol
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
            }
        } catch (_: SecurityException) {
            // Device policy / DND may restrict volume adjustments without policy permission.
        } catch (_: Exception) {
        }
    }

    private fun restoreAlarmStream() {
        val prev = previousAlarmVolume ?: return
        previousAlarmVolume = null
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
        } catch (_: Exception) {
        }
    }

    private fun startSound() {
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        if (uri != null) {
            try {
                val player = MediaPlayer().apply {
                    setAudioAttributes(alarmAttrs)
                    setDataSource(appContext, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                mediaPlayer = player
                return
            } catch (_: Exception) {
                mediaPlayer?.release()
                mediaPlayer = null
            }
        }

        startSynthesizedSiren(alarmAttrs)
    }

    private fun startSynthesizedSiren(alarmAttrs: AudioAttributes) {
        fallbackRunning = true
        fallbackThread = thread(name = "LaptopGuardAlarmTone") {
            val sampleRate = 22_050
            val chunkSamples = sampleRate / 2 // 0.5s pulse
            val pcm = ShortArray(chunkSamples)
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(alarmAttrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, chunkSamples * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            try {
                track.play()
                var high = true
                while (fallbackRunning && !Thread.currentThread().isInterrupted) {
                    val freq = if (high) 1760.0 else 1175.0
                    high = !high
                    for (i in 0 until chunkSamples) {
                        val env = if (i > chunkSamples - 400) 0.0 else 0.85
                        pcm[i] = (sin(2.0 * PI * freq * i / sampleRate) * Short.MAX_VALUE * env).toInt().toShort()
                    }
                    track.write(pcm, 0, pcm.size)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startVibration() {
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return

        val timings = longArrayOf(0, 450, 220, 450, 220)
        val effect = VibrationEffect.createWaveform(timings, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            vibrator.vibrate(effect, attrs)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }

    private fun stopVibration() {
        try {
            resolveVibrator()?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}

