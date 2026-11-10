package com.airbuds.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airbuds.companion.data.update.AppRelease
import com.airbuds.companion.data.update.AppUpdateRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Visible state of the in-app update flow. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    data class Ready(val version: String) : UpdateState
    data object InstallPermissionRequired : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases once per process, downloads the APK in the app and
 * hands it to the system installer.
 *
 * Nothing opens a browser. The download starts automatically when a newer
 * version exists; the user can cancel it with "Later".
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppUpdateRepository(application)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var checked = false
    private var downloaded: File? = null

    fun check() {
        if (checked || job?.isActive == true) return
        checked = true

        job = viewModelScope.launch {
            _state.value = UpdateState.Checking
            try {
                val release = repository.latestUpdate()
                if (release == null) {
                    _state.value = UpdateState.Idle
                    return@launch
                }
                download(release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = UpdateState.Failed(error.message ?: "Update check failed")
            }
        }
    }

    fun install() {
        val file = downloaded ?: return
        if (!repository.install(file)) {
            _state.value = UpdateState.InstallPermissionRequired
        }
    }

    fun openInstallPermissionSettings() {
        repository.openInstallPermissionSettings()
    }

    fun retry() {
        checked = false
        check()
    }

    /** Cancels the download and hides the dialog for this session. */
    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = UpdateState.Idle
    }

    private suspend fun download(release: AppRelease) {
        val version = release.version.toString()
        _state.value = UpdateState.Downloading(version, 0)
        val file = repository.download(release) { percent ->
            _state.value = UpdateState.Downloading(version, percent)
        }
        downloaded = file
        _state.value = UpdateState.Ready(version)
    }
}
