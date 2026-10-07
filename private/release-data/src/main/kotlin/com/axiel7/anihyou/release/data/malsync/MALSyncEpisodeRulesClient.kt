package com.axiel7.anihyou.release.data.malsync

import com.axiel7.anihyou.release.data.extension.ExtensionWireCodec
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** List-service numbering data only: no provider identity, URL or release authority. */
data class EpisodeNumberingRule(val fromMalId: Int, val toMalId: Int,
    val providerFirst: Int, val canonicalFirst: Int, val count: Int) {
    init {
        require(fromMalId > 0 && toMalId > 0)
        require(providerFirst in 1..9999 && canonicalFirst in 1..9999 && count in 1..9999)
        require(providerFirst.toLong() + count - 1 <= 9999 && canonicalFirst.toLong() + count - 1 <= 9999)
    }
}

/** null means failure; an empty list is a successfully checked entry with no rules. */
fun interface EpisodeRuleSource {
    suspend fun lookup(malId: Int): List<EpisodeNumberingRule>?
}

class MALSyncEpisodeRulesClient : EpisodeRuleSource {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).build()

    override suspend fun lookup(malId: Int): List<EpisodeNumberingRule>? {
        require(malId > 0)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url("https://api.malsync.moe/rules/$malId").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val rules = response.use {
                        runCatching {
                            if (it.code == 404) return@runCatching emptyList<EpisodeNumberingRule>()
                            if (it.code != 200) return@runCatching null
                            val source = it.body.source()
                            source.request(MAX_BYTES + 1L)
                            if (source.buffer.size > MAX_BYTES) return@runCatching null
                            val bytes = source.readByteArray()
                            parseMALSyncEpisodeRules(bytes)
                        }.getOrNull()
                    }
                    continuation.resume(rules)
                }
            })
        }
    }

    private companion object { const val MAX_BYTES = 65536 }
}

internal fun parseMALSyncEpisodeRules(bytes: ByteArray): List<EpisodeNumberingRule>? = runCatching {
    val root = ExtensionWireCodec.parseStrictJson(bytes, 65536).jsonObject
    require(root["page"]?.jsonPrimitive?.content == "mal")
    val rules = root.getValue("rules").jsonArray
    require(rules.size <= 64)
    fun JsonObject.number(name: String): Int = getValue(name).jsonPrimitive.let {
        require(!it.isString); it.int
    }
    rules.map { value ->
        val rule = value.jsonObject
        val from = rule.getValue("from").jsonObject
        val to = rule.getValue("to").jsonObject
        val fromFirst = from.number("start"); val toFirst = to.number("start")
        val fromLast = from.number("end"); val toLast = to.number("end")
        require(fromFirst in 1..9999 && toFirst in 1..9999 && fromLast in fromFirst..9999 && toLast in toFirst..9999)
        val count = fromLast - fromFirst + 1
        require(count == toLast - toFirst + 1)
        EpisodeNumberingRule(from.number("id"), to.number("id"), fromFirst, toFirst, count)
    }.distinct()
}.getOrNull()
