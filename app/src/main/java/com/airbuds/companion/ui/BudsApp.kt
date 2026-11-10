package com.airbuds.companion.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airbuds.companion.model.BudsState
import com.airbuds.companion.model.BudsStateSaver
import kotlinx.coroutines.delay

@Composable
fun BudsApp() {
    var state by rememberSaveable(stateSaver = BudsStateSaver) { mutableStateOf(BudsState()) }
    var popupVisible by rememberSaveable { mutableStateOf(false) }
    var autoShown by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    val updateViewModel: UpdateViewModel = viewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val debuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    if (debuggable && showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false })
        return
    }

    // First launch plays the "case just opened" moment automatically.
    LaunchedEffect(Unit) {
        if (!autoShown) {
            delay(700)
            popupVisible = true
            autoShown = true
        }
    }

    LaunchedEffect(Unit) {
        updateViewModel.check()
    }

    BackHandler(enabled = popupVisible) {
        popupVisible = false
    }

    val blurRadius by animateDpAsState(
        targetValue = if (popupVisible) 8.dp else 0.dp,
        animationSpec = tween(260),
        label = "backdropBlur",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HomeScreen(
            state = state,
            onAncChange = { state = state.copy(anc = it) },
            onShowPopup = { popupVisible = true },
            onOpenDiagnostics = if (debuggable) {
                { showDiagnostics = true }
            } else {
                null
            },
            modifier = Modifier.blur(blurRadius),
        )
        BudsPopup(
            visible = popupVisible,
            state = state,
            onAncChange = { state = state.copy(anc = it) },
            onDismiss = { popupVisible = false },
        )
        UpdateDialog(
            state = updateState,
            onInstall = updateViewModel::install,
            onRetry = updateViewModel::retry,
            onDismiss = updateViewModel::dismiss,
        )
    }
}
