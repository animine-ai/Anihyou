package com.axiel7.anihyou.release.core.source

/** Public metadata and source-fenced aggregate measurements only. Never raw URLs, headers or keys. */
interface ExtensionDiagnosticsRepository {
    suspend fun inspect(key: ExtensionSelectionKey): Map<String, String>
}
