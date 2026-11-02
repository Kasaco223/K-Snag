# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Reglas de R8 para release y qa. Las bibliotecas (AndroidX, Compose, Kotlin) traen las suyas; aquí solo va
# lo propio. Quien agregue reflexión, JNI o serialización por nombre agrega su regla con un comentario.

# App GPL con código público: no tiene sentido ofuscar, y así los reportes traen trazas legibles.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Si R8 pide -dontwarn, copia las líneas de app/build/outputs/mapping/<variante>/missing_rules.txt aquí,
# cada una con un comentario de por qué falta esa clase.
