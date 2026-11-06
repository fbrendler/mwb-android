package com.brendler.mwb

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Aparece no menu "Compartilhar" de qualquer app como "Enviar ao PC (MWB)".
 * Arquivos e fotos vão para o PC (Área de Trabalho › MouseWithoutBorders);
 * texto compartilhado vai para a área de transferência do PC.
 * Não tem tela: abre, entrega e fecha.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MwbController.init(this)
        try {
            handle(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui enviar: ${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (!MwbController.isConnected) {
            Toast.makeText(this, "Abra o Mouse Without Borders for Android by Brendler© e conecte ao PC antes de compartilhar.", Toast.LENGTH_LONG).show()
            return
        }

        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)?.let { uris.addAll(it) }
        }

        if (uris.isEmpty()) {
            // Só texto (ex.: um link compartilhado): vai para a área de transferência do PC
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrEmpty()) {
                MwbController.sendClipboardText(text)
                Toast.makeText(this, "Texto enviado para a área de transferência do PC", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Abre os arquivos agora: a permissão de leitura vale enquanto esta tela existe
        val files = uris.mapNotNull { FileStore.openForSending(this, it) }
        if (files.isEmpty()) {
            Toast.makeText(this, "Não consegui abrir o arquivo.", Toast.LENGTH_LONG).show()
            return
        }
        MwbController.sendFiles(files)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}
