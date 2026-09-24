package dev.danielc.common.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.danielc.R
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.Device
import dev.danielc.common.ModuleManifest
import dev.danielc.common.ModuleProperty
import dev.danielc.common.StorageInfo
import dev.danielc.common.Widget
import dev.danielc.common.longToFileSize
import dev.danielc.common.ui.DynamicScaffold
import dev.danielc.common.ui.DynamicScaffoldNavBarItem
import dev.danielc.common.ui.IntGridGraph
import dev.danielc.common.ui.PreviewPixel9ProDark
import dev.danielc.common.ui.PreviewTabletDark
import dev.danielc.common.ui.theme.FudgeRippleConfig
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.common.ui.theme.errorButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardState(
    val widgets: List<Widget> = emptyList(),
    val batteryLevelMain: Int? = null,
    val batteryLevelLeft: Int? = null,
    val batteryLevelRight: Int? = null,
    val nameOfDevice: String? = null,
    val temperature: Int? = null,
    val humidity: Int? = null,
    val firmwareVersion: String? = null,
    val connectionType: ModuleManifest.Transport? = null,
    val isSaved: Boolean = false,
)

open class DashboardModel(
    val manifest: ModuleManifest,
    initialState: DashboardState? = null,
    val storageDevices: StateFlow<List<StorageInfo>>,
): BackgroundViewModel() {
    private val _state = MutableStateFlow(initialState ?: DashboardState())
    val state = _state.asStateFlow()

    open fun propChanged(pane: Widget) {}
    open fun disconnect() {}
    open fun runCommand(line: String) {}
    open fun save() {}
    open fun onStorageDeviceClicked(name: String) {}

    fun setSaved() { _state.update { it.copy(isSaved = true) } }
    fun setProperty(type: ModuleProperty, value: String) {
        scope.launch(Dispatchers.IO) {
            _state.update { currentState ->
                when (type) {
                    ModuleProperty.NAME_OF_DEVICE -> currentState.copy(nameOfDevice = value)
                    ModuleProperty.FIRMWARE_VERSION -> currentState.copy(firmwareVersion = value)
                    else -> currentState
                }
            }
        }
    }
    fun setProperty(type: ModuleProperty, value: Int) {
        scope.launch(Dispatchers.IO) {
            _state.update { currentState ->
                when (type) {
                    ModuleProperty.TEMPERATURE -> currentState.copy(temperature = value)
                    ModuleProperty.HUMIDITY -> currentState.copy(humidity = value)
                    ModuleProperty.BATTERY_LEFT -> currentState.copy(batteryLevelLeft = value)
                    ModuleProperty.BATTERY_MAIN -> currentState.copy(batteryLevelMain = value)
                    ModuleProperty.BATTERY_RIGHT -> currentState.copy(batteryLevelRight = value)
                    else -> currentState
                }
            }
        }
    }
    fun setDashboardPane(pane: Widget) {
        scope.launch(Dispatchers.IO) {
            _state.update { currentState ->
                // Incorrect?
                if (currentState.widgets.find { it.args.name == pane.args.name } == null) {
                    currentState.copy(widgets = currentState.widgets + pane)
                } else {
                    currentState
                }
            }
        }
    }
    fun updateSettingPane(pane: Widget) {
        scope.launch(Dispatchers.IO) {
            _state.update { currentState ->
                val index = currentState.widgets.find { it.args.name == pane.args.name }
                val list = currentState.widgets.toMutableList()
                if (index != null) {
                    list[currentState.widgets.indexOf(index)] = pane
                    currentState.copy(widgets = list)
                } else {
                    currentState
                }
            }
            propChanged(pane)
        }
    }
}

data class PaneState(
    val color: PaneState.Color = PaneState.Color.SECONDARY,
    val text: String? = null,
    val icon: Int? = null,
    val onClick: () -> Unit = {},
    val content: (@Composable () -> Unit)? = null,
    val fillMaxWidth: Boolean = false,
) {
    enum class Color {
        PRIMARY,
        SECONDARY,
        TERTIARY,
        ERROR,
        NEUTRAL,
    }
}

private fun cameraState(): DashboardModel {
    val manifest = ModuleManifest(name = "Fujifilm", targets = listOf(ModuleManifest.Target(deviceId = Device.PROFESSIONAL_CAMERA)))
    return DashboardModel(manifest, DashboardState(
        nameOfDevice = "Fujifilm X-T5",
        batteryLevelMain = 48,
        firmwareVersion = "4.31",
    ), storageDevices = MutableStateFlow(listOf(StorageInfo(
        name = "Card 1",
        nFiles = 320,
        sizeBytes = 64000000000L,
        usedBytes = 30000000000L
    ), StorageInfo(
        name = "Card 2",
        nFiles = 67,
        sizeBytes = 128000000000L,
        usedBytes = 30000000000L
    ))).asStateFlow())
}

@PreviewTabletDark
@Composable
private fun PreviewDashboardCamera() {
    var state by remember { mutableStateOf(cameraState()) }
    return FudgeTheme {
        DynamicScaffold(topBar = {}, navBarItems = listOf(
            DynamicScaffoldNavBarItem(label = { Text("Dashboard") }, onClick = {}, icon = { Icon(painterResource(R.drawable.outline_home_24), contentDescription = null) }, selected = true),
            DynamicScaffoldNavBarItem(label = { Text("Gallery") }, onClick = {}, icon = { Icon(painterResource(R.drawable.outline_photo_library_24), contentDescription = null) }, selected = false),
            DynamicScaffoldNavBarItem(label = { Text("Liveview") }, onClick = {}, icon = { Icon(painterResource(R.drawable.outline_smart_display_24), contentDescription = null) }, selected = false),
        )) { innerPadding ->
            Dashboard(Modifier.padding(innerPadding), state)
        }
    }
}

private fun budsState(): DashboardModel {
    val manifest = ModuleManifest(name = "CMF Nothing", description = "Supports", targets = listOf(ModuleManifest.Target(deviceId = Device.EARBUDS)))
    return DashboardModel(manifest, DashboardState(
        nameOfDevice = "CMF Buds Pro 2",
        firmwareVersion = "5.0",
        batteryLevelMain = 50,
        batteryLevelLeft = 20,
        batteryLevelRight = 70,
        widgets = listOf(
            Widget.BooleanSetting(
                Widget.Properties("ll", "Low Lag Mode"),
                value = true
            ),
            Widget.BooleanSetting(
                Widget.Properties("be", "Bass Enhancement"),
                value = false
            ),
//            Widget.IntSetting(
//               Widget.Properties("st", "Something"),
//                value = 123
//            ),
            Widget.DropdownSetting(
                Widget.Properties("temp", "Noise Cancelling"),
                index = 0,
                options = listOf("Adaptive")
            ),
//            Widget.Graph(
//                Widget.Properties("temp", "Graph"),
//                points = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 8, 7, 6, 5, 4, 5, 7, 8, 5)
//            ),
        )
    ), storageDevices = MutableStateFlow(emptyList<StorageInfo>()).asStateFlow())
}

@PreviewPixel9ProDark
@Composable
private fun PreviewDashboardBuds() {
    var state by remember { mutableStateOf(budsState()) }
    return FudgeTheme {
        Scaffold(
            content = { innerPadding ->
                Dashboard(Modifier.padding(innerPadding), state)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DashboardPane(modifier: Modifier = Modifier, bg: Color, fg: Color, content: @Composable () -> Unit, onClick: () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides FudgeRippleConfig(fg)) {
        Box(modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .indication(
                indication = ripple(),
                interactionSource = remember { MutableInteractionSource() }
            )
        ) {
            content()
        }
    }
}

private fun getBatteryStatusIcon(percent: Int): Int {
    return when (percent) {
        0 -> R.drawable.outline_battery_android_0_24
        in 1..13 -> R.drawable.outline_battery_android_frame_1_24
        in 14..44 -> R.drawable.outline_battery_android_frame_2_24
        in 45..58 -> R.drawable.outline_battery_android_frame_3_24
        in 59..72 -> R.drawable.outline_battery_android_frame_4_24
        in 73..86 -> R.drawable.outline_battery_android_frame_5_24
        in 87..99 -> R.drawable.outline_battery_android_frame_6_24
        100 -> R.drawable.outline_battery_android_frame_full_24
        else -> R.drawable.outline_battery_android_0_24
    }
}

data class PaneBatteryStatus(
    val name: String,
    val percent: Int,
)

fun BatteryListPane(batteries: List<PaneBatteryStatus>): PaneState {
    return PaneState(PaneState.Color.NEUTRAL, content = {
        Text("Battery Status", color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth()) {
            for (b in batteries) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(getBatteryStatusIcon(b.percent)), contentDescription = null)
                    Text("${b.percent}%", style = MaterialTheme.typography.labelSmall)
                    Text(b.name, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                }
            }
        }
    })
}

@Composable
fun SettingsDialog(model: DashboardModel, close: () -> Unit = {}) {
    Dialog(onDismissRequest = {
        close()
    }) {
        Card(modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f), shape = RoundedCornerShape(16.dp),) {
            Column(Modifier
                .fillMaxSize()
                .padding(10.dp)) {
                var terminalCommand by remember { mutableStateOf("help") }
                TextField(
                    leadingIcon = {
                        Icon(painterResource(R.drawable.baseline_terminal_24), contentDescription = null)
                    },
                    value = terminalCommand,
                    onValueChange = { terminalCommand = it },
                    label = { Text(stringResource(R.string.terminal_command)) }
                )
                Button(onClick = {model.runCommand(terminalCommand)}) {
                    Text(stringResource(R.string.execute))
                }
            }
        }
    }
}

@Composable
fun DropdownDialog(close: () -> Unit = {}, dropdownSetting: Widget.DropdownSetting, onSelect: (Int) -> Unit = {}) {
    Dialog(onDismissRequest = {
        close()
    }) {
        Card(modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)) {
            Text(dropdownSetting.args.title, modifier = Modifier.padding(10.dp))
            LazyColumn {
                itemsIndexed(dropdownSetting.options) { i, item ->
                    Box(Modifier
                        .fillMaxWidth()
                        .clickable(onClick = {
                            onSelect(i)
                        })
                        .background(
                            if (i == dropdownSetting.index) MaterialTheme.colorScheme.surfaceContainer.copy(
                                0.5f
                            ) else MaterialTheme.colorScheme.surfaceContainer
                        )) {
                        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewPane(state: DashboardState, model: DashboardModel, showSettings: () -> Unit) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.nameOfDevice?.let { name ->
                    if (!model.manifest.targets.isEmpty())
                        Icon(
                            painter = painterResource(model.manifest.targets[0].deviceId.getIcon()),
                            contentDescription = null
                        )
                    Text(
                        name,
                        fontSize = 25.sp,
                        modifier = Modifier.padding(5.dp)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.End)) {
                    state.batteryLevelMain?.let {
                        Icon(
                            painter = painterResource(getBatteryStatusIcon(it)),
                            contentDescription = null,
                        )
                    }
                    val icon = when (state.connectionType) {
                        ModuleManifest.Transport.BLUETOOTH -> R.drawable.outline_bluetooth_24
                        ModuleManifest.Transport.USB -> R.drawable.baseline_usb_24
                        else -> R.drawable.outline_wifi_24
                    }
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                    )
                }
            }
            if (state.firmwareVersion != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painter = painterResource(R.drawable.outline_developer_board_24), contentDescription = null)
                    Text(stringResource(R.string.firmware_version, state.firmwareVersion))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = showSettings) {
                    Icon(painterResource(R.drawable.baseline_settings_24), contentDescription = null)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { model.save() }, modifier = Modifier, enabled = !state.isSaved) {
                    Icon(painterResource(R.drawable.outline_save_24), contentDescription = null)
                    Spacer(Modifier.width(2.dp))
                    Text(if (state.isSaved) stringResource(R.string.saved) else stringResource(R.string.save))
                }
                Button(onClick = { model.disconnect() }, modifier = Modifier, colors = errorButtonColors()) {
                    Icon(painterResource(R.drawable.outline_close_24), contentDescription = null)
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.disconnect))
                }
            }
        }
    }
}

@Composable
private fun CardPane(e: StorageInfo, model: DashboardModel) {
    Box(Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable(onClick = { model.onStorageDeviceClicked(e.name) })
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.isLiveFeedMedium) {
                    Icon(painterResource(R.drawable.outline_sim_card_download_24), contentDescription = null)
                } else {
                    Icon(painterResource(R.drawable.outline_sd_card_24), contentDescription = null)
                }
                Text(e.name)
                Spacer(Modifier.weight(1f))
                if (e.sizeBytes != null && e.usedBytes != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { e.usedBytes.toFloat() / e.sizeBytes })
                        val percent = ((e.usedBytes.toFloat() / e.sizeBytes) * 100).toInt()
                        Text(
                            "${percent}% of ${longToFileSize(e.sizeBytes)} used",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (e.currentStatus != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.currentStatus, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
                        if (e.currentProgress != null) LinearProgressIndicator(progress = { e.currentProgress.toFloat() / 100 })
                    }
                }
            }
            Text("${e.nFiles} files")
            //Text("7 downloaded")
        }
    }
}

@Composable
private fun RenderPanes(panes: List<PaneState>) {
    LazyVerticalStaggeredGrid(
        modifier = Modifier.fillMaxSize(),
        columns = StaggeredGridCells.Adaptive(160.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = {
            items(panes, span = {
                if (it.fillMaxWidth) {
                    StaggeredGridItemSpan.FullLine
                } else {
                    StaggeredGridItemSpan.SingleLane
                }
            }) { pane ->
                var bg: Color
                var fg: Color
                when (pane.color) {
                    PaneState.Color.PRIMARY -> {
                        bg = MaterialTheme.colorScheme.primary
                        fg = MaterialTheme.colorScheme.onPrimary
                    }
                    PaneState.Color.SECONDARY -> {
                        bg = MaterialTheme.colorScheme.secondary
                        fg = MaterialTheme.colorScheme.onSecondary
                    }
                    PaneState.Color.ERROR -> {
                        bg = MaterialTheme.colorScheme.error
                        fg = MaterialTheme.colorScheme.onError
                    }
                    PaneState.Color.NEUTRAL -> {
                        bg = MaterialTheme.colorScheme.surfaceContainer
                        fg = MaterialTheme.colorScheme.onSurface
                    }
                    PaneState.Color.TERTIARY -> {
                        bg = MaterialTheme.colorScheme.tertiary
                        fg = MaterialTheme.colorScheme.onTertiary
                    }
                }
                DashboardPane(
                    Modifier
                        .fillMaxSize()
                        .wrapContentHeight(), // ?? not expanding pane size
                    bg = bg,
                    fg = fg,
                    onClick = pane.onClick,
                    content = {
                        if (pane.content == null) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (pane.icon != null) {
                                    Icon(
                                        painter = painterResource(pane.icon),
                                        contentDescription = null,
                                        tint = fg,
                                    )
                                }
                                Text(pane.text.orEmpty(), color = fg)
                            }
                        } else {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                pane.content()
                            }
                        }
                    }
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard(modifier: Modifier = Modifier, model: DashboardModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val storageDevices by model.storageDevices.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var selectedDropdown by remember { mutableStateOf<Widget.DropdownSetting?>(null) }
    val coroutineScope = rememberCoroutineScope()
    if (showSettings) {
        SettingsDialog(model, close = {
            showSettings = false
        })
    }
    selectedDropdown?.let { setting ->
        DropdownDialog({
            selectedDropdown = null
        }, setting, { i ->
            model.updateSettingPane(setting.copy(index = i))
            coroutineScope.launch {
                delay(150)
                selectedDropdown = null
            }
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Column(
            modifier = modifier
                .padding(10.dp)
                .widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OverviewPane(state, model, { showSettings = true })
            for (e in storageDevices) {
                CardPane(e, model)
            }

            val panes = mutableListOf<PaneState>()

            val batteries = mutableListOf<PaneBatteryStatus>()
            state.batteryLevelLeft?.let { level -> batteries.add(PaneBatteryStatus("Left", level)) }
            state.batteryLevelMain?.let { level -> batteries.add(PaneBatteryStatus("Base", level)) }
            state.batteryLevelRight?.let { level ->  batteries.add(PaneBatteryStatus("Right", level)) }
            if (batteries.size > 1) panes += BatteryListPane(batteries)

            state.temperature?.let { temp ->
                panes += PaneState(PaneState.Color.NEUTRAL, content = {
                    Row(Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.outline_device_thermostat_24), contentDescription = null)
                        Text("Temperature", color = MaterialTheme.colorScheme.onSurface)
                    }
                    val c = temp.toFloat() / 100
                    Text("%.2f C / %.2f F".format(c, c * 1.8 + 32))
                })
            }

            state.humidity?.let { humid ->
                panes += PaneState(PaneState.Color.NEUTRAL, content = {
                    Row(Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.outline_humidity_percentage_24), contentDescription = null)
                        Text("Humidity", color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text("%.2f%%".format(humid.toFloat() / 100))
                })
            }

            for (pane in state.widgets) {
                panes += when (pane) {
                    is Widget.BooleanSetting -> {
                        PaneState(PaneState.Color.NEUTRAL, content = {
                            Text(pane.args.title, style = MaterialTheme.typography.titleSmall)
                            Switch(pane.value,
                                onCheckedChange = {
                                    model.updateSettingPane(pane.copy(value = !pane.value))
                                }
                            )
                        })
                    }
                    is Widget.Button -> {
                        PaneState(PaneState.Color.PRIMARY, icon = R.drawable.outline_lightbulb_2_24, text = pane.args.title, onClick = {
                            model.updateSettingPane(pane)
                        })
                    }
                    is Widget.Graph -> {
                        PaneState(PaneState.Color.NEUTRAL, content = {
                            val coords = mutableListOf<Pair<Int, Int>>()
                            for (i in pane.points.indices) coords += Pair(i, pane.points[i])
                            Text(pane.args.title)
                            Box(Modifier.aspectRatio(1f)) {
                                IntGridGraph(coords, Modifier)
                            }
                        }, fillMaxWidth = true)
                    }
                    is Widget.DropdownSetting -> {
                        PaneState(PaneState.Color.NEUTRAL, content = {
                            Text(pane.args.title, style = MaterialTheme.typography.titleSmall)
                            Row(Modifier
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(10.dp)
                                .fillMaxWidth()) {
                                Text(pane.options[pane.index])
                                Spacer(Modifier.weight(1f))
                                Icon(painterResource(R.drawable.outline_arrow_forward_24), contentDescription = null)
                            }
                        }, onClick = {
                            selectedDropdown = pane
                        })
                    }
                    is Widget.IntSetting -> {
                        PaneState(PaneState.Color.NEUTRAL, content = {
                            TextField(
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.outline_numbers_24), contentDescription = null)
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal
                                ),
                                value = pane.value.toString(),
                                onValueChange = { model.updateSettingPane(pane.copy(value = it.toInt())) },
                                label = { Text(pane.args.title) }
                            )
                        })
                    }
                    is Widget.SliderSetting -> {
                        PaneState()
                    }
                }
            }
            RenderPanes(panes)
        }
    }
}