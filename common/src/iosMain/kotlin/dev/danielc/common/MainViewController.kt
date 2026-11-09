package dev.danielc.common

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeUIViewController
import dev.danielc.common.ui.theme.FudgeTheme

fun MainViewController() = ComposeUIViewController {
    FudgeTheme {
        Surface {
            Text("hi")
        }
    }
}
