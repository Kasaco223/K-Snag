// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.testing

/**
 * Marca las pruebas que necesitan internet de verdad (por ejemplo, descargar de YouTube). CI no las corre:
 * desde las IP de un centro de datos YouTube pide comprobar que no eres un robot. Se corren a mano en un
 * celular o emulador con conexión.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NetworkTest
