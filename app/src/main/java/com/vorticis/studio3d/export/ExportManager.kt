package com.vorticis.studio3d.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.vorticis.studio3d.core.export.ExportFile
import java.io.File

/** Salva arquivos em Downloads/Vorticis3D e prepara o compartilhamento. */
object ExportManager {

    /** Retorna uma descrição de onde o arquivo foi salvo. */
    fun saveToDownloads(context: Context, file: ExportFile): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeForStore(file))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vorticis3D")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Não foi possível criar o arquivo em Downloads")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(file.bytes) }
            "Downloads/Vorticis3D/${file.name}"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Vorticis3D")
            val target = try {
                dir.mkdirs(); File(dir, file.name).apply { writeBytes(file.bytes) }
            } catch (_: Exception) {
                File(context.getExternalFilesDir(null), file.name).apply { writeBytes(file.bytes) }
            }
            target.absolutePath
        }
    }

    fun shareIntent(context: Context, file: ExportFile): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, file.name).apply { writeBytes(file.bytes) }
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeForShare(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartilhar ${file.name}")
    }

    // MediaStore rejeita alguns tipos "model/*" em certos aparelhos; usamos genérico quando necessário.
    private fun mimeForStore(f: ExportFile) = when {
        f.mime.startsWith("model/") || f.mime == "text/plain" -> "application/octet-stream"
        else -> f.mime
    }

    private fun mimeForShare(f: ExportFile) = if (f.mime == "application/zip") f.mime else "application/octet-stream"
}
