package dev.danielc.fudge.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun App() {
    MaterialTheme {
        Surface {
            Text("Hello World")
        }
    }
}

data class HttpResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

expect object Http {
    fun post(urlString: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse
    fun get(urlString: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/*
expect object WiFi {
    class Device {

    }
}

expect object Bluetooth {
    class Device {}
}
*/