package com.motocom

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val version: String, val notes: String, val downloadUrl: String) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class ReadyToInstall(val file: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object UpdateManager {
    val state = mutableStateOf<UpdateState>(UpdateState.Idle)
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/laita-dev/Motocom/releases"

    suspend fun checkForUpdates(currentVersion: String) {
        state.value = UpdateState.Checking
        try {
            val (version, notes, apkUrl) = withContext(Dispatchers.IO) { fetchLatestRelease() }
            if (apkUrl.isNotEmpty() && isNewerVersion(version, currentVersion)) {
                state.value = UpdateState.Available(version, notes, apkUrl)
            } else {
                state.value = UpdateState.UpToDate
            }
        } catch (e: Throwable) {
            state.value = UpdateState.Error(e.message ?: "Error al comprobar actualizaciones")
        }
    }

    private fun fetchLatestRelease(): Triple<String, String, String> {
        val url = URL(GITHUB_RELEASES_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "MotocomApp")
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        if (conn.responseCode != 200) {
            throw IllegalStateException("GitHub API devolvió código ${conn.responseCode}")
        }

        val body = conn.inputStream.bufferedReader().readText()
        val releases = Json.parseToJsonElement(body).jsonArray
        if (releases.isEmpty()) return Triple("", "", "")

        val latest = releases[0].jsonObject
        val tag = latest["tag_name"]?.jsonPrimitive?.content.orEmpty()
        val notes = latest["body"]?.jsonPrimitive?.content.orEmpty()
        var apkUrl = ""

        val assets = latest["assets"]?.jsonArray
        assets?.forEach { element ->
            val asset = element.jsonObject
            val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
            if (downloadUrl.endsWith(".apk", ignoreCase = true)) {
                apkUrl = downloadUrl
            }
        }

        return Triple(tag, notes, apkUrl)
    }

    private fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val cleanRemote = remoteTag.removePrefix("v").removeSuffix("-dev").trim()
        val cleanLocal = localVersion.removePrefix("v").removeSuffix("-dev").trim()
        if (cleanRemote == cleanLocal) return false

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return remoteTag.contains("dev") && !localVersion.contains("dev")
    }

    suspend fun downloadAndInstall(context: Context, downloadUrl: String) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, "update.apk")

        try {
            state.value = UpdateState.Downloading(0)
            withContext(Dispatchers.IO) {
                val url = URL(downloadUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                }
                val totalSize = conn.contentLength
                var downloaded = 0

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalSize > 0) {
                                val progress = ((downloaded.toDouble() / totalSize) * 100).toInt()
                                withContext(Dispatchers.Main) {
                                    state.value = UpdateState.Downloading(progress)
                                }
                            }
                        }
                    }
                }
            }

            state.value = UpdateState.ReadyToInstall(apkFile)
            installApk(context, apkFile)
        } catch (e: Throwable) {
            state.value = UpdateState.Error("Error en la descarga: ${e.message}")
        }
    }

    fun installApk(context: Context, file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            state.value = UpdateState.Error("No se pudo abrir el instalador: ${e.message}")
        }
    }
}
