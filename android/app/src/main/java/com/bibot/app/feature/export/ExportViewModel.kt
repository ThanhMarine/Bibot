package com.bibot.app.feature.export

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A3.1 ExportViewModel — manages export UI state, observes WorkManager progress.
 * Usage: ExportViewModel.scheduleExport(bookId) -> observe exportState
 */
class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private var currentBookId: String? = null

    sealed interface ExportState {
        object Idle : ExportState
        data class Exporting(
            val progress: Int,
            val total: Int,
            val skipped: Int
        ) : ExportState
        data class Success(val outputPath: String, val skipped: Int) : ExportState
        data class Error(val message: String) : ExportState
    }

    fun scheduleExport(bookId: String) {
        currentBookId = bookId
        _exportState.value = ExportState.Exporting(0, 1, 0)
        ExportWorker.schedule(getApplication(), bookId)
        observeProgress(bookId)
    }

    fun cancelExport() {
        currentBookId?.let { ExportWorker.cancel(getApplication(), it) }
        _exportState.value = ExportState.Idle
        currentBookId = null
    }

    private fun observeProgress(bookId: String) {
        viewModelScope.launch {
            val workManager = WorkManager.getInstance(getApplication())
            val workName = ExportWorker.uniqueName(bookId)
            workManager.getWorkInfosByTagLiveData(workName).observeForever { workInfos ->
                val info = workInfos.firstOrNull()
                if (info == null) return@observeForever

                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val prog = info.progress
                        _exportState.value = ExportState.Exporting(
                            progress = prog.getInt(ExportWorker.PROG_DONE, 0),
                            total = prog.getInt(ExportWorker.PROG_TOTAL, 1),
                            skipped = prog.getInt(ExportWorker.PROG_SKIPPED, 0)
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val output = info.outputData.getString(ExportWorker.OUT_PATH) ?: ""
                        val skipped = info.outputData.getInt(ExportWorker.PROG_SKIPPED, 0)
                        _exportState.value = ExportState.Success(output, skipped)
                        currentBookId = null
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        val err = info.outputData.getString(ExportWorker.OUT_ERROR) ?: "unknown"
                        _exportState.value = ExportState.Error(err)
                        currentBookId = null
                    }
                    WorkInfo.State.BLOCKED -> {}
                }
            }
        }
    }
}