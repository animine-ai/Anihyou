package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.source.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Durable host product preference. No trust is inferred from this file. */
class FileExtensionProductPolicyRepository(
    private val directory: File,
    private val eligible: (ExtensionSelectionKey) -> Boolean,
    private val releaseEligible: (ExtensionSelectionKey) -> Boolean,
    private val navigationEligible: (ExtensionSelectionKey) -> Boolean,
) : ExtensionProductPolicyRepository {
    constructor(directory: File, eligible: (ExtensionSelectionKey) -> Boolean) : this(directory, eligible, eligible, eligible)
    private val mutex = Mutex()
    private val file = File(directory, "product-policy.json")
    private val state = MutableStateFlow(runCatching {
        if (file.exists()) decode(file.readBytes()) else ExtensionProductPolicy()
    }.getOrElse { ExtensionProductPolicy() })
    override val policy = state.asStateFlow()

    override suspend fun selectActiveSource(key: ExtensionSelectionKey?) = mutate {
        require(key == null || releaseEligible(key)) { "release source unavailable" }
        it.copy(activeReleaseSource = key)
    }

    override suspend fun selectNavigationProvider(key: ExtensionSelectionKey?) = mutate {
        require(key == null || navigationEligible(key)) { "navigation provider unavailable" }
        it.copy(preferredNavigationProvider = key)
    }

    override suspend fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) = mutate {
        require(eligible(key)) { "extension unavailable" }
        require(key in it.preferences || it.preferences.size < 256)
        it.copy(preferences = it.preferences + (key to preferences))
    }

    override suspend fun invalidateSource(sourceId: String) = mutate {
        it.copy(activeReleaseSource = it.activeReleaseSource?.takeUnless { key -> key.sourceId == sourceId },
            preferredNavigationProvider = it.preferredNavigationProvider?.takeUnless { key -> key.sourceId == sourceId })
    }

    override suspend fun invalidateExtension(key: ExtensionSelectionKey) = mutate {
        it.copy(activeReleaseSource = it.activeReleaseSource?.takeUnless { selected -> selected == key },
            preferredNavigationProvider = it.preferredNavigationProvider?.takeUnless { selected -> selected == key })
    }

    override suspend fun setNavigationProviderOrder(keys: List<ExtensionSelectionKey>) = mutate {
        require(keys.size <= 256 && keys.distinct() == keys && keys.all(navigationEligible))
        it.copy(navigationProviderOrder = keys)
    }

    override suspend fun <T> withCurrentSelection(snapshot: ExtensionProductPolicy, block: suspend () -> T): T? =
        mutex.withLock {
            val active = snapshot.activeReleaseSource ?: return@withLock null
            if (state.value.activeReleaseSource != active || state.value.releaseGeneration != snapshot.releaseGeneration || !releaseEligible(active)) return@withLock null
            block()
        }

    override suspend fun <T> withCurrentPolicy(snapshot: ExtensionProductPolicy, block: suspend () -> T): T? =
        mutex.withLock { if (state.value == snapshot) block() else null }

    private suspend fun mutate(transform: (ExtensionProductPolicy) -> ExtensionProductPolicy) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = state.value
            val changed = transform(old)
            if (changed == old) return@withLock
            val sourceChanged = changed.activeReleaseSource != old.activeReleaseSource ||
                old.activeReleaseSource?.let { changed.preferencesFor(it).enabledTracks != old.preferencesFor(it).enabledTracks } == true
            val next = changed.copy(generation = Math.addExact(old.generation, 1),
                releaseGeneration = if (sourceChanged) Math.addExact(old.releaseGeneration, 1) else old.releaseGeneration)
            require(directory.isDirectory || directory.mkdirs())
            val bytes = encode(next).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 256 * 1024)
            val temporary = File(directory, "product-policy.next")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            ExtensionFileDurability.syncDirectory(directory)
            state.value = next
        }
    }

    private fun key(value: ExtensionSelectionKey?): JsonElement = value?.let {
        JsonArray(listOf(it.sourceId, it.extensionId, it.publisherId, it.providerId).map(::JsonPrimitive))
    } ?: JsonNull

    private fun encode(value: ExtensionProductPolicy) = buildJsonObject {
        put("schemaVersion", 1); put("generation", value.generation)
        put("releaseGeneration", value.releaseGeneration)
        put("activeReleaseSource", key(value.activeReleaseSource))
        put("preferredNavigationProvider", key(value.preferredNavigationProvider))
        put("navigationProviderOrder", JsonArray(value.navigationProviderOrder.map { key(it) }))
        put("preferences", JsonArray(value.preferences.map { (k, p) -> buildJsonObject {
            put("key", key(k))
            put("enabledTracks", JsonArray(p.enabledTracks.sorted().map(::JsonPrimitive)))
            put("preferredTrackOrder", JsonArray(p.preferredTrackOrder.map(::JsonPrimitive)))
            put("languageOrder", JsonArray(p.languageOrder.map(::JsonPrimitive)))
            put("visibleInProviderField", p.visibleInProviderField)
        } }))
    }

    private fun decode(bytes: ByteArray): ExtensionProductPolicy {
        val json = ExtensionWireCodec.parseStrictJson(bytes, 256 * 1024).jsonObject
        val required = setOf("schemaVersion", "generation", "releaseGeneration", "activeReleaseSource", "preferredNavigationProvider", "preferences")
        require(json.keys == required || json.keys == required + "navigationProviderOrder")
        require(json.getValue("schemaVersion").jsonPrimitive.int == 1)
        fun parseKey(value: JsonElement): ExtensionSelectionKey? {
            if (value == JsonNull) return null
            val parts = value.jsonArray.map { it.jsonPrimitive.content }
            require(parts.size == 4)
            return ExtensionSelectionKey(parts[0], parts[1], parts[2], parts[3])
        }
        val entries = json.getValue("preferences").jsonArray
        require(entries.size <= 256)
        val preferences = entries.map { entry ->
            val e = entry.jsonObject
            require(e.keys == setOf("key", "enabledTracks", "preferredTrackOrder", "languageOrder", "visibleInProviderField"))
            fun strings(name: String) = e.getValue(name).jsonArray.map { it.jsonPrimitive.content }
            requireNotNull(parseKey(e.getValue("key"))) to ExtensionPreferences(strings("enabledTracks").toSet(),
                strings("preferredTrackOrder"), strings("languageOrder"), e.getValue("visibleInProviderField").jsonPrimitive.boolean)
        }
        require(preferences.map { it.first }.distinct().size == preferences.size)
        val order = json["navigationProviderOrder"]?.jsonArray?.map { requireNotNull(parseKey(it)) }.orEmpty()
        require(order.size <= 256 && order.distinct() == order)
        return ExtensionProductPolicy(json.getValue("generation").jsonPrimitive.long.also { require(it >= 0) },
            parseKey(json.getValue("activeReleaseSource")), parseKey(json.getValue("preferredNavigationProvider")), preferences.toMap(),
            json.getValue("releaseGeneration").jsonPrimitive.long.also { require(it >= 0) }, order)
    }
}

interface InstalledExtensionAccess {
    suspend fun loadInstalled(key: ExtensionSelectionKey): VerifiedExtensionPackage?
    suspend fun <T> withCurrentPackage(key: ExtensionSelectionKey, digest: String, block: suspend () -> T): T? =
        if (loadInstalled(key)?.packageDigest == digest) block() else null

    /** Generation-aware fence for operations pinned to one installed-package lifecycle. */
    suspend fun <T> withCurrentGeneration(
        key: ExtensionSelectionKey,
        digest: String,
        generation: Long,
        block: suspend () -> T,
    ): T? = loadInstalled(key)?.let { current ->
        if (current.packageDigest == digest && current.packageGeneration == generation) block() else null
    }
}
