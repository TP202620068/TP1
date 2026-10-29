package com.example.tp1.inference.models

import com.example.tp1.inference.ModelArtifact
import com.example.tp1.inference.ModelProfile

/** Retrieval adapter is already merged. Native output is 768-dimensional with LAST pooling. */
internal object JinaV5NanoRetrieval {
    val profile = ModelProfile(
        artifact = ModelArtifact(
            name = "v5-nano-retrieval-IQ4_NL.gguf",
            url = "https://huggingface.co/jinaai/jina-embeddings-v5-text-nano-retrieval/resolve/ac5d898c8d382b17167c33e5c8af644a3519b47d/v5-nano-retrieval-IQ4_NL.gguf",
            bytes = 152405056L,
            sha256 = "f243c19458bcd8162632b89bfeaf02b077b24c2d812e5ece1b73bd380732ba29"
        )
    )
}
