package dev.danielc.common.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.danielc.common.FileHandle
import dev.danielc.common.ModuleInstance
import dev.danielc.common.ModuleInstanceRequest
import dev.danielc.common.ModuleManifest
import dev.danielc.common.Runtime
import dev.danielc.common.Screen
import dev.danielc.common.ui.DefaultNavHost
import dev.danielc.common.ui.DisconnectDialog
import dev.danielc.common.ui.DynamicScaffold
import dev.danielc.common.ui.DynamicScaffoldNavBarItem
import dev.danielc.common.ui.theme.FudgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class HomeState(
    val supportedNavBarScreenList: List<Screen> = listOf(Screen.DASHBOARD),
    val supportedMainScreenList: List<Screen> = listOf(Screen.DASHBOARD),
    val showDisconnectDialog: Boolean = false,
)

data class UiEvent(
    val type: UiEventType,
    val screen: Screen = Screen.NONE,
) {
    enum class UiEventType {
        SWITCH_SCREEN,
        SWITCH_NAV,
        GO_BACK_SCREEN,
        GO_BACK_NAV,
    }
}

class ModuleInstanceModel(manifest: ModuleManifest, request: ModuleInstanceRequest) : ViewModel() {
    override fun onCleared() {
        runBlocking {
            module.deregister()
        }
    }

    private val _homeState = MutableStateFlow(HomeState())
    val homeState = _homeState.asStateFlow()
    private val _uiEvents = MutableSharedFlow<UiEvent>(replay = 0)
    val uiEvents = _uiEvents.asSharedFlow()
    val initializationError = MutableStateFlow(false)

    var module: ModuleInstance = ModuleInstance(manifest, request, this)
    init {
        if (!initializationError.value) module.initThread()
    }

    fun showDisconnectDialog(v: Boolean) {
        _homeState.update { homeState ->
            homeState.copy(
                showDisconnectDialog = v
            )
        }
    }

    fun isScreenInNavBar(s: Screen): Boolean {
        return when (s) {
            Screen.DASHBOARD, Screen.FILE_GALLERY, Screen.LIVEVIEW, Screen.INTERVALOMETER, Screen.LIVE_FEED -> true
            else -> false
        }
    }

    fun sendUiEvent(type: UiEvent.UiEventType, screen: Screen = Screen.NONE) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent(type, screen))
        }
    }

    fun goToScreen(screen: Screen, isInNavBar: Boolean = false) {
        sendUiEvent(if (isInNavBar) UiEvent.UiEventType.SWITCH_NAV else UiEvent.UiEventType.SWITCH_SCREEN, screen)
    }

    fun back(isInNavBar: Boolean = false) {
        sendUiEvent(if (isInNavBar) UiEvent.UiEventType.GO_BACK_NAV else UiEvent.UiEventType.GO_BACK_SCREEN, Screen.NONE)
    }

    fun setSupportedScreen(s: Screen, v: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _homeState.update { currentState ->
                // TODO: refactor
                if (isScreenInNavBar(s)) {
                    if (!v && currentState.supportedNavBarScreenList.contains(s)) {
                        currentState.copy(supportedNavBarScreenList = currentState.supportedNavBarScreenList.filter { x -> x != s })
                    } else if (v && !currentState.supportedNavBarScreenList.contains(s)) {
                        currentState.copy(supportedNavBarScreenList = currentState.supportedNavBarScreenList + s)
                    } else {
                        currentState
                    }
                } else {
                    if (!v && currentState.supportedMainScreenList.contains(s)) {
                        currentState.copy(supportedMainScreenList = currentState.supportedMainScreenList.filter { x -> x != s })
                    } else if (v && !currentState.supportedMainScreenList.contains(s)) {
                        currentState.copy(supportedMainScreenList = currentState.supportedMainScreenList + s)
                    } else {
                        currentState
                    }
                }
            }
        }
    }
}

/**
 * Secondary Module instance bottom navigation graph (NavigationBar)
 */
@Composable
fun ModuleHomeScreen(module: ModuleInstance, hostNavController: NavController) {
    val model = module.homeModelView
    val navController = rememberNavController()
    val homeState by model.homeState.collectAsStateWithLifecycle()
    val dashboardState by module.dashboardModel.state.collectAsStateWithLifecycle()
    val navScreens = homeState.supportedNavBarScreenList.sortedBy { when (it) {
        Screen.DASHBOARD -> 0 // ensure dashboard is always first
        Screen.FILE_GALLERY -> 1
        else -> 2
    } }
    var screenSwitchProgress by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        module.homeModelView.uiEvents.collect { event ->
            if (event.type == UiEvent.UiEventType.SWITCH_NAV) {
                navController.navigate(route = event.screen.strId)
            } else if (event.type == UiEvent.UiEventType.GO_BACK_NAV) {
                navController.navigateUp()
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val haptic = LocalHapticFeedback.current

    // Progress bar for navbar
    // screenSwitchProgress?.let {
    //     LinearProgressIndicator(
    //         modifier = Modifier.fillMaxWidth(),
    //         color = MaterialTheme.colorScheme.primary,
    //         progress = { it.toFloat() / 100 }
    //     )
    // }

    FudgeTheme {
        DynamicScaffold(
            topBar = {},
            navBarItems = navScreens.map { screen ->
                DynamicScaffoldNavBarItem(
                    enabled = screenSwitchProgress == null,
                    selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.strId } == true,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        CoroutineScope(Dispatchers.IO).launch {
                            module.switchScreen(screen, isInNavBar = true, { job ->
                                screenSwitchProgress = job.progressBarValue
                            })
                            screenSwitchProgress = null
                        }
                    },
                    icon = {
                        Icon(
                            painter = painterResource(screen.getIcon()),
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(screen.getName())
                    }
                )
            },
        ) { innerPadding ->
            fun goBack() {
                val previousRoute = navController.previousBackStackEntry?.destination?.route
                if (previousRoute == null) {
                    model.showDisconnectDialog(true)
                } else {
                    val previous = Screen.fromStrId(previousRoute)!!
                    CoroutineScope(Dispatchers.IO).launch {
                        module.goBack(previous, isInNavBar = true, { job ->
                            screenSwitchProgress = job.progressBarValue
                        })
                        screenSwitchProgress = null
                    }
                }
            }
            NavHost(
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                navController = navController, startDestination = Screen.DASHBOARD.strId) {
                composable(Screen.DASHBOARD.strId) {
                    BackHandler { goBack() }
                    Dashboard(Modifier.padding(innerPadding), module.dashboardModel)
                }
                composable(Screen.FILE_GALLERY.strId) {
                    val galleryState by module.galleryViewModel.uiState.collectAsStateWithLifecycle()
                    BackHandler { goBack() }
                    Gallery(Modifier.padding(innerPadding), galleryState, requestLoad = { items ->
                        module.galleryViewModel.enqueueObjects(items)
                    }, onItemClick = { i ->
                        module.galleryViewModel.goToViewer(FileHandle(i, storageName = module.galleryViewModel.uiState.value.storageName))
                    }, setSortBy = { sort -> module.galleryViewModel.setSortBy(sort) })
                }
                composable(Screen.LIVEVIEW.strId) {
                    BackHandler { goBack() }
                    Liveview(Modifier.padding(innerPadding), module.liveviewWorker)
                }
                composable(Screen.INTERVALOMETER.strId) {
                    BackHandler { goBack() }
                    Intervalometer(Modifier.padding(innerPadding), module.intervalometerModel)
                }
                composable(Screen.LIVE_FEED.strId) {
                    BackHandler { goBack() }
                    LiveFeed(Modifier.padding(innerPadding), module.liveFeedModel)
                }
            }
            if (homeState.showDisconnectDialog) {
                DisconnectDialog(dashboardState.nameOfDevice ?: "Device", yes = {
                    module.forceDisconnect("Manual disconnect")
                    model.showDisconnectDialog(false)
                },
                no = {
                    model.showDisconnectDialog(false)
                })
            }
        }
    }
}

/**
 * Main navigation graph of module instance UI
 */
@Composable
fun ModuleInstanceNav(module: ModuleInstance, backToMainScreen: () -> Unit = {}) {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        module.homeModelView.uiEvents.collect { event ->
            if (event.type == UiEvent.UiEventType.SWITCH_SCREEN) {
                if (event.screen == Screen.DASHBOARD) {
                    navController.navigate("home") {
                        // discard entire nav graph
                        popUpTo(navController.graph.startDestinationId) {
                            inclusive = true
                        }
                    }
                } else if (event.screen == Screen.DISCONNECTED) {
                    navController.navigate("disconnected") {
                        popUpTo(navController.graph.startDestinationId) {
                            inclusive = true
                        }
                    }
                } else {
                    navController.navigate(event.screen.strId)
                }
            } else if (event.type == UiEvent.UiEventType.GO_BACK_SCREEN) {
                navController.navigateUp()
            }
        }
    }

    DefaultNavHost(navController = navController, startDestination = "connecting") {
        composable("connecting-secondary") {
            ConnectingScreen(back = {
                module.homeModelView.back(false)
            }, model = module.connectingModel)
        }
        composable("connecting") {
            val debugLogState by module.debugLogModel.uiState.collectAsStateWithLifecycle()
            val isInitError by module.homeModelView.initializationError.collectAsStateWithLifecycle()
            var hasCancelled by remember { mutableStateOf(false) }
            if (isInitError) {
                ModuleErrorScreen(backToMainScreen, debugLogState)
            } else {
                ConnectingScreen({
                    if (!hasCancelled) {
                        hasCancelled = true
                        CoroutineScope(Dispatchers.IO).launch {
                            module.debugLog("Cancelling...")
                            module.stopAllThreads()
                            CoroutineScope(Dispatchers.Main).launch {
                                backToMainScreen()
                            }
                        }
                    }
                }, model = module.connectingModel)
            }
        }
        composable("home") {
            ModuleHomeScreen(module, navController)
        }
        composable("disconnected") {
            val debugLogState by module.debugLogModel.uiState.collectAsStateWithLifecycle()
            val reason = "${module.disconnectReason ?: "(no reason)"} - (${Runtime.errorCodeToString(module.disconnectedErrorCode ?: 0)})"
            DisconnectedScreen(reason, backToMainScreen = backToMainScreen, consoleState = debugLogState, report = BugReport(
                moduleInstanceInfo = module.dumpStatus(),
                verboseLog = module.getVerboseLog(),
            ))
        }
        composable(Screen.FILE_VIEWER.strId) {
            val viewerState by module.viewerViewModel.viewerState.collectAsStateWithLifecycle()
            ViewerScreen(viewerState,
                switchTo = { i ->
                    module.galleryViewModel.goToViewer(FileHandle(i, viewerState?.handle?.storageName))
                }, close = {
                    if (module.galleryViewModel.viewerDownloadJob == null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            module.goBack(Screen.FILE_GALLERY, false)
                        }
                    } else {
                        module.galleryViewModel.downloader?.cleanupAfterCancel()
                        module.galleryViewModel.viewerDownloadJob?.let { module.cancelJob(it) }
                    }
                },
                cancel = {
                    module.galleryViewModel.downloader?.cleanupAfterCancel()
                    module.galleryViewModel.viewerDownloadJob?.let { module.cancelJob(it) }
                },
                save = {
                    module.galleryViewModel.downloader?.save()
                    module.viewerViewModel.setHasSaved(true)
                }
            )
        }
    }
}