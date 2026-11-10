package dev.danielc.fudge

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import dev.danielc.fudge.ui.theme.FudgeTheme

@CalledFromNative
@Suppress("unused")
@Composable
fun MainViewController() = ComposeUIViewController {
    FudgeTheme {
        Surface {
            Text("hi")
        }
    }
}
