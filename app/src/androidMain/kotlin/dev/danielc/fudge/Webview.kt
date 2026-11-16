package dev.danielc.fudge
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun WebView(url: String, transparent: Boolean) {
    AndroidView(factory = {
        WebView(it).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }, update = {
        if (transparent) it.setBackgroundColor(Color.TRANSPARENT)
        it.loadUrl(url)
    })
}

@Composable
actual fun WebViewHTML(html: String, transparent: Boolean) {
    AndroidView(factory = {
        WebView(it).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }
    }, update = {
        if (transparent) it.setBackgroundColor(Color.TRANSPARENT)
        it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    })
}