package dev.danielc.common.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.danielc.R
import dev.danielc.common.ModuleManifest
import dev.danielc.common.ui.dummyManifestList
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.common.ui.theme.errorButtonColors

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun ManifestInfoScreen(manifest: ModuleManifest = dummyManifestList[0], close: () -> Unit = {}) {
    val uriHandler = LocalUriHandler.current
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text(stringResource(R.string.manifest_info))
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            close()
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
            Box(Modifier.padding(innerPadding)) {
                Column(Modifier
                    .fillMaxSize()
                    .padding(10.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(manifest.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Button(onClick = {}, enabled = false) {
                            Text(stringResource(R.string.update))
                        }
                        Button(onClick = {}, colors = errorButtonColors(), enabled = false) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                    if (manifest.author != null) Text("Author: ${manifest.author}")
                    if (manifest.description != null) Text("Description: ${manifest.description}")
                    Text("Version: ${manifest.version}")
                    if (manifest.targets.isNotEmpty()) {
                        Text("Targets:")
                        Column(Modifier.padding(horizontal = 5.dp)) {
                            for (e in manifest.targets) {
                                Text("Company: ${e.company}")
                                Text("Type: ${e.deviceId.id}")
                                if (e.products.isNotEmpty()) {
                                    Text("Products: " + e.products.joinToString(", "), overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (manifest.website != null) {
                            Button(modifier = Modifier.weight(1f), onClick = {
                                uriHandler.openUri(manifest.website)
                            }) {
                                Text(stringResource(R.string.visit_website))
                            }
                        }
                        Button(modifier = Modifier.weight(1f), onClick = {}, enabled = false) {
                            Text(stringResource(R.string.report_bugs))
                        }
                    }
                }
            }
        }
    }
}