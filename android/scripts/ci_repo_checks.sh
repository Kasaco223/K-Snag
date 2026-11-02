#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Revisiones del repositorio que no necesitan Gradle ni el SDK. Se corre desde la RAÍZ del repo (CI y local):
#   bash android/scripts/ci_repo_checks.sh
# Cada revisión trabaja solo con archivos que ya existen. Las que lleguen con otros PR (avisos de terceros,
# pins de youtubedl-android, espejo de fuentes, huellas del certificado de release) se agregan como
# secciones nuevas al final, con su propio número.
set -uo pipefail
fail=0
err() {
  echo "::error file=$1::$2"
  fail=1
}

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1 || [ ! -d android ]; then
  echo "Corre este script desde la raíz del repositorio." >&2
  exit 2
fi

# 1) Scripts ejecutables en git y con LF: Windows no guarda el bit +x por sí solo, y un CRLF rompe bash.
while IFS= read -r f; do
  [ -n "$f" ] || continue
  mode=$(git ls-files -s -- "$f" | cut -d' ' -f1)
  [ "$mode" = "100755" ] || err "$f" "No es ejecutable en git (modo ${mode:-?}). Corre: git update-index --chmod=+x $f"
  if grep -q $'\r' "$f"; then err "$f" "Tiene finales de línea CRLF (revisa .gitattributes)"; fi
done < <(git ls-files -- android/gradlew 'android/*.sh')

# 2) Encabezado SPDX en los archivos propios de android/ (GPL-3.0-or-later; GPL-3.0-only si se copió de un
#    proyecto con esa licencia). No llevan encabezado: el texto de la licencia, los binarios, el wrapper
#    de Gradle (archivos de Gradle, Apache-2.0), gradle/verification-metadata.xml (lo reescribe Gradle) y los
#    textos de licencia de terceros (third_party/licenses/) y signing/release-cert.sha256 (una huella por línea).
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    android/LICENSE | android/gradlew | android/gradlew.bat | android/gradle/wrapper/* | android/gradle/verification-metadata.xml | android/third_party/licenses/* | android/signing/release-cert.sha256) continue ;;
  esac
  case "$f" in
    *.kt | *.kts | *.java | *.sh | *.py | *.xml | *.properties | *.toml | *.yaml | *.yml | *.pro | *.md | */Dockerfile) ;;
    *) continue ;;
  esac
  header=$(head -n 12 "$f")
  if ! grep -qE 'SPDX-License-Identifier: GPL-3\.0-(or-later|only)' <<< "$header"; then
    err "$f" "Falta el encabezado SPDX-License-Identifier: GPL-3.0-or-later (android/README.md › Licencia)"
  fi
  if ! grep -q 'SPDX-FileCopyrightText: ' <<< "$header"; then
    err "$f" "Falta la línea SPDX-FileCopyrightText (android/README.md › Licencia)"
  fi
done < <(git ls-files -- android)

# 3) Ninguna llave de firma en git, salvo la de debug (pública a propósito). Esa se fija por SHA-256: una
#    llave real guardada con ese nombre también falla (CI firmaría con ella los APK debug y qa).
debug_keystore=android/app/debug.keystore
debug_keystore_sha=44370b800f58b5d0265c54c49a80ed98f547b10774d8fe366d835d13e497d2dc
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if [ "$f" = "$debug_keystore" ]; then
    if [ "$(sha256sum "$f" | cut -d' ' -f1)" != "$debug_keystore_sha" ]; then
      err "$f" "No es la llave de debug pública (android/README.md › Variantes, versiones y firma)"
    fi
    continue
  fi
  err "$f" "Hay una llave de firma en el repositorio: sácala de git y reemplázala por una nueva"
done < <(git ls-files -- '*.jks' '*.p12' '*.pfx' '*.keystore' '*.pk8')

# 4) android/LICENSE es el texto de la GPL-3.0 sin cambios (https://www.gnu.org/licenses/gpl-3.0.txt).
gpl_sha=3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986
if [ ! -f android/LICENSE ]; then
  err android/LICENSE "Falta el texto de la GPL-3.0"
elif [ "$(sha256sum android/LICENSE | cut -d' ' -f1)" != "$gpl_sha" ]; then
  err android/LICENSE "No es el texto oficial de la GPL-3.0: restáuralo desde https://www.gnu.org/licenses/gpl-3.0.txt"
fi

# 5) El wrapper de Gradle fija el SHA-256 de su distribución (setup-gradle valida además gradle-wrapper.jar).
props=android/gradle/wrapper/gradle-wrapper.properties
if ! grep -qE '^distributionSha256Sum=[0-9a-f]{64}$' "$props"; then
  err "$props" "Falta distributionSha256Sum (SHA-256 de la distribución de Gradle)"
fi
if ! grep -qE '^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-[0-9.]+-bin\.zip$' "$props"; then
  err "$props" "distributionUrl debe ser una distribución -bin.zip de services.gradle.org"
fi

# 6) Inventario de terceros válido (todo lo copyleft con espejo, licencias citadas y presentes) y
#    THIRD_PARTY_NOTICES.md al día con él.
python3 -I -X utf8 android/scripts/notices.py --check || fail=1

# 7) Los SHA-256 de los AAR de youtubedl-android: los mismos en components.json y en la verificación de
#    Gradle. No hace nada mientras el catálogo no traiga youtubedl-android.
python3 -I -X utf8 android/scripts/check_pins.py || fail=1

# 8) Con youtubedl-android en el catálogo, el espejo de fuentes debe estar publicado y completo antes de que
#    CI arme un APK con FFmpeg (los artefactos de un repo público ya son distribución). Sin él, no hace nada.
python3 -I -X utf8 android/scripts/sources_mirror.py --verify || fail=1

if [ "$fail" -eq 0 ]; then echo "Revisiones del repositorio: OK"; fi
exit "$fail"
