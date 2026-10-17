package com.axiel7.anihyou.release.core.api

/**
 * The cover of the anime a release notification is about. It is remembered when the notification is decided, from the answer
 * that decided it, so that posting it later (a retry, after the process died) needs no AniList request, which is the one that
 * used to fail when AniList's rate limit was on.
 */
interface ReleaseMediaArtStore {
    fun remember(mediaId: Int, coverUrl: String)
    fun coverUrl(mediaId: Int): String?
}

object NoopReleaseMediaArtStore : ReleaseMediaArtStore {
    override fun remember(mediaId: Int, coverUrl: String) = Unit
    override fun coverUrl(mediaId: Int): String? = null
}
