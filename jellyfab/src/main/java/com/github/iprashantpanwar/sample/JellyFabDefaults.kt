/*
 * JellyFab – A delightful, jelly-like floating action menu for Jetpack Compose
 * Author: Prashant Panwar (github.com/iprashantpanwar)
 * License: Apache 2.0
 */

package com.github.iprashantpanwar.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

object JellyFabDefaults {
    /**
     * Creates a [JellyFabColors] that represents the default colors used in a [JellyFab].
     */
    @Composable
    fun colors(
        containerColor: Color = Color(0xFFFF7F86),
        secondaryContainerColor: Color = Color(0xFF6BA5FF),
        contentColor: Color = Color.White,
        scrimColor: Color = Color.White.copy(alpha = 0.55f) // Note: Alpha is often handled by config, but color is here
    ): JellyFabColors = JellyFabColors(
        containerColor = containerColor,
        secondaryContainerColor = secondaryContainerColor,
        contentColor = contentColor,
        scrimColor = scrimColor
    )
}

/**
 * Represents the colors used by a [JellyFab] and its items.
 */
@Immutable
@Stable
data class JellyFabColors(
    val containerColor: Color,
    val secondaryContainerColor: Color,
    val contentColor: Color,
    val scrimColor: Color
)
