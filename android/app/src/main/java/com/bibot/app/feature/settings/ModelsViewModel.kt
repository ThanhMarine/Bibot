package com.bibot.app.feature.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.offline.DownloadState
import com.bibot.app.data.offline.ModelCatalog
import com.bibot.app.data.offline.ModelDownloader
import com.bibot.app.data.offline.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelUiState(
    val models: List<ModelInfo> = ModelCatalog.all,
    val installed: Map<String, Boolean> = emptyMap(),
    val sizes: Map<String, Long> = emptyMap(),
    val downloadStates: Map<String, DownloadState> = emptyMap(),
    val freeSpaceMb: Long = 0
)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val downloader = ModelDownloader(context)
    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()
    // Giữ 1 collector / model — chống rò coroutine khi bấm Tải nhiều lần
    private val collectorJobs = mutableMapOf<String, Job>()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = ModelCatalog.all.associate { it.id to it.isInstalled(context) }
            val sizes = ModelCatalog.all.associate { it.id to it.installedSize(context) }
            val freeMb = FilePaths.cacheRoot(context).freeSpace / (1024 * 1024)
            _uiState.value = _uiState.value.copy(installed = installed, sizes = sizes, freeSpaceMb = freeMb)
        }
    }

    fun download(model: ModelInfo) {
        val st = downloader.stateFlow(model.id).value
        if (st is DownloadState.Downloading || st is DownloadState.Unzipping) return
        if (collectorJobs[model.id]?.isActive != true) {
            collectorJobs[model.id] = viewModelScope.launch {
                downloader.stateFlow(model.id).collect { s ->
                    _uiState.value = _uiState.value.copy(downloadStates = _uiState.value.downloadStates + (model.id to s))
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { downloader.download(model) } catch (_: Exception) {}
            refresh()
        }
    }

    /** Cài model từ file user chọn qua file picker. */
    fun installFromFile(model: ModelInfo, uri: Uri) {
        val st = downloader.stateFlow(model.id).value
        if (st is DownloadState.Downloading || st is DownloadState.Unzipping) return
        if (collectorJobs[model.id]?.isActive != true) {
            collectorJobs[model.id] = viewModelScope.launch {
                downloader.stateFlow(model.id).collect { s ->
                    _uiState.value = _uiState.value.copy(downloadStates = _uiState.value.downloadStates + (model.id to s))
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { downloader.installFromFile(model, uri) } catch (_: Exception) {}
            refresh()
        }
    }

    fun delete(model: ModelInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try { downloader.delete(model) } catch (_: Exception) {}
            refresh()
        }
    }

    fun downloadState(id: String): StateFlow<DownloadState> = downloader.stateFlow(id)
}
