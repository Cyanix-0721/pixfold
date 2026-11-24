package com.cyanix.pixfold.debug

import android.content.Intent
import androidx.compose.runtime.Composable

internal data class DebugShowcaseRequest(val sceneId: String)

internal fun parseDebugShowcaseRequest(intent: Intent?): DebugShowcaseRequest? = null

internal fun isExitShowcaseIntent(intent: Intent?): Boolean = false

internal fun clearDebugShowcaseExtras(intent: Intent?) = Unit

@Composable
internal fun DebugShowcaseScreen(
    request: DebugShowcaseRequest,
    onExitShowcase: () -> Unit,
) = Unit
