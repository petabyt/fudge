package dev.danielc.common.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.danielc.R
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.common.ui.theme.LightGray
import dev.danielc.common.ui.theme.primaryIconButtonColors
import dev.danielc.fudge.FramebufferSurface
import dev.danielc.fudge.ModuleLiveviewModel

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_9a", uiMode = 32)
@Composable
fun PreviewLiveview() {
    FudgeTheme {
        Scaffold { innerPadding ->
            Liveview(Modifier.padding(innerPadding), model = null)
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
fun LiveviewButton(modifier: Modifier = Modifier, text: String, icon: Int, currentValue: String) {
    Button(modifier = modifier, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LightGray.copy(alpha = 0.5f)),
        onClick = {}) {
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
fun Liveview(modifier: Modifier = Modifier, model: ModuleLiveviewModel?, intervalometerModel: ModuleIntervalometerModel? = null) {
    val haptic = LocalHapticFeedback.current
    Box(modifier.fillMaxSize().background(Color.Black)) {
        @Composable
        fun buttons(modifier: Modifier) {
            LiveviewButton(modifier, text = "White Balance", R.drawable.outline_wb_sunny_24, "Daylight")
            LiveviewButton(modifier, text = "ISO", R.drawable.outline_iso_24, "6400")
            LiveviewButton(modifier, text = "Shutter Speed", R.drawable.outline_shutter_speed_24, "1/60")
            LiveviewButton(modifier, text = "Aperture", R.drawable.outline_camera_24, "f/2.0")
            LiveviewButton(modifier, text = "Format", R.drawable.outline_style_24, "RAW")
        }
        @Composable
        fun shutterPanel() {
            // Fullscreen button

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

            IconButton(onClick = {}, modifier = Modifier.size(70.dp),
                interactionSource = interactionSource,
                colors = if (isPressed) primaryIconButtonColors(0.8f) else primaryIconButtonColors(), shape = RoundedCornerShape(10.dp)) {
                Icon(painterResource(R.drawable.outline_camera_24), contentDescription = null, Modifier.padding(10.dp).fillMaxSize())
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
        if (model == null) {
            Image(
                modifier = Modifier.align(Alignment.Center),
                painter = painterResource(R.drawable.image),
                contentDescription = null
            )
        } else {
            FramebufferSurface(Modifier.aspectRatio(1.5f).align(Alignment.Center), model)
        }

        if (LocalConfiguration.current.orientation == 2) {
           Box(Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHigh).align(Alignment.BottomEnd)) {
                Column(Modifier.fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    shutterPanel()
                }
            }
        } else {
           Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).align(Alignment.BottomEnd)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
                    shutterPanel()
                }
            }
        }
    }
}