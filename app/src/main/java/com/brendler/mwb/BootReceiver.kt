package com.brendler.mwb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tablet ligado (ou app atualizado): conecta ao último PC, se "Conectar automaticamente" estiver ligado.
 * É uma segunda garantia além do serviço de controle, que o Android também liga sozinho.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "tablet ligado"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app atualizado"
            else -> return
        }
        MwbController.autoStart(context, reason)
    }
}
