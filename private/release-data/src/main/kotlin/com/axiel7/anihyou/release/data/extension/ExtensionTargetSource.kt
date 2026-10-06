package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1

/** Host-owned target identity retained beside the provider-neutral protocol value. */
data class ExtensionAcquisitionTarget(
    val target: ExtensionTargetV1,
    val canonicalKey: String,
)

fun interface ExtensionTargetSource {
    suspend fun targets(): List<ExtensionAcquisitionTarget>
}
