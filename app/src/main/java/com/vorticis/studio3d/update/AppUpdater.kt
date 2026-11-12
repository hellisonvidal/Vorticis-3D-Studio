package com.vorticis.studio3d.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vorticis.studio3d.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Nova versão publicada no GitHub Releases. */
data class UpdateInfo(val versionName: String, val versionCode: Int, val notes: String, val apkUrl: String, val sizeBytes: Long)

/**
 * Atualização por dentro do app: consulta a última versão publicada em
 * GitHub Releases, baixa o APK e abre o instalador do Android.
 */
object AppUpdater {

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    /** Retorna a atualização disponível, ou null se já estiver na versão mais recente. Lança exceção se falhar a rede. */
    fun check(): UpdateInfo? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Vorticis3DStudio")
        conn.connectTimeout = 15_000; conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) error("GitHub respondeu ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            // tags "v1.1.N" -> versionCode 100 + N (mesma regra do build.gradle)
            val n = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val code = if (tag.startsWith("v")) 100 + n else n
            if (code <= currentVersionCode) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return UpdateInfo(
                        versionName = tag.removePrefix("v"),
                        versionCode = code,
                        notes = json.optString("body").lines().firstOrNull { it.isNotBlank() }?.take(300) ?: "",
                        apkUrl = a.getString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Baixa o APK reportando o progresso (0..1). */
    fun download(context: Context, info: UpdateInfo, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Vorticis3DStudio-${info.versionName}.apk")
        var url = URL(info.apkUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Vorticis3DStudio")
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) { url = URL(conn.getHeaderField("Location")); conn.disconnect(); hops++; continue }
            if (code != 200) error("Download falhou ($code)")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    o.write(buf, 0, r); done += r
                    if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()
        return out
    }

    /** Precisa da permissão "instalar apps desconhecidos" para este app? */
    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
