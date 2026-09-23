package dev.danielc.common.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.danielc.R
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.Widget
import dev.danielc.common.WidgetNames
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.common.ui.theme.LightGray
import dev.danielc.common.ui.theme.primaryIconButtonColors
import dev.danielc.fudge.FramebufferSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class LiveviewState(
    val widgets: List<Widget> = emptyList(),
)

enum class LiveviewWidgetNames(val id: String) {
    ISO("iso"),
    SHUTTER_SPEED("shutter-speed"),
    APERTURE("aperture"),
    WHITE_BALANCE("white-balance"),
    IMAGE_FORMAT("image-format"),
}

open class LiveviewModel: BackgroundViewModel() {
    private val _state = MutableStateFlow(LiveviewState())
    val state = _state.asStateFlow()
    fun updateWidget(pane: Widget) {
        scope.launch(Dispatchers.IO) {
            _state.update { currentState ->
                if (currentState.widgets.find { it.args.name == pane.args.name } == null) {
                    currentState.copy(widgets = currentState.widgets + pane)
                } else {
                    currentState
                }
            }
        }
    }
    open fun widgetUpdated(pane: Widget) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_9a", uiMode = 32)
@Composable
fun PreviewLiveview() {
    val model = LiveviewModel()
    model.updateWidget(Widget.DropdownSetting(Widget.Properties("white-balance", "White Balance", Widget.Group.LIVEVIEW), index = -1, options = listOf(
        "Cloudy", "Incandescent", "Sunny"
    )))
    model.updateWidget(Widget.DropdownSetting(Widget.Properties("shutter-speed", "Shutter Speed", Widget.Group.LIVEVIEW), index = -1, options = listOf(
        "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1\"", "2\"", "4\"", "8\"", "15\"", "30\""
    )))
    model.updateWidget(Widget.DropdownSetting(Widget.Properties("aperture", "Aperture", Widget.Group.LIVEVIEW), index = -1, options = listOf(
        "f/1.0", "f/1.4", "f/2", "f/2.8", "f/4", "f/5.6", "f/8", "f/11", "f/16", "f/22", "f/32"
    )))
    model.updateWidget(Widget.DropdownSetting(Widget.Properties("format", "Format", Widget.Group.LIVEVIEW), index = -1, options = listOf(
        "JPEG", "RAW"
    )))
    model.updateWidget(Widget.DropdownSetting(Widget.Properties("iso", "ISO", Widget.Group.LIVEVIEW), index = -1, options = listOf(
        "6400", "3200", "1600", "800", "600", "400", "200", "100"
    )))

    FudgeTheme {
        Scaffold { innerPadding ->
            Liveview(Modifier.padding(innerPadding), model = model)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "spec:width=411dp,height=891dp,dpi=420,isRound=false,chinSize=0dp,orientation=landscape", uiMode = 32)
@Composable
fun PreviewLiveview2() {
    PreviewLiveview()
}

@Composable
fun LiveviewButton(modifier: Modifier = Modifier, text: String, icon: Int, currentValue: String, onClick: () -> Unit = {}) {
    Button(modifier = modifier, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LightGray.copy(alpha = 0.5f)),
        onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(icon),
                tint = Color.White,
                contentDescription = text
            )
            Text(currentValue, overflow = TextOverflow.Clip, maxLines = 1, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun CustomWheelPicker(
    items: List<String>,
    selectedIndex: Int,
    visibleItemsCount: Int = 5,
    itemHeight: Dp = 48.dp,
    onItemSelected: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (selectedIndex == -1) 0 else selectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    val paddingItemCount = visibleItemsCount / 2
    val pickerHeight = itemHeight * visibleItemsCount

    val centeredIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@derivedStateOf 0

            // Find the item closest to the center of the viewport
            val viewportCenter = layoutInfo.viewportEndOffset / 2
            visibleItemsInfo.minByOrNull {
                abs((it.offset + (it.size / 2)) - viewportCenter)
            }?.index ?: 0
        }
    }

    LaunchedEffect(centeredIndex) {
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    Box(
        modifier = Modifier.height(pickerHeight),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize()
        ) {
            items(paddingItemCount) { Spacer(modifier = Modifier.height(itemHeight)) }

            items(items.size) { index ->
                val absoluteIndex = index + paddingItemCount
                val distanceToCenter = abs(absoluteIndex - centeredIndex)

                val itemAlpha = if (distanceToCenter == 0) 1f else 0f

                Box(
                    modifier = Modifier
                        .height(itemHeight)
                        .fillMaxWidth()
                        .background(Color.Black.copy(itemAlpha.coerceIn(0.1f, 0.4f)))
                        .clickable(onClick = {
                            onItemSelected(index)
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = items[index],
                        fontSize = if (distanceToCenter == 0) 20.sp else 16.sp
                    )
                }
            }

            items(paddingItemCount) { Spacer(modifier = Modifier.height(itemHeight)) }
        }
    }
}

@Composable
fun Liveview(modifier: Modifier = Modifier, model: LiveviewModel, intervalometerModel: ModuleIntervalometerModel? = null) {
    val haptic = LocalHapticFeedback.current
    val state by model.state.collectAsStateWithLifecycle()
    var selectedWidget by rememberSaveable { mutableStateOf<String?>(null) }
    val widgets = state.widgets

    val prioWidgets = widgets.sortedBy {
        when (it.args.name) {
            WidgetNames.ISO.id -> 0
            WidgetNames.SHUTTER_SPEED.id -> 1
            WidgetNames.APERTURE.id -> 2
            WidgetNames.WHITE_BALANCE.id -> 3
            else -> 5
        }
    }

    fun widgetIcon(name: String): Int {
        return when (name) {
            WidgetNames.ISO.id -> R.drawable.outline_iso_24
            WidgetNames.SHUTTER_SPEED.id -> R.drawable.outline_shutter_speed_24
            WidgetNames.APERTURE.id -> R.drawable.outline_camera_24
            WidgetNames.WHITE_BALANCE.id -> R.drawable.outline_wb_sunny_24
            WidgetNames.IMAGE_FORMAT.id -> R.drawable.outline_style_24
            else -> R.drawable.baseline_question_mark_24
        }
    }

    Box(modifier
        .fillMaxSize()
        .background(Color.Black)) {
        @Composable
        fun buttons(modifier: Modifier) {
            for (w in prioWidgets) {
                if (w is Widget.DropdownSetting) {
                    LiveviewButton(modifier, text = w.args.title, icon = widgetIcon(w.args.name), currentValue = if (w.index == -1) "-" else w.options.getOrElse(w.index, {"-"})) {
                        selectedWidget = if (selectedWidget == w.args.name) null else w.args.name
                    }
                }
            }
        }
        @Composable
        fun shutterPanel(modifier: Modifier) {
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { interaction ->
                    if (interaction is PressInteraction.Press) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        intervalometerModel?.shutter(true)
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        intervalometerModel?.shutter(false)
                    }
                }
            }

            Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }, modifier = Modifier.size(70.dp), shape = RoundedCornerShape(10.dp)) {
                    Icon(painterResource(R.drawable.outline_menu_24), contentDescription = null, Modifier.padding(10.dp))
                }
            }

            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = {}, modifier = Modifier.size(70.dp),
                    interactionSource = interactionSource,
                    colors = if (isPressed) primaryIconButtonColors(0.8f) else primaryIconButtonColors(), shape = RoundedCornerShape(10.dp)) {
                    Icon(painterResource(R.drawable.outline_camera_24), contentDescription = null, Modifier
                        .padding(10.dp)
                        .fillMaxSize())
                }
            }

            Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }, modifier = Modifier.size(70.dp), shape = RoundedCornerShape(10.dp)) {
                    Icon(painterResource(R.drawable.outline_fullscreen_24), contentDescription = null, Modifier.padding(10.dp))
                }
            }
        }

        if (LocalConfiguration.current.orientation == 2) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                buttons(Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                buttons(Modifier.weight(1f))
            }
        }

        FramebufferSurface(Modifier
            .aspectRatio(1.5f)
            .align(Alignment.Center), model)

        if (LocalConfiguration.current.orientation == 2) {
           Box(Modifier
               .fillMaxHeight()
               .background(MaterialTheme.colorScheme.surfaceContainerHigh)
               .align(Alignment.BottomEnd)) {
                Column(Modifier
                    .fillMaxHeight()
                    .padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    shutterPanel(Modifier.weight(1f))
                }
            }
        } else {
           Box(Modifier
               .fillMaxWidth()
               .background(MaterialTheme.colorScheme.surfaceContainerHigh)
               .align(Alignment.BottomEnd)) {
                Row(Modifier
                    .fillMaxWidth()
                    .padding(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
                    shutterPanel(Modifier.weight(1f))
                }
            }
        }

        selectedWidget?.let { widgetName ->
            val widget = widgets.find { it.args.name == widgetName }
            if (widget == null) return
            if (widget is Widget.DropdownSetting) {
                Box(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.8f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Gray.copy(alpha = 0.4f))
                        .padding(16.dp)
                        .align(Alignment.Center),
                ) {
                    CustomWheelPicker(
                        widget.options,
                        onItemSelected = { i ->
                            selectedWidget = null
                            model.widgetUpdated(widget.copy(index = i))
                        },
                        selectedIndex = widget.index
                    )
                }
            }
        }
    }
}