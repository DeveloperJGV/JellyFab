/*
 * JellyFab – A delightful, jelly-like floating action menu for Jetpack Compose
 * Author: Prashant Panwar (github.com/iprashantpanwar)
 * License: Apache 2.0
 *
 * Core composable that coordinates:
 * - Main FAB (jelly blob rendering + rotation icon)
 * - Primary mini-FABs arranged on an arc (top, top-left, left)
 * - Optional secondary mini-FABs chained outward from the first primary FAB
 *
 * State drives everything:
 *  state.expanded → controls primary layer visibility
 *  state.secondaryExpanded → controls chained secondary layer
 *
 * Animations:
 *  - Distances, scaling, opacity (α), and positions use Animatable
 *  - Bulging jelly movement is handled in JellyPhysics.kt
 */

package com.github.iprashantpanwar.sample

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Main JellyFab entry point — renders a floating action button with an
 * expandable, jelly-like motion. The main FAB animates with a soft blob effect,
 * primary actions fan out along an arc, and an optional secondary group can
 * chain outward from the first primary action.
 *
 * @param modifier Layout modifier for positioning the entire FAB group.
 *
 * @param state External state controller for expand/collapse behavior.
 * Use [rememberJellyFabState] to create and hoist this state.
 *
 * @param fabSize Diameter of the main (center) FAB.
 * Default is `72.dp` which matches Material 3 large FAB sizing.
 *
 * @param miniFabSize Diameter of the small mini-FABs used in primary
 * and secondary layers. Usually `48.dp`.
 *
 * @param colors [JellyFabColors] that will be used to resolve the colors used for this FAB.
 * See [JellyFabDefaults.colors].
 *
 * @param primaryItems The list of actions forming the first fan-out arc
 * around the main FAB. Each [JellyFabItem] provides an icon + click action.
 *
 * @param secondaryItems Optional list of actions that expand outward in a chained,
 * cascading motion from the **first primary FAB**. If empty, JellyFab behaves as
 * a single-layer radial menu.
 *
 * @param showScrim When `true`, a subtle background scrim is shown behind the JellyFab while
 * expanded, helping bring focus to the action menu and visually dimming the underlying UI.
 *
 * @param config Fine-grained control for animation timing, bounce physics,
 * jelly squishiness, spacing, and layer layout geometry.
 *
 * Example usage:
 * ```
 * val state = rememberJellyFabState()
 * JellyFab(
 *     state = state,
 *     primaryItems = listOf(...),
 *     secondaryItems = listOf(...)
 * )
 * ```
 */

@Composable
fun JellyFab(
    modifier: Modifier = Modifier,
    state: JellyFabState = rememberJellyFabState(),
    fabSize: Dp = 72.dp,
    miniFabSize: Dp = 48.dp,
    colors: JellyFabColors = JellyFabDefaults.colors(),
    primaryItems: List<JellyFabItem>,
    secondaryItems: List<JellyFabItem> = emptyList(),
    showScrim: Boolean = true,
    config: JellyFabConfig = JellyFabConfig(),
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Core animation holders (one Animatable per FAB)
    val primaryProgress = remember { primaryItems.map { Animatable(0f) } }
    val secondaryProgress = remember { secondaryItems.map { Animatable(0f) } }

    // Bulges drive the jelly blob wobble, separate from FAB motion
    val bulges = remember { primaryItems.map { Animatable(0f) } }

    // Prevent overlapping animation sequences when toggling state quickly
    var primaryAnimJob by remember { mutableStateOf<Job?>(null) }
    var secondaryAnimJob by remember { mutableStateOf<Job?>(null) }

    // First launch: snap to initial state (no animation on startup)
    var didRunOnce by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        primaryProgress.forEach { it.snapTo(if (state.expanded) 1f else 0f) }
        bulges.forEach { it.snapTo(0f) }
        secondaryProgress.forEach { it.snapTo(if (state.secondaryExpanded) 1f else 0f) }
    }

    // Animate primary ring expand/collapse when state.expanded changes
    LaunchedEffect(state.expanded) {
        primaryAnimJob?.cancel()

        if (!didRunOnce) {
            didRunOnce = true
            return@LaunchedEffect // Skip animation on initial draw
        }

        primaryAnimJob = scope.launch {
            if (state.expanded) {
                expandPrimaryLayer(primaryProgress, bulges, config) { bulge ->
                    playDoubleBounce(bulge, outward = true, soft = true)
                }
            } else {
                collapsePrimaryLayer(
                    primaryProgress, bulges, config,
                    bounceTopBulge = { playDoubleBounce(it, outward = false) },
                    bounceSideBulge = { playDoubleBounce(it, outward = false) }
                )
            }
        }
    }

    // Animate secondary chained ring
    LaunchedEffect(state.secondaryExpanded) {
        secondaryAnimJob?.cancel()
        secondaryAnimJob = scope.launch {
            if (state.secondaryExpanded) {
                expandSecondaryLayer(secondaryProgress, config)
            } else {
                collapseSecondaryLayer(secondaryProgress, config)
            }
        }
    }

    val centerCorrect = (miniFabSize - fabSize) / 2f

    // Precompute positions of primary ring on arc
    val primaryAngles = remember(primaryItems.size) { evenArcAngles(count = primaryItems.size) }
    val primaryPositions = remember { MutableList(primaryItems.size) { Offset.Zero } }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // SCRIM OVERLAY (fade + tap-to-collapse)
        if (showScrim) {
            JellyFabScrim(
                expanded = state.expanded,
                secondaryExpanded = state.secondaryExpanded,
                config = config,
                scrimColor = colors.scrimColor,
                onClose = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        state.secondaryExpanded = false
                        delay(secondaryCollapseDuration(secondaryItems.size, config))
                        state.expanded = false
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.BottomEnd
        ) {

            // ---------- PRIMARY LAYER ----------
            JellyFabPrimaryLayer(
                primaryItems = primaryItems,
                primaryProgress = primaryProgress,
                primaryAngles = primaryAngles,
                primaryPositions = primaryPositions,
                config = config,
                miniFabSize = miniFabSize,
                centerCorrect = centerCorrect,
                colors = colors,
                secondaryItems = secondaryItems,
                state = state,
                onClose = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        state.secondaryExpanded = false
                        delay(secondaryCollapseDuration(secondaryItems.size, config))
                        state.expanded = false
                    }
                }
            )

            // ---------- SECONDARY LAYER (CHAIN / ARC ABOVE PRIMARY) ----------
            JellyFabSecondaryLayer(
                secondaryItems = secondaryItems,
                secondaryProgress = secondaryProgress,
                state = state,
                primaryPositions = primaryPositions,
                config = config,
                miniFabSize = miniFabSize,
                centerCorrect = centerCorrect,
                colors = colors,
                onClose = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        state.secondaryExpanded = false
                        delay(secondaryCollapseDuration(secondaryItems.size, config))
                        state.expanded = false
                    }
                }
            )

            // ---------- MAIN JELLY BLOB FAB ----------
            JellyFabMainButton(
                fabSize = fabSize,
                colors = colors,
                bulges = bulges,
                config = config,
                primaryAngles = primaryAngles,
                state = state,
                secondaryItems = secondaryItems,
                onToggle = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    when {
                        !state.expanded -> state.expanded = true
                        state.secondaryExpanded -> {
                            scope.launch {
                                state.secondaryExpanded = false
                                delay(secondaryCollapseDuration(secondaryItems.size, config))
                                state.expanded = false
                            }
                        }
                        else -> state.expanded = false
                    }
                }
            )
        }
    }
}

@Composable
private fun JellyFabScrim(
    expanded: Boolean,
    secondaryExpanded: Boolean,
    config: JellyFabConfig,
    scrimColor: Color,
    onClose: () -> Unit
) {
    val scrimVisible = expanded || secondaryExpanded
    val scrimAlpha by animateFloatAsState(
        targetValue = if (scrimVisible) config.scrimAlpha else 0f,
        animationSpec = tween(config.scrimAnimationDuration)
    )

    if (scrimAlpha > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(scrimColor) // Alpha is handled by graphicsLayer
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClose
                )
        )
    }
}

@Composable
private fun JellyFabPrimaryLayer(
    primaryItems: List<JellyFabItem>,
    primaryProgress: List<Animatable<Float, *>>,
    primaryAngles: List<Double>,
    primaryPositions: MutableList<Offset>,
    config: JellyFabConfig,
    miniFabSize: Dp,
    centerCorrect: Dp,
    colors: JellyFabColors,
    secondaryItems: List<JellyFabItem>,
    state: JellyFabState,
    onClose: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    primaryItems.forEachIndexed { index, item ->
        val angleRad = Math.toRadians(primaryAngles[index])
        val p = primaryProgress[index].value
        val dx = (cos(angleRad) * config.layerDistance.value * p)
        val dy = (sin(angleRad) * config.layerDistance.value * p)
        primaryPositions[index] = Offset(dx.toFloat(), dy.toFloat())

        FloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                when {
                    // toggle secondary ring from the first mini FAB
                    index == 0 && secondaryItems.isNotEmpty() ->
                        state.secondaryExpanded = !state.secondaryExpanded

                    // clicking another primary mini while secondary is open collapses everything
                    state.secondaryExpanded -> onClose()

                    else -> {
                        state.expanded = false
                        item.onClick()
                    }
                }
            },
            modifier = Modifier
                .offset(dx.dp + centerCorrect, dy.dp + centerCorrect)
                .graphicsLayer {
                    scaleX = 0.8f + 0.2f * p
                    scaleY = 0.8f + 0.2f * p
                }
                .size(miniFabSize),
            shape = CircleShape,
            containerColor = if (index == 0 && secondaryItems.isNotEmpty()) colors.secondaryContainerColor else colors.containerColor,
            elevation = FloatingActionButtonDefaults.elevation(0.dp)
        ) {
            val icon: ImageVector =
                if (index == 0 && secondaryItems.isNotEmpty())
                    animatedSecondaryToggleIcon(
                        secondaryExpanded = state.secondaryExpanded,
                        delayOnCollapse = secondaryCollapseDuration(
                            secondaryItems.size,
                            config
                        )
                    )
                else item.icon

            Icon(
                imageVector = icon,
                contentDescription = item.contentDescription,
                tint = colors.contentColor
            )
        }
    }
}

@Composable
private fun JellyFabSecondaryLayer(
    secondaryItems: List<JellyFabItem>,
    secondaryProgress: List<Animatable<Float, *>>,
    state: JellyFabState,
    primaryPositions: List<Offset>,
    config: JellyFabConfig,
    miniFabSize: Dp,
    centerCorrect: Dp,
    colors: JellyFabColors,
    onClose: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    if (state.secondaryExpanded || secondaryProgress.any { it.value > 0f }) {
        val radius = config.layerDistance.value * config.secondaryLayerSpacingMultiplier
        val anchor = primaryPositions.firstOrNull() ?: Offset.Zero
        var prevOrigin = anchor
        var prevTarget = anchor

        secondaryItems.forEachIndexed { i, item ->
            val p = secondaryProgress[i].value
            val angle = 90 + (90.0 / (secondaryItems.size - 1).coerceAtLeast(1)) * i
            val target = calculateArcOffset(angle, radius)

            val prevProg = if (i == 0) 1f else secondaryProgress[i - 1].value
            val prevCurrent = calculateLerpOffset(prevOrigin, prevTarget, prevProg)
            val current = calculateLerpOffset(prevCurrent, target, p)

            prevOrigin = prevCurrent
            prevTarget = target

            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClose()
                    item.onClick()
                },
                modifier = Modifier
                    .offset(current.x.dp + centerCorrect, current.y.dp + centerCorrect)
                    .graphicsLayer {
                        alpha = p
                        scaleX = 0.8f + 0.2f * p
                        scaleY = 0.8f + 0.2f * p
                    }
                    .size(miniFabSize),
                shape = CircleShape,
                containerColor = colors.secondaryContainerColor,
                elevation = FloatingActionButtonDefaults.elevation(0.dp)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.contentDescription,
                    tint = colors.contentColor
                )
            }
        }
    }
}

@Composable
private fun JellyFabMainButton(
    fabSize: Dp,
    colors: JellyFabColors,
    bulges: List<Animatable<Float, *>>,
    config: JellyFabConfig,
    primaryAngles: List<Double>,
    state: JellyFabState,
    secondaryItems: List<JellyFabItem>,
    onToggle: () -> Unit
) {
    Box(modifier = Modifier.size(fabSize), contentAlignment = Alignment.Center) {

        JellyBlob(
            size = fabSize,
            color = colors.containerColor,
            bulges = bulges.map { it.value },
            shadowOpacity = config.shadowOpacity,
            shadowBlurFactor = config.shadowBlurFactor,
            bulgeAngles = primaryAngles,
            bounceFactor = config.bounceFactor
        )

        val rotation by animateFloatAsState(if (state.expanded) 0f else 45f, tween(260))

        Box(
            Modifier
                .size(fabSize)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onToggle
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = CloseIcon,
                contentDescription = if (state.expanded) "Close menu" else "Open menu",
                tint = colors.contentColor,
                modifier = Modifier
                    .size(32.dp)
                    .rotate(rotation)
            )
        }
    }
}

/**
 * Handles delayed icon-switching for the primary mini FAB when toggling secondary layer.
 */
@Composable
internal fun animatedSecondaryToggleIcon(
    secondaryExpanded: Boolean,
    delayOnCollapse: Long = 360L
): ImageVector {
    var icon by remember { mutableStateOf(MoreHorizontal) }

    LaunchedEffect(secondaryExpanded) {
        if (secondaryExpanded) {
            icon = CloseIcon
        } else {
            delay(delayOnCollapse) // switch only after collapse finishes
            icon = MoreHorizontal
        }
    }

    return icon
}

// --- Helper Functions ---

private fun calculateArcOffset(angleDeg: Double, radius: Float): Offset {
    val rad = Math.toRadians(angleDeg)
    return Offset(
        (cos(rad) * radius).toFloat(),
        (sin(rad) * radius).toFloat()
    )
}

private fun calculateLerpOffset(start: Offset, stop: Offset, fraction: Float): Offset {
    return Offset(
        x = start.x + (stop.x - start.x) * fraction,
        y = start.y + (stop.y - start.y) * fraction
    )
}

// --- Previews ---

@Preview(showBackground = true, name = "Collapsed")
@Composable
fun JellyFabPreviewCollapsed() {
    val state = rememberJellyFabState(initialExpanded = false)
    Box(Modifier.fillMaxSize()) {
        JellyFab(
            state = state,
            primaryItems = listOf(
                JellyFabItem(Icons.Default.Add, "Add"),
                JellyFabItem(Icons.Default.Edit, "Edit"),
                JellyFabItem(Icons.Default.Favorite, "Favorite")
            )
        )
    }
}

@Preview(showBackground = true, name = "Expanded")
@Composable
fun JellyFabPreviewExpanded() {
    val state = rememberJellyFabState(initialExpanded = true)
    Box(Modifier.fillMaxSize()) {
        JellyFab(
            state = state,
            primaryItems = listOf(
                JellyFabItem(Icons.Default.Add, "Add"),
                JellyFabItem(Icons.Default.Edit, "Edit"),
                JellyFabItem(Icons.Default.Favorite, "Favorite")
            )
        )
    }
}

@Preview(showBackground = true, name = "With Secondary")
@Composable
fun JellyFabPreviewWithSecondary() {
    val state = rememberJellyFabState(initialExpanded = true, initialSecondaryExpanded = true)
    Box(Modifier.fillMaxSize()) {
        JellyFab(
            state = state,
            primaryItems = listOf(
                JellyFabItem(Icons.Default.Add, "Add"),
                JellyFabItem(Icons.Default.Edit, "Edit"),
                JellyFabItem(Icons.Default.Favorite, "Favorite")
            ),
            secondaryItems = listOf(
                JellyFabItem(Icons.Default.Share, "Share"),
                JellyFabItem(Icons.Default.Favorite, "Like")
            )
        )
    }
}

@Preview(showBackground = true, name = "Main Button")
@Composable
fun JellyFabMainButtonPreview() {
    val state = rememberJellyFabState()
    val bulges = remember { listOf(Animatable(0f), Animatable(0f), Animatable(0f)) }
    Box(Modifier.padding(16.dp)) {
        JellyFabMainButton(
            fabSize = 72.dp,
            colors = JellyFabDefaults.colors(),
            bulges = bulges,
            config = JellyFabConfig(),
            primaryAngles = listOf(270.0, 225.0, 180.0),
            state = state,
            secondaryItems = emptyList(),
            onToggle = {}
        )
    }
}

@Preview(showBackground = true, name = "Primary Layer")
@Composable
fun JellyFabPrimaryLayerPreview() {
    val state = rememberJellyFabState(initialExpanded = true)
    val items = listOf(
        JellyFabItem(Icons.Default.Add, "Add"),
        JellyFabItem(Icons.Default.Edit, "Edit")
    )
    val progress = remember { items.map { Animatable(1f) } }
    val positions = remember { MutableList(items.size) { Offset.Zero } }
    
    Box(Modifier.size(200.dp), contentAlignment = Alignment.BottomEnd) {
        JellyFabPrimaryLayer(
            primaryItems = items,
            primaryProgress = progress,
            primaryAngles = listOf(270.0, 180.0),
            primaryPositions = positions,
            config = JellyFabConfig(),
            miniFabSize = 48.dp,
            centerCorrect = (-12).dp, // (48 - 72) / 2
            colors = JellyFabDefaults.colors(),
            secondaryItems = emptyList(),
            state = state,
            onClose = {}
        )
    }
}

@Preview(showBackground = true, name = "Scrim")
@Composable
fun JellyFabScrimPreview() {
    Box(Modifier.size(200.dp).background(Color.Gray)) {
        JellyFabScrim(
            expanded = true,
            secondaryExpanded = false,
            config = JellyFabConfig(),
            scrimColor = Color.White.copy(alpha = 0.55f),
            onClose = {}
        )
    }
}
