package com.axiel7.anihyou.release.core

/**
 * Phase-0-only proof that the release core boundary compiles as pure Kotlin/JVM.
 * This type intentionally contains no release-domain behavior.
 */
object Wp00CoreCompatibilityProbe {
    fun echo(value: String): String = value
}
