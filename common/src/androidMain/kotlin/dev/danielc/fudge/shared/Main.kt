package dev.danielc.fudge.shared

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

actual object Http {
    private const val CONNECT_TIMEOUT_MS: Int = 10000
    private const val READ_TIMEOUT_MS: Int = 10000

    actual fun post(
        urlString: String,
        body: String,
        headers: Map<String, String>
    ): HttpResponse {
        return request("POST", urlString, body, headers)
    }

    actual fun get(
        urlString: String,
        headers: Map<String, String>
    ): HttpResponse {
        return request("GET", urlString, null, headers)
    }

    private fun request(
        method: String,
        urlString: String,
        body: String?,
        headers: Map<String, String>
    ): HttpResponse {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            connection.setRequestProperty("Accept", "application/json")
            if (body != null && !headers.containsKey("Content-Type")) {
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
            }

            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { os ->
                    val input = body.toByteArray(StandardCharsets.UTF_8)
                    os.write(input, 0, input.size)
                }
            }

            val statusCode = connection.responseCode
            val inputStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = inputStream?.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            } ?: ""

            return HttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}