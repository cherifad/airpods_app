package com.airbuds.companion.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbuds.companion.R
import com.airbuds.companion.data.ble.BlePermissions
import com.airbuds.companion.ui.components.BudsIllustration

private enum class OnboardingStep { Welcome, Permissions, Ready }

/**
 * First-launch flow: welcome, Bluetooth rationale/permission, ready.
 *
 * The permission is requested with a plain explanation before the system
 * dialog; the user can skip and allow it later from the connect sheet.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(BlePermissions.hasScanPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = BlePermissions.hasScanPermissions(context)
        denied = !granted
        if (granted) step = OnboardingStep.Ready
    }

    LaunchedEffect(Unit) {
        granted = BlePermissions.hasScanPermissions(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(18.dp))
        StepDots(step)
        Spacer(Modifier.weight(0.6f))

        when (step) {
            OnboardingStep.Welcome -> WelcomeStep()
            OnboardingStep.Permissions -> PermissionsStep(
                granted = granted,
                denied = denied,
            )

            OnboardingStep.Ready -> ReadyStep()
        }

        Spacer(Modifier.weight(1f))
        when (step) {
            OnboardingStep.Welcome -> Button(
                onClick = { step = OnboardingStep.Permissions },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }

            OnboardingStep.Permissions -> Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (granted) {
                            step = OnboardingStep.Ready
                        } else {
                            permissionLauncher.launch(BlePermissions.scan)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        stringResource(
                            if (granted) {
                                R.string.onboarding_continue
                            } else {
                                R.string.onboarding_allow
                            },
                        ),
                    )
                }
                TextButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_not_now))
                }
            }

            OnboardingStep.Ready -> Button(
                onClick = onFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(stringResource(R.string.onboarding_start))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WelcomeStep() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp),
        contentAlignment = Alignment.Center,
    ) {
        BudsIllustration(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1.25f),
        )
    }
    Spacer(Modifier.height(26.dp))
    Text(
        text = stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PermissionsStep(granted: Boolean, denied: Boolean) {
    Text(
        text = stringResource(R.string.onboarding_permissions_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                R.string.onboarding_permissions_body
            } else {
                R.string.onboarding_permissions_body_legacy
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    Text(
        text = stringResource(
            when {
                granted -> R.string.onboarding_permissions_granted
                denied -> R.string.onboarding_permissions_denied
                else -> R.string.onboarding_permissions_pending
            },
        ),
        style = MaterialTheme.typography.labelMedium,
        color = when {
            granted -> MaterialTheme.colorScheme.primary
            denied -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ReadyStep() {
    Text(
        text = stringResource(R.string.onboarding_ready_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.onboarding_ready_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StepDots(step: OnboardingStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnboardingStep.entries.forEach { entry ->
            Box(
                modifier = Modifier
                    .size(if (entry == step) 9.dp else 6.dp)
                    .background(
                        color = if (entry == step) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}
