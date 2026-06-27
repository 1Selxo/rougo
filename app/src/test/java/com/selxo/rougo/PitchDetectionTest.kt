package com.selxo.rougo

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchDetectionTest {
    @Test
    fun pitchDetectionAcceptsQuietVoicedToneButRejectsNoise() {
        val sampleRate = 44_100
        val sine = ShortArray(sampleRate / 2) { index ->
            (sin(2.0 * PI * 220.0 * index / sampleRate) * 950.0).roundToInt().toShort()
        }
        val noiseRandom = Random(7)
        val noise = ShortArray(sampleRate / 2) {
            noiseRandom.nextInt(-950, 951).toShort()
        }

        val pitch = estimatePitch(sine, sampleRate)

        assertNotNull(pitch)
        assertTrue(pitch!! in 214f..226f)
        assertNull(estimatePitch(noise, sampleRate))
    }
}
