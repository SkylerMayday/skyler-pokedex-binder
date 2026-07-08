package com.skyler.pokedexbinder.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * Shared card-image renderer that desaturates + scrims the image when [dimmed] is true,
 * used to represent "unowned" across Connecting Art slots and Personal Collection cells.
 *
 * Rationale: a single desaturate-scrim helper keeps the "unowned = greyed" visual identical
 * across both binders (and any future one) instead of duplicating ColorMatrix logic per screen.
 */
@Composable
fun DimmableCardImage(
    imageUrl: String?,
    contentDescription: String?,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            colorFilter = if (dimmed) {
                ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            } else {
                null
            },
            modifier = Modifier.fillMaxSize()
        )
        if (dimmed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }
    }
}
