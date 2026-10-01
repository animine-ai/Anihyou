package com.axiel7.anihyou.core.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import com.axiel7.anihyou.core.model.CurrentListType
import com.axiel7.anihyou.core.model.media.ChartType
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.navigation.Route

sealed class BottomDestination(
    val index: Int,
    val route: Route,
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
    val stableId: String,
    val topLevelEligible: Boolean = true,
    val animatedIcon: Boolean = true,
) {
    data object Home : BottomDestination(
        stableId = "home",
        index = 0,
        route = Route.Home,
        title = R.string.home,
        icon = R.drawable.anim_home,
    )

    data object AnimeList : BottomDestination(
        stableId = "anime",
        index = 1,
        route = Route.AnimeTab,
        title = R.string.anime,
        icon = R.drawable.anim_tv,
    )

    data object MangaList : BottomDestination(
        stableId = "manga",
        index = 2,
        route = Route.MangaTab,
        title = R.string.manga,
        icon = R.drawable.anim_book,
    )

    data object Profile : BottomDestination(
        stableId = "profile",
        index = 3,
        route = Route.Profile,
        title = R.string.profile,
        icon = R.drawable.anim_person,
    )

    data object Explore : BottomDestination(
        stableId = "explore",
        index = 4,
        route = Route.Explore,
        title = R.string.explore,
        icon = R.drawable.anim_explore,
    )

    data object Calendar : BottomDestination(5, Route.CalendarMain, R.string.calendar,
        R.drawable.calendar_month_24, "calendar", animatedIcon = false)

    data class Shortcut(val id: String, val key: Route, val label: Int, val drawable: Int, val legacyIndex: Int) :
        BottomDestination(legacyIndex, key, label, drawable, id, animatedIcon = false)

    companion object {
        val values = listOf(Home, AnimeList, MangaList, Explore, Calendar)
        val railValues = values
        val catalog: List<BottomDestination> = listOf(Home, AnimeList, MangaList, Explore, Calendar, Profile) +
            CurrentListType.entries.mapIndexed { index, type ->
                Shortcut("current_" + type.name.lowercase(), Route.CurrentListMain(type), when(type) {
                    CurrentListType.AIRING -> R.string.airing
                    CurrentListType.BEHIND -> R.string.anime_behind
                    CurrentListType.ANIME -> R.string.watching
                    CurrentListType.MANGA -> R.string.reading
                    CurrentListType.NEXT_SEASON -> R.string.next_season
                }, R.drawable.play_arrow_24, 100 + index)
            } + ChartType.entries.mapIndexed { index, type ->
                Shortcut("chart_" + type.name.lowercase(), Route.ChartMain(type.name), when(type) {
                    ChartType.TOP_ANIME, ChartType.TOP_MANGA -> R.string.top_100
                    ChartType.POPULAR_ANIME, ChartType.POPULAR_MANGA -> R.string.top_popular
                    ChartType.POPULAR_MANHWA -> R.string.popular_manhwa
                    ChartType.UPCOMING_ANIME, ChartType.UPCOMING_MANGA -> R.string.upcoming
                    ChartType.AIRING_ANIME -> R.string.airing
                    ChartType.TOP_MOVIES -> R.string.top_movies
                    ChartType.PUBLISHING_MANGA -> R.string.publishing
                }, type.icon(), 200 + index)
            } + listOf(
                Shortcut("season_current", Route.SeasonMain(false), R.string.season, R.drawable.calendar_today_24, 300),
                Shortcut("season_next", Route.SeasonMain(true), R.string.next_season, R.drawable.calendar_today_24, 301),
            )
        val routes = catalog.mapTo(linkedSetOf()) { it.route }

        fun Int.toBottomDestinationRoute(): Route? = catalog.find { it.index == this }?.route

        fun NavKey.isBottomDestination() = catalog.any { it.route == this }

        val BottomDestination.testTag
            get() = when (this) {
                is Home -> "HomeTab"
                is AnimeList -> "AnimeListTab"
                is MangaList -> "MangaListTab"
                is Profile -> "ProfileTab"
                is Explore -> "ExploreTab"
                is Calendar -> "CalendarTab"
                is Shortcut -> "MainTab-$stableId"
            }
    }
}