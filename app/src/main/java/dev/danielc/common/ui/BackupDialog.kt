package dev.danielc.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.danielc.R
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.MimeType
import dev.danielc.common.ModuleJob
import dev.danielc.common.SortBy
import dev.danielc.common.Timestamp
import dev.danielc.common.screens.FilesystemState
import dev.danielc.common.screens.isSelected
import dev.danielc.fudge.hoursSince
import dev.danielc.fudge.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class BackupState(
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val importFromIndex: Int = 0,
    val importfilterMask: UInt = 0b111u,
    val eta: String? = null,
    val nDownloaded: Int = 0,
    val nToDownload: Int = 0,
    val currentStatus: String? = null,
    val itemsWereSelected: Boolean = false,
)

open class BackupModel(val fs: StateFlow<FilesystemState> = MutableStateFlow(FilesystemState())): BackgroundViewModel() {
    private val _state = MutableStateFlow(BackupState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var moduleJob: ModuleJob? = null

    open fun cancelJob(job: ModuleJob) {}
    open fun onStart() {}
    open fun onStop() {}
    open fun fulfillFileMetadata(index: Int) {}
    open fun downloadFile(index: Int, update: (ModuleJob) -> Unit = {}) {}

    fun reset() {
        val wereSelected = fs.value.objects.filter { it?.selected ?: false }
        _state.update { BackupState(itemsWereSelected = wereSelected.isNotEmpty(), nToDownload = wereSelected.size) }
    }
    fun updateState(block: (BackupState) -> BackupState) { _state.update { block(it) } }
    fun updateStatus(v: String?) { updateState { it.copy(currentStatus = v) }; println(v) }
    fun setImportFilter(i: Int, v: Boolean) {
        _state.update { it.copy(importfilterMask =
            if (v) it.importfilterMask or (1u shl i) else it.importfilterMask and (1u shl i).inv()
        ) }
    }
    fun cancel(): Boolean {
        if (job?.isCancelled ?: false) return false
        job?.cancel()
        cancelJob(moduleJob ?: return true)
        return true
    }
    fun passesTimeFilter(date: Timestamp?): Boolean {
        if (state.value.importFromIndex == 0) return true
        if (date == null) return false
        val hoursMax = when (state.value.importFromIndex) {
            1 -> 24
            2 -> 24 * 7
            3 -> 24 * 30
            else -> throw Error("undefined")
        }
        return hoursMax >= Timestamp.now().hoursSince(date)
    }
    fun passesTypeFilter(type: MimeType): Boolean {
        val mask = state.value.importfilterMask
        if ((mask and (1u shl 0)) == 0u) if (type.isImage()) return false
        if ((mask and (1u shl 1)) == 0u) if (type == MimeType.RAW) return false
        if ((mask and (1u shl 2)) == 0u) if (type.isVideo()) return false
        return true
    }
    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch {
            updateState { it.copy(isRunning = true) }
            try {
                var index = 0
                while (!(job?.isCancelled ?: false)) {
                    if (!_state.value.itemsWereSelected) {
                        val filteredTotalValidObjects = if (_state.value.importFromIndex == 0) fs.value.objects.size else {
                            var nValid = 0
                            for (obj in fs.value.objects) {
                                if (passesTimeFilter(obj?.metadata?.createdTimestamp)
                                    && passesTypeFilter(obj?.metadata?.getMimeType() ?: continue))
                                    nValid++
                            }
                            nValid
                        }
                        updateState { it.copy(nToDownload = filteredTotalValidObjects) }
                    }
                    if (index >= fs.value.objects.size) { updateStatus("End of list"); break }
                    val obj = fs.value.objects[index]
                    if (obj?.metadata == null) {
                        fulfillFileMetadata(index)
                        if (fs.value.objects[index]?.metadata == null) index++
                        continue
                    }
                    if (_state.value.itemsWereSelected) {
                        if (!obj.selected || obj.hasSaved) {
                            index++
                            continue
                        }
                        updateStatus("Downloading ${obj.metadata.filename ?: "?"}")
                        downloadFile(index) {
                            moduleJob = if (it.isFinished) null else it
                        }
                        updateState { it.copy(nDownloaded = it.nDownloaded + 1) }
                    } else {
                        if (!passesTimeFilter(obj.metadata.createdTimestamp) && fs.value.objectListSortedOrder == SortBy.NEWEST_FIRST) {
                            updateStatus("End of newest list")
                            break
                        }
                        if (obj.hasSaved) {
                            index++
                            continue
                        }
                        if (passesTimeFilter(obj.metadata.createdTimestamp) && passesTypeFilter(obj.metadata.getMimeType())) {
                            updateStatus("Downloading ${obj.metadata.filename ?: "?"}")
                            downloadFile(index) {
                                moduleJob = if (it.isFinished) null else it
                            }
                            updateState { it.copy(nDownloaded = it.nDownloaded + 1) }
                        }
                    }
                    index++
                }
            } catch (_: CancellationException) {
                updateStatus("Cancelled")
            } catch (e: Error) {
                updateStatus(e.message)
            }
            updateState { it.copy(isRunning = false, isFinished = true) }
            job = null
        }
    }
}

@PreviewPixel9ProDark
@Composable
fun BackupDialog(dismiss: () -> Unit = {}, model: BackupModel = BackupModel()) {
    val isWorking by remember { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    LargeCustomAlertDialog(
        onDismissRequest = { if (state.isRunning) model.cancel() else dismiss() },
        title = "Download and Back up",
        icon = {
            if (isWorking) {
                CircularProgressIndicator(modifier = Modifier
                    .padding(end = 16.dp)
                    .size(24.dp))
            } else {
                Icon(
                    painter = painterResource(R.drawable.outline_archive_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(24.dp)
                )
            }
        },
        actionButtons = {
            if (state.isRunning) {
                TextButton(onClick = { model.cancel() }) { Text("Cancel") }
            } else if (state.isFinished) {
                TextButton(onClick = {
                    model.updateState { it.copy(isFinished = false) }
                    dismiss()
                }) { Text("Close") }
            } else {
                TextButton(onClick = { dismiss() }) { Text("Close") }
                TextButton(onClick = { model.start() }) { Text("Start") }
            }
        },
        content = {
            Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isRunning) {
                    Row {
                        Text("Download progress:")
                        Spacer(Modifier.weight(1f))
                        Text("${state.nDownloaded}/${state.nToDownload}")
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = {
                        if (state.nToDownload == 0) 0f else (state.nDownloaded.toFloat() / state.nToDownload.toFloat())
                    })
                    state.eta?.let {
                        Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Text("ETA: ${it}")
                        }
                    }
                    state.currentStatus?.let {
                        Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Text("Status: ${it}")
                        }
                    }
                } else if (state.isFinished) {
                    Text("Finished")
                    Text("${state.currentStatus}")
                } else if (state.itemsWereSelected) {
                    Text("Download ${state.nToDownload} selected items")
                } else {
                    Text("Import files from:")
                    val times = listOf("All time", "Last day", "Last week", "Last 30 days")
                    Column {
                        for (i in times.indices) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .selectable(
                                        selected = state.importFromIndex == i,
                                        onClick = { model.updateState({ it.copy(importFromIndex = i) }) },
                                        role = Role.RadioButton
                                    )
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = state.importFromIndex == i, onClick = null)
                                Text(
                                    text = times[i],
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                    Text("Import filter:")
                    val types = listOf("Images", "RAWs", "Videos")
                    Column {
                        for (i in types.indices) {
                            val selected = (state.importfilterMask and (1u shl i)) != 0u
                            Row(Modifier.fillMaxWidth()
                                    .height(56.dp)
                                    .selectable(
                                        selected = selected,
                                        onClick = { model.setImportFilter(i, !selected) },
                                        role = Role.RadioButton
                                    ).padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = null
                                )
                                Text(
                                    text = types[i],
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}