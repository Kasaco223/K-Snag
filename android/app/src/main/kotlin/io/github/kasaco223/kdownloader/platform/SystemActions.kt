// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Acciones que salen de la app: abrir una página en el navegador y copiar texto. */
object SystemActions {
    /** Abre [url] en el navegador. Devuelve false si no hay ninguna app que lo abra (nunca lanza). */
    fun openWebPage(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            context.startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }
    }

    /** Abre un archivo descargado con la app que la persona elija. False si no hay ninguna (nunca lanza). */
    fun openFile(context: Context, uri: String, mime: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }
    }

    /** El texto del portapapeles, o null (Android 10+ solo lo deja leer con la app en primer plano). */
    fun readClipboard(context: Context): String? = runCatching {
        context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    /**
     * Copia [text] al portapapeles. Devuelve true si la app debe confirmarlo ella misma: desde Android 13 el
     * sistema ya muestra su propio aviso al copiar.
     */
    fun copyPlainText(context: Context, label: String, text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return Build.VERSION.SDK_INT <= 32
    }
}
