package dev.danielc.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.danielc.R

@PreviewPixel9ProDark
@Composable
fun BackupDialog(dismiss: () -> Unit = {}) {
    val isWorking by remember { mutableStateOf(false) }
    LargeCustomAlertDialog(
        onDismissRequest = { dismiss() },
        title = "Download and Back up",
        icon = {
            if (isWorking) {
                CircularProgressIndicator(modifier = Modifier
                    .padding(end = 16.dp)
                    .size(24.dp))
            } else {
                Icon(
                    painter = painterResource(R.drawable.baseline_bug_report_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(24.dp)
                )
            }
        },
        actionButtons = {
            TextButton(onClick = {
            }) { Text("Start") }
        },
        content = {
            Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("WIP")
            }
        }
    )
}