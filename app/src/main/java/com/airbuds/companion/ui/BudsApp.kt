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
import com.airbuds.companion.BuildConfig
import com.airbuds.companion.data.preferences.OnboardingPreferences
import kotlinx.coroutines.delay

@Composable
fun BudsApp() {
    val budsViewModel: BudsViewModel = viewModel()
    val state by budsViewModel.uiState.collectAsStateWithLifecycle()

    var popupVisible by rememberSaveable { mutableStateOf(false) }
    var autoShown by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    val updateViewModel: UpdateViewModel = viewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val debuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val onboardingPreferences = remember(context) { OnboardingPreferences(context) }
    var onboardingDone by rememberSaveable { mutableStateOf(onboardingPreferences.completed) }

    if (debuggable && showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false })
        return
    }

    if (!onboardingDone) {
        OnboardingScreen(
            onFinished = {
                onboardingPreferences.completed = true
                onboardingDone = true
            },
        )
        return
    }

    // Play the "case just opened" moment once, when the earbuds are ready.
    LaunchedEffect(state.connected) {
        if (state.connected && !autoShown) {
            delay(400)
            popupVisible = true
            autoShown = true
        }
    }

    LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG) {
            updateViewModel.check()
        }
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
            onPrimaryAction = {
                if (state.connected) {
                    popupVisible = true
                } else {
                    budsViewModel.showConnectSheet()
                }
            },
            onAncChange = budsViewModel::setAnc,
            onDisconnect = {
                popupVisible = false
                budsViewModel.disconnect()
            },
            onRefresh = budsViewModel::refresh,
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
            onAncChange = budsViewModel::setAnc,
            onDismiss = { popupVisible = false },
        )
        ConnectSheet(
            visible = state.connectSheetVisible,
            state = state,
            onDismiss = budsViewModel::hideConnectSheet,
            onScan = budsViewModel::scan,
            onConnect = { device -> budsViewModel.connect(device.device) },
        )
        UpdateDialog(
            state = updateState,
            onDownload = updateViewModel::download,
            onInstall = updateViewModel::install,
            onRetry = updateViewModel::retry,
            onDismiss = updateViewModel::dismiss,
        )
    }
}
