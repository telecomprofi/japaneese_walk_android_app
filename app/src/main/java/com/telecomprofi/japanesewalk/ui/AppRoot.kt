package com.telecomprofi.japanesewalk.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.telecomprofi.japanesewalk.ui.screens.DataScreen
import com.telecomprofi.japanesewalk.ui.screens.MusicScreen
import com.telecomprofi.japanesewalk.ui.screens.SettingsScreen
import com.telecomprofi.japanesewalk.ui.screens.SummaryScreen
import com.telecomprofi.japanesewalk.ui.screens.WalkScreen
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)

/** Page order is also swipe order, so it follows the flow of a session. */
private val TABS = listOf(
    Tab("Summary", Icons.Filled.Speed),
    Tab("Walk", Icons.AutoMirrored.Filled.DirectionsWalk),
    Tab("Data", Icons.Filled.GridView),
    Tab("Music", Icons.Filled.MusicNote),
    Tab("Settings", Icons.Filled.Settings),
)

private const val PAGE_SUMMARY = 0
private const val PAGE_WALK = 1
private const val PAGE_DATA = 2
private const val PAGE_MUSIC = 3

/**
 * A pager rather than a nav graph: the screens are peers you move between
 * sideways, which is how you want to reach the numbers mid-walk. The bottom bar
 * and the swipe drive the same state, so they can never disagree.
 */
@Composable
fun AppRoot() {
    val pagerState = rememberPagerState(initialPage = PAGE_SUMMARY) { TABS.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            // Keeping neighbours alive means a swipe to Data mid-walk shows live
            // numbers immediately instead of a frame of empty tiles.
            beyondViewportPageCount = 1,
        ) { page ->
            when (page) {
                PAGE_SUMMARY -> SummaryScreen(
                    onStartWorkout = { scope.launch { pagerState.animateScrollToPage(PAGE_WALK) } },
                    onOpenWorkout = { scope.launch { pagerState.animateScrollToPage(PAGE_WALK) } },
                )
                PAGE_WALK -> WalkScreen(
                    onFinished = { scope.launch { pagerState.animateScrollToPage(PAGE_SUMMARY) } },
                )
                PAGE_DATA -> DataScreen()
                PAGE_MUSIC -> MusicScreen()
                else -> SettingsScreen()
            }
        }
    }
}
