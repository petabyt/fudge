package dev.danielc.common.screens

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.danielc.R
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.ModuleManifest
import dev.danielc.common.ui.dummyManifestList
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.common.ui.theme.errorButtonColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectingRequiredAction {
    NONE,
    TURN_ON_WIFI,
    TURN_ON_BLUETOOTH,
    ACCEPT_PERMISSION,
}

//@Preview(showBackground = true, device = "id:pixel_9a", uiMode = 32)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleErrorScreen(back: () -> Unit = {}, state: ConsoleState = ConsoleState()) {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()

    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.module_failure)) },
                    navigationIcon = {
                        IconButton(onClick = { back() }) {
                            Icon(painterResource(R.drawable.outline_arrow_back_24), contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                val clipData = ClipData.newPlainText("label", state.toString())
                                clipboardManager.setClipEntry(clipData.toClipEntry())
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.outline_content_copy_24),
                                contentDescription = "Copy"
                            )
                        }
                    }
                )
            },
        ) { innerPadding ->
            Column(Modifier
                .fillMaxSize()
                .padding(innerPadding)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.module_failed_to_initialize_this_is_a_bug))
                    Console(Modifier.fillMaxSize(), state)
                }
            }
        }
    }
}

data class ConnectingScreenState(
    val progress: Int? = null,
    val tryAgainDisabled: Boolean = false,
    val action: ConnectingRequiredAction = ConnectingRequiredAction.NONE,
    val target: ModuleManifest.Target = dummyManifestList[0].targets[0],
    val transport: ModuleManifest.Transport? = null,
    val disableTryAgain: Boolean = false,
    val userInstruction: String? = null,
    val loadingPopupText: String? = null,
)

open class ConnectingScreenModel(val consoleModel: ConsoleModel): BackgroundViewModel() {
    private val _state = MutableStateFlow(ConnectingScreenState())
    val state = _state.asStateFlow()
    fun reset(target: ModuleManifest.Target, transport: ModuleManifest.Transport?) {
        _state.update {
            ConnectingScreenState(
                target = target,
                transport = transport
            )
        }
    }
    fun setTryAgainDisabled(v: Boolean) { _state.update { it.copy(tryAgainDisabled = v) } }
    fun setProgress(p: Int?) { _state.update { it.copy(progress = p) } }
    fun setRequiredAction(a: ConnectingRequiredAction) { _state.update { it.copy(action = a) } }
    fun setPopupText(s: String?) { _state.update { it.copy(loadingPopupText = s) } }
    fun setUserInstruction(s: String?) { _state.update { it.copy(userInstruction = s) } }

    open fun onCancel(): Boolean { return false }
    open fun onTryAgain() {}
}

@Preview(showBackground = true, device = "id:pixel_9a", uiMode = 32)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectingScreen(back: () -> Unit = {}, model: ConnectingScreenModel = ConnectingScreenModel(ConsoleModel())) {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsStateWithLifecycle()
    val consoleState by model.consoleModel.uiState.collectAsStateWithLifecycle()
    @Composable
    fun ActionMessage(icon: Painter, text: String) {
        Column(Modifier
            .fillMaxSize()
            .padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(200.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(text, textAlign = TextAlign.Center)
            Button({
                model.onTryAgain()
            }, enabled = !state.disableTryAgain) {
                Text(stringResource(R.string.try_again))
            }
        }
    }

    BackHandler {
        back()
    }

    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Connecting")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            back()
                        }) {
                            Icon(painterResource(R.drawable.outline_arrow_back_24), contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                val clipData = ClipData.newPlainText("debug log", consoleState.toString())
                                clipboardManager.setClipEntry(clipData.toClipEntry())
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.outline_content_copy_24),
                                contentDescription = "Copy"
                            )
                        }
                    }
                )
            },
        ) { innerPadding ->
            Box(Modifier
                .fillMaxSize()
                .padding(innerPadding)) {
                Column(Modifier.fillMaxSize()) {
                    if (state.action == ConnectingRequiredAction.TURN_ON_WIFI) {
                        ActionMessage(painterResource(R.drawable.outline_wifi_24),
                            stringResource(R.string.please_turn_on_wifi)
                        )
                    } else if (state.action == ConnectingRequiredAction.TURN_ON_BLUETOOTH) {
                        ActionMessage(painterResource(R.drawable.outline_bluetooth_24),
                            stringResource(R.string.please_turn_on_bluetooth)
                        )
                    } else if (state.action == ConnectingRequiredAction.ACCEPT_PERMISSION) {
                        ActionMessage(painterResource(R.drawable.outline_user_attributes_24),
                            stringResource(R.string.permission_required_to_connect_to_this_device)
                        )
                    } else {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier
                                .fillMaxWidth()
                                .padding(10.dp)) {
                                Icon(painterResource(state.target.deviceId.getIcon()), contentDescription = null, modifier = Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (state.transport == ModuleManifest.Transport.LOCAL_NETWORK_UDP) {
                                        Text(
                                            "Looking for a ${state.target.company} ${state.target.deviceId.getReadableName()}...",
                                            textAlign = TextAlign.Center
                                        )
                                    } else {
                                        Text(
                                            "Connecting to a ${state.target.company} ${state.target.deviceId.getReadableName()}...",
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    if (state.progress != null) Text("${state.progress}%", textAlign = TextAlign.Center)
                                }
                            }
                            Button(onClick = {
                                model.onTryAgain()
                            }, Modifier.fillMaxWidth(), enabled = !state.tryAgainDisabled) {
                                Text(stringResource(R.string.try_again))
                            }
                            Button(onClick = {
                                model.onCancel()
                                back()
                            }, Modifier.fillMaxWidth(), colors = errorButtonColors()) {
                                Text(stringResource(R.string.cancel))
                            }
                            if (state.transport == ModuleManifest.Transport.LOCAL_NETWORK_UDP) {
                                Column(Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                state.progress?.let {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                        progress = { it.toFloat() / 100 }
                                    )
                                }
                            }
                        }
                        Console(Modifier.fillMaxSize(), consoleState)
                    }
                }
                state.loadingPopupText?.let {
                    Box(Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .align(Alignment.Center)) {
                        Column(Modifier.padding(20.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(it)
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                            Text(stringResource(R.string.might_take_a_while), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                state.userInstruction?.let {
                    Box(Modifier
                        .padding(10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .align(Alignment.BottomCenter)) {
                        Row(Modifier
                            .fillMaxWidth()
                            .padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.outline_info_24), contentDescription = null)
                            Text(it)
                        }
                    }
                }
            }
        }
    }
}