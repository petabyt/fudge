/// Main app start screen
package dev.danielc.common.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.danielc.R
import dev.danielc.common.ConnectableDevice
import dev.danielc.common.Device
import dev.danielc.common.ModuleInstanceRequest
import dev.danielc.common.ModuleManifest
import dev.danielc.common.Runtime
import dev.danielc.common.SavedDeviceEntity
import dev.danielc.common.ui.ClickableCard
import dev.danielc.common.ui.DefaultNavHost
import dev.danielc.common.ui.DeleteDialog
import dev.danielc.common.ui.DynamicScaffold
import dev.danielc.common.ui.DynamicScaffoldNavBarItem
import dev.danielc.common.ui.ManifestList
import dev.danielc.common.ui.PreviewPixel9ProDark
import dev.danielc.common.ui.PreviewTabletDark
import dev.danielc.common.ui.TargetCard
import dev.danielc.common.ui.dummyManifestList
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.fudge.AndroidRuntime
import dev.danielc.fudge.BuildInfo
import dev.danielc.fudge.FileLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

private val dummyConnectableDeviceList: List<ConnectableDevice> = listOf(
    ConnectableDevice("CMF Buds Pro 2", manifest = dummyManifestList[0], target = dummyManifestList[0].targets[0]),
)

private val dummyOptions = listOf(
    ModuleManifest.SetupOption("wifi", "WiFi"),
    ModuleManifest.SetupOption("bluetooth", "Bluetooth"),
    ModuleManifest.SetupOption("local", "Local Network (Foo Bar Foo Bar)"),
)

@OptIn(ExperimentalMaterial3Api::class)
//@Preview(showBackground = true, device = "id:pixel_9", uiMode = 32)
@Composable
fun InstanceSetup(options: List<ModuleManifest.SetupOption> = dummyOptions, onClick: (ModuleManifest.SetupOption) -> Unit = {}, back: () -> Unit = {}) {
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.choose_a_mode_to_proceed))
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            back()
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
            LazyColumn(Modifier
                .padding(innerPadding)
                .padding(10.dp)
                .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.Top)) {
                items(options) { e ->
                    // TODO: Interesting colors
                    val colors = when (e.name) {
                        "bluetooth" -> Pair(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
                        else -> Pair(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    }
                    Surface(
                        onClick = {
                            onClick(e)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = colors.first,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                when (e.name) {
                                    "wifi" -> painterResource(R.drawable.outline_wifi_24)
                                    "bluetooth" -> painterResource(R.drawable.outline_bluetooth_24)
                                    "usb" -> painterResource(R.drawable.outline_usb_24)
                                    else -> painterResource(R.drawable.outline_general_device_24)
                                },
                                contentDescription = null,
                                tint = colors.second
                            )
                            Text(e.title, color = colors.second)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectableDeviceCard(dev: ConnectableDevice, clicked: (String?) -> Unit = {}) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .combinedClickable(
            onClick = {
                clicked(null)
            },
            onLongClick = {
                clicked(null)
            }
        )
        .padding(16.dp)
        .alpha(1f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        painterResource(dev.target.deviceId.getIcon()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dev.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun SavedDeviceCard(target: ModuleManifest.Target, transport: ModuleManifest.Transport?, dev: SavedDeviceEntity, isNearby: Boolean, clicked: () -> Unit = {}, longClick: () -> Unit) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .combinedClickable(
            onClick = { clicked() },
            onLongClick = { longClick() }
        )
        .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(target.deviceId.getIcon()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dev.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    if (transport != null) {
                        Icon(painterResource(when (transport) {
                            ModuleManifest.Transport.BLUETOOTH -> R.drawable.outline_bluetooth_24
                            else -> R.drawable.outline_wifi_24
                        }), contentDescription = null)
                    }
                    if (isNearby) {
                        Box(Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.Green))
                        Text(
                            text = stringResource(R.string.nearby),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                }
                val diff = System.currentTimeMillis().milliseconds.toLong(DurationUnit.MILLISECONDS) - dev.lastSeenTimestamp
                Text(
                    stringResource(R.string.last_connected_hours_ago, diff / 1000 / 60 / 60))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainConnectScreen(navController: NavController, modifier: Modifier = Modifier, clicked: (ModuleInstanceRequest) -> Unit) {
    var isRefreshing by remember { mutableStateOf(false) }
    val settingsValue by Runtime.appSettings.collectAsStateWithLifecycle()
    var selectedSavedDevice by remember { mutableStateOf<SavedDeviceEntity?>(null) }
    val savedDevices by Runtime.savedDevices.collectAsStateWithLifecycle()
    val deviceList by Runtime.connectableDevices.collectAsStateWithLifecycle()
    val manifestList by Runtime.moduleManifests.collectAsStateWithLifecycle()
    val nearbyList by Runtime.nearbyDevicesFlow.collectAsStateWithLifecycle()
    selectedSavedDevice?.let {
        DeleteDialog(it.name, yes = {
            Runtime.deleteSavedDeviceEntity(it)
            selectedSavedDevice = null
        }, no = { selectedSavedDevice = null })
    }
    PullToRefreshBox(
        state = rememberPullToRefreshState(),
        isRefreshing = isRefreshing,
        onRefresh = {
            CoroutineScope(Dispatchers.IO).launch {
                isRefreshing = true
                Runtime.refreshManifests()
                isRefreshing = false
            }
        },
        modifier = modifier
    ) {
        Column(Modifier
            .fillMaxSize()
            .padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (settingsValue.showWelcomeDialog) {
                    item {
                        ClickableCard(color = MaterialTheme.colorScheme.surfaceContainerHighest, click = {
                            navController.navigate("about")
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.fantasyfudge_pre_release), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.introduction_and_what_this_app_does))
                                }
                                IconButton(onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        AndroidRuntime.updateAppSetting({it.copy(showWelcomeDialog = false)})
                                    }
                                }, modifier = Modifier.fillMaxHeight()) {
                                    Icon(
                                        painterResource(R.drawable.outline_close_24),
                                        contentDescription = null
                                    )
                                }
                            }
                        }
                    }
                }
                if (deviceList.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.bonded_devices))
                    }
                }
                items(deviceList, key = { isRefreshing }) { dev ->
                    ConnectableDeviceCard(dev, clicked = {
                        clicked(ModuleInstanceRequest(
                            dev.manifest.name,
                            dev.manifest.targets.indexOf(dev.target),
                            deviceMacAddress = dev.macAddress,
                        ))
                    })
                }
                if (savedDevices.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.connect_again))
                    }
                }
                items(savedDevices) { dev ->
                    val manifest = Runtime.getManifestFromName(dev.manifestName)
                    val target = manifest?.targets?.getOrNull(dev.targetIndex)
                    val setupOption = target?.setupOptions?.find { it.name == dev.setupOption }
                    val isNearby = nearbyList.contains(dev.bluetoothMacAddress)
                    if (manifest != null && target != null) {
                        SavedDeviceCard(target, setupOption?.transport, dev, isNearby, clicked = {
                            clicked(ModuleInstanceRequest(
                                manifest.name,
                                dev.targetIndex,
                                savedDeviceUniqueId = dev.uniqueIdentifier,
                                chosenSetupOption = dev.setupOption,
                            ))
                        }, longClick = {
                            selectedSavedDevice = dev
                        })
                    }
                }
                item {
                    Text(stringResource(R.string.select_a_type_of_device_to_connect_to))
                }
                var targets = mutableListOf<Pair<ModuleManifest.Target, ModuleManifest>>()
                for (manifest in manifestList) {
                    for (target in manifest.targets) {
                        targets += Pair(target, manifest)
                    }
                }
                targets = targets.sortedBy {
                    if (it.second.name == "dummymod") 0 else
                    when (it.first.deviceId) {
                        Device.PROFESSIONAL_CAMERA, Device.DASHCAM -> 1
                        else -> 2
                    }
                }.toMutableList()
                items(targets) { pair ->
                    TargetCard(pair.first, pair.second, clicked = {
                        clicked(ModuleInstanceRequest(pair.second.name, pair.second.targets.indexOf(pair.first)))
                    })
                }
            }
        }
    }
}

@Serializable
private data class ManifestInfo(
    val name: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(navController: NavHostController = rememberNavController()) {
    val subNavController = rememberNavController()
    val haptic = LocalHapticFeedback.current
    val navBackStackEntry by subNavController.currentBackStackEntryAsState()
    var showFeedbackDialog by rememberSaveable { mutableStateOf(false) }

    data class NavItem(
        val icon: Int,
        val text: String,
        val route: String,
    )
    val items = listOf(
        NavItem(R.drawable.outline_devices_other_24, stringResource(R.string.connect), "connect"),
        NavItem(R.drawable.outline_photo_library_24, stringResource(R.string.downloads), "local-gallery"),
        NavItem(R.drawable.outline_deployed_code_24, stringResource(R.string.modules), "modules"),
    )

    return FudgeTheme {
        if (showFeedbackDialog) {
            FeedbackDialog({ showFeedbackDialog = false })
        }
        DynamicScaffold(
            noTopBar = navBackStackEntry?.destination?.route == "local-gallery",
            topBar = {
                if (navBackStackEntry?.destination?.route != "local-gallery") {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(),
                        title = {
                            if (BuildInfo.isDebug) {
                                Text(stringResource(R.string.fantasyfudge_debug))
                            } else if (BuildInfo.isNightly) {
                                Text(stringResource(R.string.fantasyfudge_nightly))
                            } else {
                                Text(stringResource(R.string.app_name))
                            }
                        },
                        navigationIcon = {
                            Image(
                                painterResource(R.drawable.icon),
                                contentDescription = null,
                                Modifier
                                    .size(40.dp)
                                    .padding(5.dp)
                                    .clip(CircleShape)
                            )
                        },
                        actions = {
                            IconButton(onClick = {
                                showFeedbackDialog = true
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.outline_feedback_24),
                                    contentDescription = null
                                )
                            }
                            IconButton(onClick = {
                                navController.navigate("settings")
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_settings_24),
                                    contentDescription = null
                                )
                            }
                        }
                    )
                }
            },
            navBarItems = items.map { item ->
                DynamicScaffoldNavBarItem(
                    icon = {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(item.text)
                    },
                    selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == item.route } == true,
                    onClick = {
                        subNavController.navigate(item.route) {
                            launchSingleTop = true
                            restoreState = false
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    }
                )
            },
//            bottomBar = {
//                NavigationBar {
//                    items.forEach { item ->
//                        NavigationBarItem(
//                            icon = {
//                                Icon(
//                                    painter = painterResource(item.icon),
//                                    contentDescription = null
//                                )
//                            },
//                            label = {
//                                Text(item.text)
//                            },
//                            selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == item.route } == true,
//                            onClick = {
//                                subNavController.navigate(item.route) {
//                                    launchSingleTop = true
//                                    restoreState = false
//                                }
//                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
//                            }
//                        )
//                    }
//                }
//            }
        ) { innerPadding ->
            NavHost(
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                modifier = Modifier.padding(innerPadding),
                navController = subNavController, startDestination = "connect", route = "route"
            ) {
                composable("connect") {
                    MainConnectScreen(
                        navController,
                        clicked = { request ->
                            navController.navigate(request)
                        }
                    )
                }
                composable("modules") {
                    val manifestList by Runtime.moduleManifests.collectAsStateWithLifecycle()
                    ManifestList(manifestList = manifestList, itemClicked = { manifest ->
                        navController.navigate(ManifestInfo(manifest.name))
                    })
                }
                composable("local-gallery") {
                    LaunchedEffect(Unit) {
                        FileLayer.requestLegacyPermissions()
                    }
                    LocalGallery(onItemClick = { i ->
                        navController.navigate("local-viewer")
                        CoroutineScope(Dispatchers.IO).launch {
                            Runtime.localGalleryViewModel.itemClicked(GalleryObjectReference(i))
                        }
                    }, Modifier, Runtime.localGalleryViewModel)
                }
            }
        }
    }
}

@Composable
@PreviewPixel9ProDark
fun PreviewMainScreen() {
    Runtime.savedDevices = MutableStateFlow(listOf(SavedDeviceEntity(
        uniqueIdentifier = "ASD",
        name = "Fujifilm X-T30",
        manifestName = "libfuji",
        targetIndex = 0,
        setupOption = "bluetooth",
        auxillaryData = null,
    )))
    Runtime.moduleManifests = MutableStateFlow(dummyManifestList)
    MainScreen()
}

@Composable
fun MainNav(navController: NavHostController) {
    LaunchedEffect(Unit) {
        Runtime.trimMemorySignal.collect {
            Runtime.localGalleryViewModel.onTrimMemory()
        }
    }

    DefaultNavHost(navController = navController, startDestination = "home") {
        composable("home") {
            MainScreen(navController)
        }
        composable("help") {
            HelpScreen(navController)
        }
        composable("console") {
            val state by Runtime.mainLog.uiState.collectAsStateWithLifecycle()
            ConsoleScreen({
                navController.navigateUp()
            }, state, stringResource(R.string.debug_console))
        }
        composable("about") {
            AboutScreen(navController)
        }
        composable("info") {
            InfoScreen(navController)
        }
        composable("settings") {
            SettingsScreen(navController)
        }
        composable<ManifestInfo> { backStackEntry ->
            val info = backStackEntry.toRoute<ManifestInfo>()
            val manifest = Runtime.getManifestFromName(info.name)!!
            ManifestInfoScreen(manifest, close = {
                navController.navigateUp()
            })
        }
        composable<ModuleInstanceRequest> { backStackEntry ->
            val request = backStackEntry.toRoute<ModuleInstanceRequest>()
            val manifest = Runtime.getManifestFromName(request.manifestName)
            if (manifest == null) {
                throw Error("${request.manifestName} not found in manifest list")
            }
            val target = manifest.targets[request.targetIndex]
            if (target.setupOptions.isNotEmpty() && request.chosenSetupOption == null) {
                InstanceSetup(target.setupOptions, onClick = { opt ->
                    navController.navigate(request.copy(chosenSetupOption = opt.name))
                }, back = {
                    navController.navigateUp()
                })
            } else {
                val model = viewModel(initializer = { ModuleInstanceModel(manifest, request) })
                ModuleInstanceNav(model.module, backToMainScreen = {
                    navController.popBackStack(route = "home", inclusive = false)
                })
            }
        }
        composable("local-viewer") {
            val state by Runtime.localGalleryViewModel.viewer.viewerState.collectAsStateWithLifecycle()
            ViewerScreen(state, { i ->
                CoroutineScope(Dispatchers.IO).launch {
                    Runtime.localGalleryViewModel.itemClicked(GalleryObjectReference(i))
                }
            }, close = {
                Runtime.localGalleryViewModel.viewer.clear()
                navController.popBackStack()
            }, share = {
                Runtime.localGalleryViewModel.viewer.viewerState.value?.handle?.index?.let {
                    Runtime.localGalleryViewModel.share(it)
                }
            })
        }
    }
}