package com.axiel7.anihyou.feature.worker

import android.content.Context
import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import kotlinx.coroutines.CancellationException

internal data class ExtensionUpdateOutcome(
    val displayName: String,
    val fromVersion: String?,
    val toVersion: String,
    val installed: Boolean,
)

/** Remembers which signed package digests were already tried, so a package that failed is not tried again and again. */
internal interface ExtensionUpdateLedger {
    fun attempted(digest: String): Boolean
    fun markAttempted(digest: String)
}

internal class SharedPreferencesExtensionUpdateLedger(context: Context) : ExtensionUpdateLedger {
    private val preferences = context.getSharedPreferences("extension_update_attempts", Context.MODE_PRIVATE)

    override fun attempted(digest: String): Boolean = preferences.getStringSet(KEY, emptySet()).orEmpty().contains(digest)

    override fun markAttempted(digest: String) {
        val kept = (preferences.getStringSet(KEY, emptySet()).orEmpty() + digest).toList().takeLast(MAX_DIGESTS).toSet()
        preferences.edit().putStringSet(KEY, kept).apply()
    }

    private companion object {
        const val KEY = "digests"
        const val MAX_DIGESTS = 40
    }
}

/**
 * Installs the newer signed package of every extension that is already installed, once per package. The installer checks the
 * signature, the module profile and the smoke run itself and keeps the previous version when anything fails, so this only
 * decides when to ask. A package that is yanked or revoked is never asked for.
 */
internal suspend fun installAvailableExtensionUpdates(
    repository: ExtensionSourceRepository,
    ledger: ExtensionUpdateLedger,
): List<ExtensionUpdateOutcome> {
    val outcomes = mutableListOf<ExtensionUpdateOutcome>()
    for (source in repository.sources.value.filter { it.enabled }) {
        for (extension in source.extensions) {
            if (!extension.updateAvailable || extension.installedDigest == null || extension.revoked || extension.candidateYanked) continue
            if (ledger.attempted(extension.digest)) {
                AppLog.d("worker") { "extension update ${extension.extensionId} ${extension.version}: already tried, left to the user" }
                continue
            }
            ledger.markAttempted(extension.digest)
            AppLog.i("worker") {
                "extension update ${extension.extensionId} ${extension.installedVersion} -> ${extension.version}: installing in the background"
            }
            try {
                repository.activate(source.id, extension.extensionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w("worker") { "extension update ${extension.extensionId} failed: ${error.javaClass.simpleName}" }
            }
            val after = repository.sources.value.firstOrNull { it.id == source.id }
                ?.extensions?.firstOrNull { it.extensionId == extension.extensionId }
            val installed = after?.installedDigest == extension.digest
            AppLog.i("worker") { "extension update ${extension.extensionId} ${extension.version}: ${if (installed) "installed" else "not installed"}" }
            outcomes += ExtensionUpdateOutcome(extension.displayName, extension.installedVersion, extension.version, installed)
        }
    }
    return outcomes
}
