/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.res.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MomentArcAllAppsPanel(
    state: MomentArcUiState, onLaunch: (MomentArcTarget) -> Unit, onSelection: () -> Unit,
    onBack: () -> Unit, onDismiss: () -> Unit,
) {
    val geometry = state.panelGeometry ?: return
    PlatformTheme {
        val surface = MaterialTheme.colorScheme.surface
        Canvas(Modifier.fillMaxSize()) {
            val r = panelRect(state)
            drawRoundRect(
                surface, topLeft = Offset(r.left, r.top), size = Size(r.width(), r.height()),
                cornerRadius = CornerRadius(geometry.cornerRadius * state.panelProgress),
                alpha = state.panelExitAlpha,
            )
        }
        // Keep the four-column grid at its full size; fade it before shrinking the shell.
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Box(Modifier.graphicsLayer {
                    alpha = state.panelContentAlpha
                    val r = panelRect(state)
                    shape = RevealShape(
                        r.left - geometry.bounds.left, r.top - geometry.bounds.top,
                        r.right - geometry.bounds.left, r.bottom - geometry.bounds.top,
                        geometry.cornerRadius * state.panelProgress,
                    )
                    clip = true
                }) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density * geometry.scale, density.fontScale)) {
                        AllAppsContent(state, onLaunch, onSelection)
                    }
                }
            },
        ) { measurables, constraints ->
            val bounds = geometry.bounds
            val content = measurables.single().measure(Constraints.fixed(bounds.width(), bounds.height()))
            layout(constraints.maxWidth, constraints.maxHeight) { content.place(bounds.left, bounds.top) }
        }
        if (state.panelVisible) {
            val description = stringResource(R.string.moment_arc_back_all_apps)
            val closeDescription = stringResource(R.string.moment_arc_close_all_apps)
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val widthScale by animateFloatAsState(
                targetValue = if (pressed) 1.15f else 1f,
                animationSpec = spring(dampingRatio = .6f, stiffness = 800f),
                label = "MomentArcHandlePress",
            )
            val handleColor = colorResource(android.R.color.system_accent1_100)
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(Modifier.semantics { contentDescription = description }
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = state.panelInteractive,
                            role = Role.Button,
                            onClick = onBack,
                            onLongClickLabel = closeDescription,
                            onLongClick = onDismiss,
                        )
                        .graphicsLayer {
                            alpha = state.panelContentAlpha * state.panelExitAlpha *
                                ((state.panelProgress - .75f) / .25f).coerceIn(0f, 1f)
                        }) {
                        Canvas(Modifier.fillMaxSize()) {
                            val visual = geometry.handleVisualBounds
                            val touch = geometry.handleTouchBounds
                            val width = visual.width() * widthScale
                            val height = visual.height() *
                                (1f + .65f * ((widthScale - 1f) / .15f).coerceIn(0f, 1.2f))
                            drawRoundRect(
                                handleColor,
                                topLeft = Offset(visual.centerX() - width / 2f - touch.left,
                                    visual.centerY() - height / 2f - touch.top),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(height / 2f),
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val touch = geometry.handleTouchBounds
                val placeable = measurables.single().measure(Constraints.fixed(touch.width(), touch.height()))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(touch.left, touch.top) }
            }
        }
    }
}

private fun panelRect(state: MomentArcUiState): android.graphics.RectF {
    val from = state.panelSource
    val geometry = state.panelGeometry!!
    val to = geometry.bounds
    val f = state.panelProgress
    val opened = android.graphics.RectF(
        from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
        from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f,
    )
    if (!state.launchingApp) return opened
    val target = geometry.launchBounds
    val progress = state.launchProgress
    return android.graphics.RectF(
        opened.left + (target.left - opened.left) * progress,
        opened.top + (target.top - opened.top) * progress,
        opened.right + (target.right - opened.right) * progress,
        opened.bottom + (target.bottom - opened.bottom) * progress,
    )
}

private class RevealShape(
    val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(left, top, right, bottom, CornerRadius(radius)))
}

@Composable
private fun AllAppsContent(
    state: MomentArcUiState, onLaunch: (MomentArcTarget) -> Unit, onSelection: () -> Unit,
) {
    val apps = state.apps
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { androidx.compose.runtime.mutableStateOf<Job?>(null) }
    val positions = remember(apps) {
        var position = 1 + (apps?.arcApps?.size ?: 0)
        apps?.sections.orEmpty().map { section ->
            val header = position
            position += 1 + section.apps.size
            header
        }
    }
    val density = LocalDensity.current
    val gridBounds = state.panelGeometry?.gridBounds
    LaunchedEffect(state.continuousGesture, state.panelInteractive, apps, grid, state.panelGeometry) {
        if (!state.continuousGesture || !state.panelInteractive || apps == null) return@LaunchedEffect
        val bounds = gridBounds ?: return@LaunchedEffect
        val byKey = buildMap {
            apps.arcApps.forEach { put("pinned:${it.key}", it) }
            apps.sections.forEach { section -> section.apps.forEach { put("all:${it.key}", it) } }
        }
        var anchorX = state.gestureX
        var anchorY = state.gestureY
        val threshold = with(density) { 12.dp.toPx() }
        val edge = with(density) { 48.dp.toPx() }
        while (state.continuousGesture && state.panelInteractive) {
            val x = state.gestureX
            val y = state.gestureY
            val inPanel = bounds.contains(x.toInt(), y.toInt())
            val gridY = y - bounds.top
            val gridHeight = bounds.height()
            if (!inPanel || gridHeight <= 0f) {
                state.setGestureSelection(null)
                anchorX = x
                anchorY = y
            } else if (gridY < edge || gridY > gridHeight - edge) {
                // Scroll at a controlled rate. Never select a cell solely because it moved
                // underneath a stationary finger; moving away from the edge rearms selection.
                state.setGestureSelection(null)
                anchorX = x
                anchorY = y
                val distance = if (gridY < edge) edge - gridY else gridY - (gridHeight - edge)
                val direction = if (gridY < edge) -1f else 1f
                grid.scrollBy(direction * (2f + 10f * (distance / edge).coerceIn(0f, 1f)) * density.density)
            } else {
                if (kotlin.math.hypot(x - anchorX, y - anchorY) >= threshold) {
                    val localX = x - bounds.left
                    val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        localX >= item.offset.x && localX <= item.offset.x + item.size.width &&
                            gridY >= item.offset.y && gridY <= item.offset.y + item.size.height
                    }
                    val app = (item?.key as? String)?.let(byKey::get)
                    val hit = if (app != null && item != null) RectF(
                        bounds.left + item.offset.x.toFloat(),
                        (bounds.top + item.offset.y).toFloat(),
                        bounds.left + item.offset.x + item.size.width.toFloat(),
                        (bounds.top + item.offset.y + item.size.height).toFloat(),
                    ) else null
                    if (state.setGestureSelection(app, hit)) onSelection()
                }
            }
            delay(16)
        }
    }
    Column(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.moment_arc_all_apps_title),
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4), state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 32.dp, bottom = 12.dp),
                userScrollEnabled = state.panelInteractive,
            ) {
                item(key = "arc-header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.moment_arc_apps_section))
                }
                items(apps?.arcApps.orEmpty(), key = { "pinned:${it.key}" }) { app ->
                    AppCell(app, state.panelInteractive, state.gestureSelection?.key == app.key, onLaunch)
                }
                apps?.sections.orEmpty().forEachIndexed { index, section ->
                    item(key = "section:$index", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(section.label) }
                    items(section.apps, key = { "all:${it.key}" }) { app ->
                        AppCell(app, state.panelInteractive, state.gestureSelection?.key == app.key, onLaunch)
                    }
                }
            }
            if (apps != null && apps.sections.size > 1) {
                AlphabetIndex(
                    apps.sections.map { it.label }, state.panelInteractive,
                    Modifier.align(Alignment.CenterEnd).width(32.dp).fillMaxHeight(),
                ) { index ->
                    scrollJob?.cancel()
                    scrollJob = scope.launch { grid.scrollToItem(positions[index]) }
                }
            }
            if (state.appsLoading && apps == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (state.appsLoadFailed) {
                Text(
                    stringResource(R.string.moment_arc_apps_load_failed),
                    Modifier.align(Alignment.Center).padding(16.dp).clickable(enabled = state.panelInteractive) {
                        state.invalidateApps()
                    }, textAlign = TextAlign.Center,
                )
            } else if (apps != null && apps.sections.isEmpty() && apps.arcApps.isEmpty()) {
                Text(stringResource(R.string.moment_arc_no_apps), Modifier.align(Alignment.Center).padding(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(label, Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun AppCell(
    app: MomentArcApp, enabled: Boolean, selected: Boolean, onLaunch: (MomentArcTarget) -> Unit,
) {
    val matrix = remember(app) { Matrix() }
    Column(
        Modifier.padding(8.dp).fillMaxWidth()
            .then(if (selected) Modifier.graphicsLayer { alpha = .65f } else Modifier)
            .clickable(enabled = enabled) { onLaunch(app.target) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(48.dp)) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val saved = native.save()
                val icon = app.icon
                val w = icon.intrinsicWidth
                val h = icon.intrinsicHeight
                if (w > 0 && h > 0) {
                    matrix.setRectToRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
                        android.graphics.RectF(0f, 0f, size.width, size.height), Matrix.ScaleToFit.CENTER)
                    native.concat(matrix)
                    icon.setBounds(0, 0, w, h)
                } else icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                icon.draw(native)
                native.restoreToCount(saved)
            }
        }
        Text(app.label, Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AlphabetIndex(labels: List<String>, enabled: Boolean, modifier: Modifier, onSelect: (Int) -> Unit) {
    var selected by remember(labels) { mutableIntStateOf(-1) }
    Column(modifier.pointerInput(labels, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun select(y: Float) {
                val index = (y / size.height.coerceAtLeast(1) * labels.size).toInt().coerceIn(labels.indices)
                if (selected != index) { selected = index; onSelect(index) }
            }
            down.consume()
            select(down.position.y)
            try {
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    select(change.position.y)
                } while (true)
            } finally { selected = -1 }
        }
    }, verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
        labels.forEachIndexed { index, label ->
            Text(label, Modifier.clickable(enabled = enabled) { onSelect(index) }, fontSize = 12.sp,
                fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
