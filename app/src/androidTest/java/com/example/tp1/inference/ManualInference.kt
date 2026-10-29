package com.example.tp1.inference

/** Excluded by default Gradle runner arguments. Explicit model runs must override notAnnotation. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ManualInference
