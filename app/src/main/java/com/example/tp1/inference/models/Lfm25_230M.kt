package com.example.tp1.inference.models

import com.example.tp1.inference.ModelArtifact
import com.example.tp1.inference.ModelProfile
import com.example.tp1.inference.ModelSampling

/** Exact integration fixture. The shared engine reads its chat template/tokenizer from the GGUF. */
internal object Lfm25_230M {
    val profile = ModelProfile(
        artifact = ModelArtifact(
            name = "LFM2.5-230M-Q4_K_M.gguf",
            url = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/03502067c64ce32ac4fe87b0cec0310a1a13d3e9/LFM2.5-230M-Q4_K_M.gguf",
            bytes = 153406304L,
            sha256 = "7bbd90384d3deffe4c646ec9643b212802d32d4ce417c90a1ec9282100650062"
        ),
        sampling = ModelSampling(
            temperature = 0.1f,
            topK = 50,
            repetitionPenalty = 1.05f,
            repetitionWindow = 64,
            frequencyPenalty = 0f,
            presencePenalty = 0f
        ),
        requireSingleLeadingBos = true
    )
}
