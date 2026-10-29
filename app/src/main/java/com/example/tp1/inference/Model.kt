package com.example.tp1.inference

import com.example.tp1.inference.models.Lfm25_230M
import com.example.tp1.inference.models.JinaV5NanoRetrieval as JinaRetrieval
import java.net.URL

internal enum class Model(internal val profile: ModelProfile) {
    Test(Lfm25_230M.profile),
    JinaV5NanoRetrieval(JinaRetrieval.profile)
}

/** Model-specific choices. Runtime resource/request limits remain shared engine policy. */
internal data class ModelProfile(
    val artifact: ModelArtifact,
    /** Null for an embedding model; generation always requires sampling settings. */
    val sampling: ModelSampling? = null,
    val requireSingleLeadingBos: Boolean = false
) {
    init { require(sampling != null || !requireSingleLeadingBos) }
}

internal data class ModelSampling(
    val temperature: Float,
    val topK: Int,
    val repetitionPenalty: Float,
    val repetitionWindow: Int,
    val frequencyPenalty: Float,
    val presencePenalty: Float
) {
    init {
        require(temperature.isFinite() && temperature >= 0)
        require(topK > 0 && repetitionWindow in 0..4096)
        require(repetitionPenalty.isFinite() && repetitionPenalty > 0)
        require(frequencyPenalty.isFinite() && presencePenalty.isFinite())
    }
}

internal data class ModelArtifact(val name: String, val url: String, val bytes: Long, val sha256: String) {
    init {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        require(URL(url).protocol == "https" && bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}
