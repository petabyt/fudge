package dev.danielc.common.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.common.WebView
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import fantasyfudge.common.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun HelpScreen(navController: NavHostController = rememberNavController()) {
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text(stringResource(Res.string.help))
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            navController.navigateUp()
                        }) {
                            Icon(
                                painter = painterResource(Res.drawable.outline_arrow_back_24),
                                contentDescription = null
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding)) {
                WebView("https://example.com/")
            }
        }
    }
}