package com.crunzex.linuxondex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One UI's signature screen frame: an oversized title that scrolls away and
 * hands over to a small centred app-bar title.
 *
 * The expanded title is the first item *inside* the list, exactly as Samsung
 * does it, so it always scrolls fully out of the way and the motion tracks
 * the finger. A slim opaque bar stays pinned on top for the navigation icon
 * and actions, and its compact title cross-fades in only once the large one
 * has faded out — the two are never legible at the same time.
 */
@Composable
fun OneUiCollapsingScaffold(
    /**
     * Null for a screen whose content is its own heading — One UI's About
     * pages lead with the app icon and carry no title at all. The pinned bar
     * still holds the navigation icon and any actions.
     */
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val statusBarHeight = with(density) {
        WindowInsets.statusBars.getTop(density).toDp()
    }
    val collapseDistancePx = with(density) { COLLAPSE_DISTANCE_DP.dp.toPx() }
    val collapseFraction by remember(collapseDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = statusBarHeight + PINNED_BAR_HEIGHT_DP.dp,
                    bottom = CONTENT_BOTTOM_PADDING_DP.dp,
                ),
            ) {
                if (title != null) {
                    item { ExpandedTitle(title, subtitle, collapseFraction) }
                }
                content()
            }
            PinnedBar(
                title = title,
                collapseFraction = collapseFraction,
                onNavigateBack = onNavigateBack,
                actions = actions,
            )
        }
        bottomBar?.invoke()
    }
}

/** The oversized title that lives in the list and scrolls away. */
@Composable
private fun ExpandedTitle(title: String, subtitle: String?, collapseFraction: Float) {
    // Fade out over the first stretch of the scroll so it is gone before the
    // pinned title appears.
    val titleAlpha = (1f - collapseFraction / LARGE_TITLE_FADE_END).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp)
            .alpha(titleAlpha),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Always-present slim bar: navigation, actions, and the compact title. */
@Composable
private fun PinnedBar(
    title: String?,
    collapseFraction: Float,
    onNavigateBack: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    // Only start appearing after the large title has fully faded.
    val compactTitleAlpha =
        ((collapseFraction - COMPACT_TITLE_FADE_START) /
            (1f - COMPACT_TITLE_FADE_START)).coerceIn(0f, 1f)

    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(PINNED_BAR_HEIGHT_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate up")
                }
            }
            // Always start-aligned, exactly like One UI's collapsed title —
            // even without a back arrow it sits at the leading edge.
            Text(
                text = title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (onNavigateBack == null) 24.dp else 4.dp,
                        end = 8.dp,
                    )
                    .alpha(compactTitleAlpha),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
    }
}

/**
 * One UI bottom action area: full-width capsule buttons on the background
 * colour, inset from the navigation bar.
 */
@Composable
fun OneUiBottomBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private const val PINNED_BAR_HEIGHT_DP = 56

/** Scroll distance over which the title hands over to the pinned bar. */
private const val COLLAPSE_DISTANCE_DP = 96

/** Large title is fully gone by this fraction of the collapse. */
private const val LARGE_TITLE_FADE_END = 0.5f

/** Compact title only begins to appear after this fraction. */
private const val COMPACT_TITLE_FADE_START = 0.6f

private const val CONTENT_BOTTOM_PADDING_DP = 24
