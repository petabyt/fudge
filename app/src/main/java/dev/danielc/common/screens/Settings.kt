package dev.danielc.common.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import dev.danielc.R
import dev.danielc.common.Runtime
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.fudge.AndroidRuntime
import dev.danielc.fudge.FileLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ClickableCard(text: String, icon: Painter, onClick: () -> Unit = {}) {
    Card(Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null)
            Text(text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun SettingsScreen(navController: NavController = rememberNavController()) {
    val uriHandler = LocalUriHandler.current
    val settingsValue by Runtime.appSettings.collectAsStateWithLifecycle()
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.settings))
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            navController.navigateUp()
                        }) {
                            Icon(painterResource(R.drawable.outline_arrow_back_24), contentDescription = null)
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding)) {
                Column(Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(painterResource(R.drawable.baseline_download_24), contentDescription = null)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text
                        ),
                        value = settingsValue.downloadsLocation,
                        onValueChange = {
                            CoroutineScope(Dispatchers.IO).launch {
                                AndroidRuntime.getDatabase().settingsDao().save(settingsValue.copy(downloadsLocation = it))
                            }
                        },
                        label = { Text(stringResource(R.string.downloads_location)) }
                    )
                    Row(Modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.store_downloads_in_per_device_subfolders))
                        Switch(settingsValue.perDeviceSubFolder, onCheckedChange = {
                            CoroutineScope(Dispatchers.IO).launch {
                                AndroidRuntime.getDatabase().settingsDao().save(settingsValue.copy(perDeviceSubFolder = it))
                            }
                        })
                    }
                    HorizontalDivider()
                    ClickableCard(stringResource(R.string.info), painterResource(R.drawable.outline_info_24)) {
                        navController.navigate("info")
                    }
//                    ClickableCard("Help", painterResource(R.drawable.baseline_help_24)) {
//                        navController.navigate("help")
//                    }
//                    ClickableCard("Send feedback", painterResource(R.drawable.baseline_bug_report_24)) {
//                        uriHandler.openUri("https://google.com/")
//                    }
                    ClickableCard(stringResource(R.string.debug_console), painterResource(R.drawable.baseline_terminal_24)) {
                        navController.navigate("console")
                    }
                    HorizontalDivider()
                    Button(onClick = {
                        FileLayer.requestExternalImagesPermission()
                    }) {
                        Text(stringResource(R.string.grant_access_to_all_local_images_optional))
                    }
                    Button(onClick = {
                        AndroidRuntime.resetDatabase()
                    }) {
                        Text(stringResource(R.string.reset_all_settings))
                    }
                }
            }
        }
    }
}