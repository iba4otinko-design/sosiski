package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Real-time procedural coastal soundscape & tactile SFX synthesizer.
 * Generates stereo ocean surf waves, gentle tropical marimba/kalimba melodies,
 * and interactive sand/water droplet sound effects with zero external audio assets.
 */
class BeachSoundEngine {

    private val sampleRate = 24000
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamJob: Job? = null

    @Volatile private var surfActive: Boolean = false
    @Volatile private var surfVolume: Float = 0.55f
    @Volatile private var musicActive: Boolean = false
    @Volatile private var musicVolume: Float = 0.45f
    @Volatile private var sfxActive: Boolean = true
    @Volatile private var appInForeground: Boolean = true

    // Triggered one-shot SFX voices
    @Volatile private var dropletPhase: Double = -1.0
    @Volatile private var sandBrushFramesLeft: Int = 0
    @Volatile private var shellChimePhase: Double = -1.0

    // Pleasant F-Major9 / Lydian coastal marimba scale frequencies (Hz)
    private val coastalScale = doubleArrayOf(
        261.63, // C4
        293.66, // D4
        349.23, // F4
        392.00, // G4
        440.00, // A4
        523.25, // C5
        587.33, // D5
        659.25, // E5
        698.46, // F5
        880.00  // A5
    )

    private val chordProgressions = arrayOf(
        intArrayOf(2, 4, 5, 7), // Fmaj7
        intArrayOf(0, 3, 5, 7), // Cadd9
        intArrayOf(1, 4, 6, 8), // Dm7
        intArrayOf(3, 4, 6, 9)  // G6/9
    )

    fun updateSettings(
        surfEnabled: Boolean,
        surfVol: Float,
        musicEnabled: Boolean,
        musicVol: Float,
        sfxEnabled: Boolean
    ) {
        surfActive = surfEnabled
        surfVolume = surfVol.coerceIn(0f, 1f)
        musicActive = musicEnabled
        musicVolume = musicVol.coerceIn(0f, 1f)
        sfxActive = sfxEnabled
        syncPlaybackState()
    }

    fun setForeground(inForeground: Boolean) {
        appInForeground = inForeground
        syncPlaybackState()
    }

    fun playWaterDroplet() {
        if (!sfxActive || !appInForeground) return
        ensureLoopForOneShot()
        dropletPhase = 0.0
    }

    fun playSandBrush() {
        if (!sfxActive || !appInForeground) return
        ensureLoopForOneShot()
        sandBrushFramesLeft = sampleRate / 9 // ~110ms soft sand swish
    }

    fun playShellChime() {
        if (!sfxActive || !appInForeground) return
        ensureLoopForOneShot()
        shellChimePhase = 0.0
    }

    private fun ensureLoopForOneShot() {
        if (streamJob?.isActive != true) {
            startAudioLoop()
        }
    }

    private fun syncPlaybackState() {
        val shouldRun = appInForeground && (surfActive || musicActive || dropletPhase >= 0.0 || shellChimePhase >= 0.0 || sandBrushFramesLeft > 0)
        if (shouldRun && streamJob?.isActive != true) {
            startAudioLoop()
        } else if (!appInForeground) {
            streamJob?.cancel()
            streamJob = null
        }
    }

    private fun startAudioLoop() {
        streamJob?.cancel()
        streamJob = engineScope.launch {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@launch

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return@launch
            }

            try {
                track.play()
                val framesPerChunk = 1024
                val pcmBuffer = ShortArray(framesPerChunk * 2)

                var sampleIndex = 0L
                var brownLeft = 0.0
                var brownRight = 0.0
                var lpLeft = 0.0
                var lpRight = 0.0

                // Gentle marimba voice pool (up to 4 overlapping notes)
                val noteFreqs = DoubleArray(4)
                val noteAges = DoubleArray(4) { 99.0 }
                val notePans = DoubleArray(4) { 0.5 }
                var nextVoice = 0
                val framesPerBeat = (sampleRate * 0.52).toLong() // Relaxed coastal tempo

                var idleFrames = 0

                while (isActive && appInForeground) {
                    val hasOneShot = dropletPhase >= 0.0 || shellChimePhase >= 0.0 || sandBrushFramesLeft > 0
                    if (!surfActive && !musicActive && !hasOneShot) {
                        idleFrames += framesPerChunk
                        if (idleFrames > sampleRate * 2) break
                    } else {
                        idleFrames = 0
                    }

                    for (i in 0 until framesPerChunk) {
                        val t = sampleIndex.toDouble() / sampleRate
                        var leftOut = 0.0
                        var rightOut = 0.0

                        // 1. Ocean Surf Synthesis (Rolling waves + seafoam wash)
                        if (surfActive && surfVolume > 0.01f) {
                            val primaryWave = 0.5 + 0.5 * sin(2.0 * PI * 0.105 * t)
                            val secondaryWash = 0.5 + 0.5 * sin(2.0 * PI * 0.23 * t + 1.3)
                            val crestFoam = (primaryWave * primaryWave * secondaryWash).coerceIn(0.0, 1.0)
                            val envelope = 0.14 + 0.55 * primaryWave * 0.7 + 0.31 * crestFoam

                            val whiteL = Random.nextDouble(-1.0, 1.0)
                            val whiteR = Random.nextDouble(-1.0, 1.0)
                            brownLeft = (brownLeft + 0.035 * whiteL) * 0.965
                            brownRight = (brownRight + 0.035 * whiteR) * 0.965

                            // Dynamic low-pass cutoff opens as wave crests onto the shore
                            val cutoff = 0.06 + 0.24 * crestFoam
                            lpLeft += cutoff * ((brownLeft * 2.4 + whiteL * 0.22 * crestFoam) - lpLeft)
                            lpRight += cutoff * ((brownRight * 2.4 + whiteR * 0.22 * crestFoam) - lpRight)

                            val stereoDrift = 0.5 + 0.22 * sin(2.0 * PI * 0.07 * t)
                            val gain = surfVolume * envelope * 0.42
                            leftOut += lpLeft * gain * (0.75 + 0.5 * stereoDrift)
                            rightOut += lpRight * gain * (1.25 - 0.5 * stereoDrift)
                        }

                        // 2. Coastal Marimba & Breeze Pad Music
                        if (musicActive && musicVolume > 0.01f) {
                            if (sampleIndex % framesPerBeat == 0L) {
                                val step = ((sampleIndex / framesPerBeat) % 32L).toInt()
                                val chordIdx = (step / 8) % chordProgressions.size
                                val chord = chordProgressions[chordIdx]
                                // Soft syncopated coastal pattern
                                if (step % 2 == 0 || step % 7 == 0) {
                                    val noteIndex = chord[(step + Random.nextInt(2)) % chord.size]
                                    noteFreqs[nextVoice] = coastalScale[noteIndex]
                                    noteAges[nextVoice] = 0.0
                                    notePans[nextVoice] = 0.25 + 0.5 * ((nextVoice % 3) / 2.0)
                                    nextVoice = (nextVoice + 1) % noteFreqs.size
                                }
                            }

                            var musicL = 0.0
                            var musicR = 0.0
                            for (v in noteFreqs.indices) {
                                val age = noteAges[v]
                                if (age < 2.4 && noteFreqs[v] > 20.0) {
                                    val freq = noteFreqs[v]
                                    val attack = (age / 0.012).coerceAtMost(1.0)
                                    val decay = exp(-age * 2.6)
                                    // Warm wooden marimba fundamental + 4th harmonic overtone
                                    val tone = sin(2.0 * PI * freq * age) * 0.82 +
                                        sin(2.0 * PI * freq * 2.0 * age) * 0.14 * exp(-age * 5.0) +
                                        sin(2.0 * PI * freq * 4.0 * age) * 0.04 * exp(-age * 9.0)
                                    val amp = tone * attack * decay * 0.22
                                    musicL += amp * (1.0 - notePans[v] * 0.45)
                                    musicR += amp * (0.55 + notePans[v] * 0.45)
                                    noteAges[v] = age + 1.0 / sampleRate
                                }
                            }

                            // Subtle warm ocean pad drone (F3 + C4)
                            val pad = (sin(2.0 * PI * 174.61 * t) + 0.6 * sin(2.0 * PI * 261.63 * t)) *
                                (0.035 + 0.015 * sin(2.0 * PI * 0.15 * t))

                            leftOut += (musicL + pad) * musicVolume
                            rightOut += (musicR + pad) * musicVolume
                        }

                        // 3. One-shot Tactile SFX
                        if (dropletPhase >= 0.0) {
                            if (dropletPhase < 0.11) {
                                // Rising pitch bubble/droplet "bloop"
                                val freq = 520.0 + 680.0 * (dropletPhase / 0.11)
                                val env = sin(PI * (dropletPhase / 0.11)) * exp(-dropletPhase * 12.0)
                                val sig = sin(2.0 * PI * freq * dropletPhase) * env * 0.25
                                leftOut += sig
                                rightOut += sig
                                dropletPhase += 1.0 / sampleRate
                            } else {
                                dropletPhase = -1.0
                            }
                        }

                        if (shellChimePhase >= 0.0) {
                            if (shellChimePhase < 0.35) {
                                val env = exp(-shellChimePhase * 8.5)
                                val sig = (sin(2.0 * PI * 1046.5 * shellChimePhase) +
                                    0.6 * sin(2.0 * PI * 1318.5 * shellChimePhase)) * env * 0.16
                                leftOut += sig
                                rightOut += sig
                                shellChimePhase += 1.0 / sampleRate
                            } else {
                                shellChimePhase = -1.0
                            }
                        }

                        if (sandBrushFramesLeft > 0) {
                            val progress = sandBrushFramesLeft.toDouble() / (sampleRate / 9.0)
                            val sandNoise = Random.nextDouble(-1.0, 1.0) * sin(PI * progress) * 0.06
                            leftOut += sandNoise
                            rightOut += sandNoise
                            sandBrushFramesLeft--
                        }

                        val clampedL = leftOut.coerceIn(-0.92, 0.92)
                        val clampedR = rightOut.coerceIn(-0.92, 0.92)
                        pcmBuffer[i * 2] = (clampedL * Short.MAX_VALUE).toInt().toShort()
                        pcmBuffer[i * 2 + 1] = (clampedR * Short.MAX_VALUE).toInt().toShort()
                        sampleIndex++
                    }

                    val written = track.write(pcmBuffer, 0, pcmBuffer.size)
                    if (written < 0) break
                }
            } catch (_: Exception) {
                // Safe graceful teardown
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    fun release() {
        streamJob?.cancel()
        engineScope.cancel()
    }
}
