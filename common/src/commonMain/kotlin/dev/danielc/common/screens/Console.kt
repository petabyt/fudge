package dev.danielc.common.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import dev.danielc.common.ui.theme.FudgeTheme
import fantasyfudge.common.generated.resources.*
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.createClipEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.collections.plus
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.toDuration

data class ConsoleLine(
    val line: String,
    val timestamp: Duration? = null,
    val color: Color = Color.White,
)

data class ConsoleState(
    val initialLines: List<ConsoleLine> = emptyList(),
    val initialTime: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    var title: String = "Test Suite",
    var lines: List<ConsoleLine> = initialLines,
) {
    override fun toString(): String {
        var text = ""
        for (x in lines) {
            text += x.line + "\n"
        }
        return text
    }
}

class ConsoleModel: BackgroundViewModel() {
    private val _uiState = MutableStateFlow(ConsoleState())
    val uiState: StateFlow<ConsoleState> = _uiState.asStateFlow()

    fun addLine(line: String) {
        _uiState.update { currentState ->
            var line = line
            val color = if (line.startsWith("<error>")) {
                line = line.substringAfter("<error>")
                Color.Red
            } else {
                Color.White
            }
            val newLine = ConsoleLine(
                color = color,
                line = line,
                timestamp = currentState.initialTime.elapsedNow()
            )
            currentState.copy(lines = currentState.lines + newLine)
        }
    }

    fun clearText(line: String) {
        _uiState.value.lines = emptyList()
    }
}

@Composable
fun Console(modifier: Modifier = Modifier, state: ConsoleState) {
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
            items(state.lines) { line ->
                SelectionContainer {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = line.line,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = line.color
                            )
                        )
                        if (line.timestamp != null) {
                            DisableSelection {
                                Text(
                                    text = "${line.timestamp.inWholeSeconds}s",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    ),
                                    modifier = Modifier.alignByBaseline()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(back: () -> Unit = {}, state: ConsoleState = ConsoleState(), title: String = "Console") {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(title)
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            back()
                        }) {
                            Icon(painterResource(Res.drawable.outline_arrow_back_24), contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                val entry = createClipEntry(label = "app_state_export", text = state.toString())
                                clipboardManager.setClipEntry(entry)
                            }
                        }) {
                            Icon(
                                painter = painterResource(Res.drawable.outline_content_copy_24),
                                contentDescription = "Copy"
                            )
                        }
                    }
                )
            },
        ) { innerPadding ->
            Column(Modifier.padding(innerPadding)) {
                Console(Modifier.fillMaxSize(), state)
            }
        }
    }
}

@Preview(showBackground = true, device = "id:pixel_9a", uiMode = 32)
@Composable
fun PreviewConsoleScreen() {
    val x: MutableList<ConsoleLine> = mutableListOf()

    x += (ConsoleLine(
        color = Color.Red,
        line = "Hello world asidkpaosdkpaoskdpaoskdpaoskdijoaisjdoaisjdoaisdj",
        timestamp = 0.toDuration(DurationUnit.SECONDS)
    ))

    for (i in 0..10) {
        x += (ConsoleLine(
            line = "Hello world",
            timestamp = i.toDuration(DurationUnit.SECONDS)
        ))
    }
    val state = ConsoleState(initialLines = x)
    ConsoleScreen(state = state)
}