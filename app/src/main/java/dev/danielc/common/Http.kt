package dev.danielc.common

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object Http {
    private const val CONNECT_TIMEOUT_MS: Int = 10000
    private const val READ_TIMEOUT_MS: Int = 10000

    fun post(
        urlString: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Response {
        return request("POST", urlString, body, headers)
    }

    fun get(
        urlString: String,
        headers: Map<String, String> = emptyMap()
    ): Response {
        return request("GET", urlString, null, headers)
    }

    private fun request(
        method: String,
        urlString: String,
        body: String?,
        headers: Map<String, String>
    ): Response {
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

            return Response(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    data class Response(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }
}