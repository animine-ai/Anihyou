# Local read-only tracker interface

Other apps on this phone can request the runtime permission
`<applicationId>.permission.READ_TRACKER_DATA` to read the tracker provider at
`content://<applicationId>.tracker`. Release uses `app.kiyori`, debug `app.kiyori.debug`.
Nothing can be written. The permission can be revoked in Android settings.

The provider runs in a separate `:tracker` process. Application startup deliberately does not
start Koin, networking or schedulers in that process. It reads a small credential-free snapshot
under `noBackupFilesDir/tracker-share`, populated from existing successful list-screen results.
It does not download or refresh anything. Open Kiyori and refresh the current/watching list first.

## Watching list

`/watching` returns `media_id`, `title_romaji`, `title_english`, `cover_url`, `progress`,
`episodes_total`, `latest_episode`, `next_episode`, `next_airing_at`, `site_url`.
CURRENT and REPEATING entries are included. Unknown episode counts remain null.
Absolute AniList airing timestamps are used; cached relative countdowns are never added to now.

## Upcoming episode

`/airing?from=<epoch seconds>&to=<epoch seconds>&mine=1` returns `media_id`, `title_romaji`,
`title_english`, `cover_url`, `episode`, `airing_at`, `in_my_list`, `list_status`, `site_url`.
The range is half-open `[from,to)`, at most31 days. This endpoint exposes the next known episode
per watched show, not a complete global schedule or future episodes not present in the cache.
`mine=0` is explicitly unsupported.

## Result metadata and privacy

Cursor extras: `contract_version=1`, `scope=watching`, `cached_at_millis` (snapshot publication,
not a promise of a live server refresh), `tracker_status=ok|cache_missing|signed_out|unsupported`.
Clients must inspect status before interpreting an empty cursor. Every page must be available
before a list is marked complete. Limits:1000 entries/20 pages and2MB snapshot.

Token changes/sign-out invalidate the snapshot; switching accounts atomically clears old rows.
Late results for another account are rejected. No tokens, cookies or notes are included.
The snapshot is not backed up. No new task, alarm, service or network trigger is added.

Tests cover the pure snapshot/range/account rules. Android permission, process isolation and
the real cross-app roundtrip still require device testing.
