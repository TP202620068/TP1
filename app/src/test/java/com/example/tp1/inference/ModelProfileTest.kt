package com.example.tp1.inference

import org.junit.Assert.*
import org.junit.Test

class ModelProfileTest {
    @Test fun samplingRejectsInvalidNumbersAndUnboundedHistoryButAllowsGreedy() {
        val sampling = Model.Test.profile.sampling
        val invalid = listOf<() -> ModelSampling>(
            { sampling.copy(temperature = Float.NaN) }, { sampling.copy(temperature = -1f) },
            { sampling.copy(temperature = Float.POSITIVE_INFINITY) }, { sampling.copy(topK = 0) },
            { sampling.copy(repetitionPenalty = 0f) }, { sampling.copy(repetitionPenalty = Float.NaN) },
            { sampling.copy(repetitionWindow = -1) }, { sampling.copy(repetitionWindow = 4097) },
            { sampling.copy(frequencyPenalty = Float.NaN) }, { sampling.copy(presencePenalty = Float.POSITIVE_INFINITY) }
        )
        invalid.forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
        val greedy = sampling.copy(temperature = 0f, topK = 1, repetitionWindow = 0)
        assertEquals(0f, greedy.temperature)
        assertEquals(1, greedy.topK)
    }
}
