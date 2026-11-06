package com.brendler.mwb

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Onde o app guarda o que vem do PC e como lê o que vai para o PC.
 *  - Arquivos: Downloads/MouseWithoutBorders
 *  - Imagens:  Imagens (Pictures)/MouseWithoutBorders
 * No Android 9 ou anterior, ficam na pasta do app (Android/data/com.brendler.mwb/files).
 */
object FileStore {
    const val FOLDER = "MouseWithoutBorders"

    /** Arquivo sendo gravado. */
    class Target(val uri: Uri?, val file: File?, val stream: OutputStream, val shownPath: String)

    /** Arquivo a enviar ao PC (o descritor fica aberto até o envio terminar). */
    class Outgoing(val name: String, val size: Long, val pfd: ParcelFileDescriptor, val tempFile: File? = null)

    // ---------- recebendo ----------

    fun openDownload(ctx: Context, fileName: String): Target? {
        val mime = mimeOf(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = ctx.contentResolver.openOutputStream(uri) ?: return null
            Target(uri, null, out, "Downloads/$FOLDER/$fileName")
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            val f = uniqueFile(dir, fileName)
            Target(null, f, FileOutputStream(f), f.absolutePath)
        }
    }

    /** Termina a gravação: torna o arquivo visível, ou apaga se não chegou inteiro. */
    fun finish(ctx: Context, t: Target, ok: Boolean) {
        try { t.stream.close() } catch (_: Exception) { }
        if (t.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                ctx.contentResolver.update(t.uri, v, null, null)
            } else {
                ctx.contentResolver.delete(t.uri, null, null)
            }
        } else if (!ok) {
            t.file?.delete()
        }
    }

    /** Salva a imagem recebida do PC (PNG) e devolve o endereço dela, ou null se não for uma imagem válida. */
    fun saveImage(ctx: Context, data: ByteArray): Pair<Uri?, String>? {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val name = "PC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            Pair(uri, "Imagens/$FOLDER/$name")
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), FOLDER).apply { mkdirs() }
            val f = uniqueFile(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Pair(null, f.absolutePath)
        }
    }

    // ---------- enviando ----------

    /** Abre um arquivo escolhido ou compartilhado para enviar ao PC. */
    fun openForSending(ctx: Context, uri: Uri): Outgoing? {
        var name: String? = null
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) { }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"

        val pfd = try { ctx.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null } ?: return null
        if (size < 0) size = pfd.statSize
        if (size >= 0) return Outgoing(finalName, size, pfd)

        // Tamanho desconhecido (ex.: conteúdo gerado na hora): copia para um arquivo temporário
        val tmp = File(ctx.cacheDir, "envio_" + System.nanoTime())
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val tpfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        return Outgoing(finalName, tmp.length(), tpfd, tmp)
    }

    // ---------- utilitários ----------

    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale("pt", "BR"), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale("pt", "BR"), "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }
}
