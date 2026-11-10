package dev.danielc.fudge

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.*
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

actual object Http {
    actual data class Response(actual val code: Int, actual val body: String) {
        actual val isSuccessful: Boolean get() = code in 200..299
    }
    private const val TIMEOUT_SECONDS: Double = 20.0

    actual fun post(
        urlString: String,
        body: String,
        headers: Map<String, String>
    ): Response {
        return request("POST", urlString, body, headers)
    }

    actual fun get(
        urlString: String,
        headers: Map<String, String>
    ): Response {
        return request("GET", urlString, null, headers)
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun request(
        method: String,
        urlString: String,
        body: String?,
        headers: Map<String, String>
    ): Response {
        val url = NSURL.URLWithString(urlString) ?: return Response(400, "Invalid URL")
        val urlRequest = NSMutableURLRequest.requestWithURL(url).apply {
            setHTTPMethod(method)
            setTimeoutInterval(TIMEOUT_SECONDS)

            setValue("application/json", forHTTPHeaderField = "Accept")
            if (body != null && !headers.containsKey("Content-Type")) {
                setValue("application/json; charset=utf-8", forHTTPHeaderField = "Content-Type")
            }

            headers.forEach { (key, value) ->
                setValue(value, forHTTPHeaderField = key)
            }

            if (body != null) {
                setHTTPBody(NSString.create(string = body).dataUsingEncoding(NSUTF8StringEncoding))
            }
        }

        val semaphore = dispatch_semaphore_create(0)
        var statusCode = 500
        var responseBody = ""

        val task = NSURLSession.sharedSession.dataTaskWithRequest(urlRequest) { data, response, error ->
            val httpResponse = response as? NSHTTPURLResponse
            if (httpResponse != null) {
                statusCode = httpResponse.statusCode.toInt()
            }

            if (data != null) {
                val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())
                responseBody = bytes?.decodeToString() ?: ""
            } else if (error != null) {
                responseBody = error.localizedDescription
            }

            dispatch_semaphore_signal(semaphore)
        }

        task.resume()
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)

        return Response(statusCode, responseBody)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
actual fun createClipEntry(label: String, text: String): ClipEntry {
    return ClipEntry.withPlainText(text)
}