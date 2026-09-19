package dev.danielc.common.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.danielc.R
import dev.danielc.common.ui.theme.FudgeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun DisconnectedScreen(reason: String = "Reason: Failed to connect - (Disconnected)", backToMainScreen: () -> Unit = {}, consoleState: ConsoleState = ConsoleState(), report: BugReport = BugReport()) {
    var showBugDialog by remember { mutableStateOf(false) }

    FudgeTheme {
        if (showBugDialog) {
            BugReportDialog(backToMainScreen, report)
        }
        BackHandler {
            backToMainScreen()
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text("Disconnected")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            backToMainScreen()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.outline_arrow_back_24),
                                contentDescription = null
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                Column(Modifier.padding(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(modifier = Modifier.weight(1f), onClick = {
                            showBugDialog = true
                        }) {
                            Icon(painterResource(R.drawable.baseline_bug_report_24), contentDescription = null)
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Report bug")
                        }
                        Button(modifier = Modifier.weight(1f), onClick = {
                            backToMainScreen()
                        }) {
                            Text("Exit")
                        }
                    }
                }
                Console(Modifier.weight(1f), consoleState)
            }
        }
    }
}