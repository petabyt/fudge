package dev.danielc.common.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import dev.danielc.R

@Preview(
    showSystemUi = true,
    showBackground = false,
    backgroundColor = 0,
    device = "id:pixel_9_pro",
    uiMode = 32
)
annotation class PreviewPixel9ProDark

@Preview(showSystemUi = true, device = "spec:width=2500px,height=1700px,dpi=440", uiMode = 32)
annotation class PreviewTabletDark

@Composable
fun ClickableCard(color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, click: () -> Unit = {}, longClick: () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(color)
        .combinedClickable(
            onClick = {
                click()
            },
            onLongClick = {
                longClick()
            }
        )
        .padding(16.dp)
        .alpha(1f),
    ) {
        content()
    }
}

// useless
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Material3DropDown(expanded: Boolean, current: String, options: List<String>, setExpanded: (Boolean) -> Unit, onSelected: (Int) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { setExpanded(!expanded) }
    ) {
        TextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            interactionSource = interactionSource,
            colors = TextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledIndicatorColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onClick = { }
                )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { setExpanded(false) }
        ) {
            for (i in options.indices) {
                DropdownMenuItem(
                    text = { Text(options[i]) },
                    onClick = {
                        onSelected(i)
                        setExpanded(false)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeCustomAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    icon: @Composable () -> Unit = {},
    actionButtons: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = ShapeDefaults.ExtraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    icon()
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .weight(weight = 1f, fill = false)) { content() }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actionButtons()
                }
            }
        }
    }
}

@Composable
fun DeleteDialog(nameOfDevice: String = "FooBar", yes: () -> Unit = {}, no: () -> Unit = {}) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(text = stringResource(R.string.delete)) },
        text = { Text(text = "Delete ${nameOfDevice}?") },
        onDismissRequest = { no() },
        confirmButton = {
            TextButton(onClick = { yes() }) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = { no() }) { Text("No") }
        }
    )
}

@Composable
fun DisconnectDialog(nameOfDevice: String = "FooBar", yes: () -> Unit = {}, no: () -> Unit = {}) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(stringResource(R.string.disconnect))
        },
        text = {
            Text(text = "Disconnect from ${nameOfDevice}?")
        },
        onDismissRequest = {
            no()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    yes()
                }
            ) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    no()
                }
            ) {
                Text(stringResource(R.string.no))
            }
        }
    )
}

//@Preview
@Composable
private fun PreviewGraph() {
    Column(Modifier.size(200.dp)) {
        IntGridGraph(listOf(0 to 10, 1 to 4, 2 to 4, 3 to 5, 4 to 4, 5 to 7, 6 to 40, 10 to 4, 20 to 40, 40 to 30))
    }
}

@Composable
fun IntGridGraph(
    points: List<Pair<Int, Int>>,
    modifier: Modifier = Modifier
) {
    val dataLineColor = Color(0xffab3e4e)
    val backgroundLineColor = Color(0xff2b2b2b)
    Canvas(modifier = modifier
        .fillMaxSize()
        .background(backgroundLineColor)
        .padding(24.dp)) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        var maxX = 0
        var maxY = 0
        for (p in points) {
            if (p.first > maxX) maxX = p.first
            if (p.second > maxY) maxY = p.second
        }

        val spacingX = canvasWidth / maxX
        val spacingY = canvasHeight / maxY

        val pixelPoints = points.map { (x, y) ->
            Offset(
                x = x.toFloat() * spacingX,
                y = canvasHeight - (y.toFloat() * spacingY)
            )
        }

        if (pixelPoints.isNotEmpty()) {
            val path = Path().apply {
                val first = pixelPoints.first()
                moveTo(first.x, first.y)
                pixelPoints.drop(1).forEach { point ->
                    lineTo(point.x, point.y)
                }
            }

            drawPath(
                path = path,
                color = dataLineColor,
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

data class DynamicScaffoldNavBarItem(
    val label: @Composable (() -> Unit) = {},
    val icon: @Composable (() -> Unit) = {},
    val selected: Boolean,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    noTopBar: Boolean = false,
    topTitle: String? = null,
    onBackPressed: (() -> Unit)? = null,
    bottomBar: @Composable (() -> Unit)? = null,
    navBarItems: List<DynamicScaffoldNavBarItem> = emptyList(),
    content: @Composable ((PaddingValues) -> Unit)
) {
    BoxWithConstraints {
        val isLandscape = maxWidth > maxHeight
        Scaffold(
            modifier = modifier,
            topBar = {
                if (topBar != null) {
                    topBar()
                } else {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(),
                        title = { if (topTitle != null) Text(topTitle) },
                        actions = {
                            IconButton(onClick = { if (onBackPressed != null) onBackPressed() }) {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_settings_24),
                                    contentDescription = null
                                )
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (bottomBar != null) {
                    bottomBar()
                } else {
                    if (!isLandscape) {
                        NavigationBar {
                            for (e in navBarItems) {
                                NavigationBarItem(
                                    icon = e.icon,
                                    label = e.label,
                                    selected = e.selected,
                                    onClick = e.onClick,
                                    enabled = e.enabled,
                                )
                            }
                        }
                    }
                }
            },
            content = { padding ->
                if (isLandscape && bottomBar == null) {
                    NavigationRail() {
                        Spacer(Modifier.weight(1f))
                        for (e in navBarItems) {
                            NavigationRailItem(
                                icon = e.icon,
                                label = e.label,
                                selected = e.selected,
                                onClick = e.onClick,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    content(padding + PaddingValues(start = 80.dp))
                } else {
                    content(padding)
                }
            }
        )
    }
}

private const val transitionDuration = 250

@Composable
fun DefaultNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    route: String? = null,
    builder: NavGraphBuilder.() -> Unit
): Unit {
    NavHost(navController, startDestination = startDestination, modifier = modifier, route = route, builder = builder,
        enterTransition = {
            if (targetState.destination.route == "disconnected") {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(transitionDuration, easing = FastOutSlowInEasing))
            } else {
                slideIn(
                    initialOffset = { IntOffset(it.width, 0) },
                    animationSpec = tween(transitionDuration, easing = FastOutSlowInEasing)
                )
            }
        },
        exitTransition = {
            if (targetState.destination.route == "disconnected") {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(transitionDuration, easing = FastOutSlowInEasing))
            } else {
                slideOut(
                    targetOffset = { IntOffset(-it.width / 4, 0) },
                    animationSpec = tween(transitionDuration, easing = FastOutSlowInEasing)
                )
            }
        },
        popEnterTransition = {
            slideIn(
                initialOffset = { IntOffset(-it.width / 4, 0) },
                animationSpec = tween(transitionDuration, easing = FastOutSlowInEasing)
            )
        },
        popExitTransition = {
            slideOut(
                targetOffset = { IntOffset(it.width, 0) },
                animationSpec = tween(transitionDuration, easing = FastOutSlowInEasing)
            )
        },
    )
}