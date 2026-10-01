package dev.danielc.common.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.danielc.R
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.fudge.BuildInfo

@Composable
fun DependencyList() {
    val uriHandler = LocalUriHandler.current
    data class Dep(
        val name: String,
        val url: String,
        val license: String,
    )

    val deps = listOf(
        Dep("libfuji", "https://github.com/petabyt/libfuji", "MIT License"),
        Dep("furble", "https://github.com/gkoh/furble", "MIT License"),
        Dep("libjpeg-turbo", "https://github.com/libjpeg-turbo/libjpeg-turbo", "IJG License, Modified (3-clause) BSD License"),
        Dep("rtsp-client-android", "https://github.com/alexeyvasilyev/rtsp-client-android", "Apache License 2.0"),
        Dep("quickjs", "https://github.com/bellard/quickjs", "MIT license"),
        Dep("Webassembly Micro Runtime (WAMR)", "https://github.com/bytecodealliance/wasm-micro-runtime", "Apache 2.0 License"),
        Dep("app icon courtesy of Sincerely Media", "https://unsplash.com/photos/a-stack-of-three-pieces-of-food-sitting-on-top-of-a-table-D1zuILDUNzc", "Upsplash License"),
        // TODO: Automate adding android deps
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(deps) { dep ->
            Surface(Modifier.fillMaxWidth().clickable(onClick = {
                uriHandler.openUri(dep.url)
            }), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column {
                        Text(dep.name)
                        Text(dep.license)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
//@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun InfoScreen(navController: NavHostController = rememberNavController()) {
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text("Info")
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
            Column(Modifier.padding(innerPadding).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text("Copyright (C) ${stringResource(R.string.app_name)} 2026, license TBD")
                    Text("Compile date: ${BuildInfo.time}")
                }
                DependencyList()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun AboutScreen(navController: NavHostController = rememberNavController()) {
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text("About")
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
            Column(Modifier.padding(innerPadding).padding(10.dp).verticalScroll(rememberScrollState())) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                    Text("Connect to cameras, earbuds, and more", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.outline_photo_camera_24), contentDescription = null, modifier = Modifier.size(70.dp))
                        Icon(painterResource(R.drawable.outline_earbuds_2_24), contentDescription = null, modifier = Modifier.size(70.dp))
                        Icon(painterResource(R.drawable.outline_devices_other_24), contentDescription = null, modifier = Modifier.size(70.dp))
                    }
                    Text("""
                        An alternative to the WiFi/Bluetooth app that came with your device.
                        """.trimIndent(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
                    //Text("Support for more devices coming soon", style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider(Modifier.padding(10.dp))
                    Text("Give big brother the middle finger", style = MaterialTheme.typography.titleMedium)
                    Text("""
                        Your dashcam doesn't need your email address and full name. Big tech is tracking you and selling your data to advertisers.
                    """.trimIndent(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
                    HorizontalDivider(Modifier.padding(10.dp))
                    Text("True ownership means zero vendor lock-in", style = MaterialTheme.typography.titleMedium)
                    Text("""
                        Stop being locked into big tech's walled garden. You shouldn't be forced to use their app, make an account with them, or sign up to their subscriptions.

                        You own your stuff. It shouldn't feel like you're renting it.
                    """.trimIndent(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
                    HorizontalDivider(Modifier.padding(10.dp))
                }
            }
        }
    }
}