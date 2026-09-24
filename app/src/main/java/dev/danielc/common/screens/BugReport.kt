package dev.danielc.common.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.danielc.R
import dev.danielc.common.Http
import dev.danielc.common.ui.LargeCustomAlertDialog
import dev.danielc.common.ui.PreviewPixel9ProDark
import dev.danielc.fudge.BuildInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class BugReport(
    val version: String = BuildInfo.version,
    val app: String = BuildInfo.packageName,
    val os: String = BuildInfo.osVersion,
    val description: String = "",
    val verboseLog: String = "",
    val infoLog: String = "",
    val moduleInstanceInfo: String = "",
)

data class FeedbackReport(
    val version: String = BuildInfo.version,
    val app: String = BuildInfo.packageName,
    val os: String = BuildInfo.osVersion,
    val description: String = "",
)

private fun submitReport(report: BugReport): Http.Response {
    val payload = buildJsonObject {
        put("app", report.app)
        put("os", report.os)
        put("version", report.version)
        put("log", report.verboseLog.ifEmpty { "none" })
        put("infoLog", report.infoLog.ifEmpty { "none" })
        put("description", report.description)
        put("moduleInstanceInfo", report.moduleInstanceInfo)
    }

    return Http.post(
        "https://bugreporter.danielc.dev/report",
        payload.toString(),
        headers = mapOf(Pair("Content-Type", "application/json"), Pair("User-Agent", "submit-report/1.0"))
    )
}

fun submitFeedback(report: FeedbackReport): Http.Response {
    val payload = buildJsonObject {
        put("app", report.app)
        put("os", report.os)
        put("version", report.version)
        put("description", report.description)
    }

    if (report.description.isEmpty()) throw Exception("description can't be empty")

    return Http.post(
        "https://bugreporter.danielc.dev/feedback",
        payload.toString(),
        headers = mapOf(Pair("Content-Type", "application/json"), Pair("User-Agent", "submit-report/1.0"))
    )
}

@PreviewPixel9ProDark
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportDialog(dismiss: () -> Unit = {}, finished: () -> Unit = {}, report: BugReport = BugReport()) {
    var description by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    LargeCustomAlertDialog(
        onDismissRequest = { dismiss() },
        title = stringResource(R.string.report_a_bug_to_daniel),
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
                CoroutineScope(Dispatchers.IO).launch {
                    isWorking = true
                    try {
                        val rc = submitReport(report.copy(
                            description = description
                        ))
                        if (rc.isSuccessful) { CoroutineScope(Dispatchers.Main).launch { finished() } } else {
                            println(rc)
                            errorMessage = "Error code ${rc.code}"
                        }
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                    isWorking = false
                }
            }) { Text(stringResource(R.string.submit)) }
        },
        content = {
            Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(painterResource(R.drawable.outline_info_24), contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    ),
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description_optional)) }
                )

                Text(
                    stringResource(R.string.reporting_a_bug_sends_a_verbose),
                    style = MaterialTheme.typography.labelSmall
                )

                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}

@PreviewPixel9ProDark
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackDialog(dismiss: () -> Unit = {}, finished: () -> Unit = dismiss, report: FeedbackReport = FeedbackReport()) {
    var description by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    LargeCustomAlertDialog(
        onDismissRequest = { dismiss() },
        title = stringResource(R.string.report_feedback_to_daniel),
        icon = {
            if (isWorking) {
                CircularProgressIndicator(modifier = Modifier
                    .padding(end = 16.dp)
                    .size(24.dp))
            } else {
                Icon(
                    painter = painterResource(R.drawable.outline_feedback_24),
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
                CoroutineScope(Dispatchers.IO).launch {
                    isWorking = true
                    try {
                        val rc = submitFeedback(report.copy(
                            description = description
                        ))
                        if (rc.isSuccessful) { CoroutineScope(Dispatchers.Main).launch { finished() } } else {
                            println(rc)
                            errorMessage = "Error code ${rc.code}"
                        }
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                    isWorking = false
                }
            }) { Text(stringResource(R.string.submit)) }
        },
        content = {
            Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(painterResource(R.drawable.outline_info_24), contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    ),
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description)) },
                )

                Text(
                    stringResource(R.string.reporting_feedback_sends_a_verbose),
                    style = MaterialTheme.typography.labelSmall
                )

                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}
