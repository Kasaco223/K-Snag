#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Punto de entrada de la imagen: copia el código (de /src, solo lectura) al volumen /work, corre Gradle con
# los argumentos recibidos y deja los APK y los reportes en /out. Los build/ de /work se conservan entre
# corridas (compilaciones incrementales).
set -euo pipefail

# Sin anclar .gradle/ y .kotlin/: también los de buildSrc/ se conservan (rsync no borra lo excluido).
rsync -a --delete \
  --exclude=/build/ --exclude='/*/build/' --exclude=.gradle/ --exclude=.kotlin/ --exclude=/local.properties \
  /src/android/ /work/android/

# Datos compartidos con la app de Windows que leerán las pruebas de :core (catálogo de errores, engine.json,
# tests/fixtures). Se copian solo si existen.
mkdir -p /work/src/kdownloader/engine /work/tests/fixtures
if [ -f /src/src/kdownloader/engine/errors_catalog.json ]; then
  cp /src/src/kdownloader/engine/errors_catalog.json /work/src/kdownloader/engine/
fi
if [ -f /src/engine.json ]; then cp /src/engine.json /work/engine.json; fi
if [ -d /src/tests/fixtures ]; then rsync -a --delete /src/tests/fixtures/ /work/tests/fixtures/; fi

# Commit del código para "Acerca de". Si no viene de afuera, se lee del repo montado.
if [ -z "${KDL_SOURCE_COMMIT:-}" ]; then
  KDL_SOURCE_COMMIT=$(git -c safe.directory=/src -C /src rev-parse HEAD 2>/dev/null || echo dev)
fi
export KDL_SOURCE_COMMIT

cd /work/android
status=0
bash ./gradlew --console=plain "$@" || status=$?

rm -rf /out/apk /out/reports /out/test-results /out/verification-metadata.xml
# Con --write-verification-metadata, Gradle reescribe gradle/verification-metadata.xml en /work: se deja en
# /out para copiarlo al repo (el repo está montado solo de lectura).
if [ -f gradle/verification-metadata.xml ]; then cp gradle/verification-metadata.xml /out/; fi
if [ -d app/build/outputs/apk ]; then
  mkdir -p /out/apk && cp -r app/build/outputs/apk/. /out/apk/
fi
for module in app core; do
  if [ -d "$module/build/reports" ]; then
    mkdir -p "/out/reports/$module" && cp -r "$module/build/reports/." "/out/reports/$module/"
  fi
  if [ -d "$module/build/test-results" ]; then
    mkdir -p "/out/test-results/$module" && cp -r "$module/build/test-results/." "/out/test-results/$module/"
  fi
done
exit "$status"
