#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Pruebas en el emulador ya encendido (reactivecircus/android-emulator-runner). Solo usa adb: los APK vienen
# compilados del job "build". Sin descargas de YouTube (las pruebas con @NetworkTest no corren aquí).
#   bash android/scripts/ci_emulator.sh <carpeta con los APK> <carpeta de resultados> [--only-selftest]
#  1) Pruebas instrumentadas del APK debug x86_64.
#  2) Build qa (R8, como release): abre sin cerrarse y, si el APK ya trae el motor (lib/*/libpython.so),
#     corre su autoprueba: una línea con tag KDL-SELFTEST, "PASS {json}" (Log.i) o "FAIL ..." (Log.e).
#     Con páginas de 16 KB, mientras elf_alignment_allowlist.txt tenga bibliotecas "pending=M5", lo esperado
#     es "FAIL engine_incompatible" (la app lo explica); cualquier otro resultado es un error. Cuando M5 pase
#     esas líneas a "replaced_by=", la excepción se apaga sola y se exige PASS también ahí.
set -uo pipefail

if [ "$#" -lt 2 ]; then
  echo "Uso: $0 <carpeta con los APK> <carpeta de resultados> [--only-selftest]" >&2
  exit 2
fi
apk_dir="$1"
out="$2"
only_selftest="${3:-}"
mkdir -p "$out"
status=0

pick() { find "$apk_dir" -type f -name "$1" | sort | head -n 1; }
app_pid() {
  local pid
  pid=$(adb shell pidof "$1" 2>/dev/null | tr -d '\r')
  [ -n "$pid" ] || pid=$(adb shell ps -A 2>/dev/null | tr -d '\r' | awk -v p="$1" '$NF == p { print $2 }')
  echo "$pid"
}

# Tamaño de página del emulador (4096, o 16384 en las imágenes ps16k). Informativo.
page_size=$(adb shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')
if ! [[ "$page_size" =~ ^[0-9]+$ ]]; then
  page_size=$(adb shell grep -m1 KernelPageSize /proc/self/smaps 2>/dev/null | tr -d '\r' | awk '{ print $2 * 1024 }')
fi
echo "Tamaño de página: ${page_size:-desconocido}" | tee "$out/page_size.txt"
adb shell input keyevent 82 >/dev/null 2>&1 || true # despierta la pantalla y quita el bloqueo (sin PIN)

if [ "$only_selftest" != "--only-selftest" ]; then
  app=$(pick '*x86_64*-debug.apk')
  test_apk=$(pick '*-debug-androidTest.apk')
  if [ -z "$app" ] || [ -z "$test_apk" ]; then
    echo "::error::Faltan los APK debug x86_64 o de pruebas en $apk_dir"
    status=1
  # -g: permisos concedidos desde la instalación (en Android 8 y 9, el de almacenamiento tiene que estar
  # antes de que arranque el proceso para que la prueba de descarga pueda escribir en Descargas).
  elif ! adb install -r -t -g "$app" || ! adb install -r -t "$test_apk"; then
    echo "::error::No se pudieron instalar los APK debug"
    status=1
  else
    adb shell am instrument -w -r \
      -e notAnnotation io.github.kasaco223.kdownloader.testing.NetworkTest \
      io.github.kasaco223.kdownloader.debug.test/io.github.kasaco223.kdownloader.testing.KdlTestRunner \
      | tr -d '\r' > "$out/instrumentation.txt"
    # "OK (0 tests)" no cuenta: un runner o un filtro mal puesto no puede pasar en verde sin correr nada.
    if grep -qE '^OK \([1-9][0-9]* tests?\)' "$out/instrumentation.txt" \
      && ! grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$out/instrumentation.txt"; then
      grep -E '^OK \(' "$out/instrumentation.txt"
    else
      echo "::error::Fallaron las pruebas instrumentadas (ver instrumentation.txt)"
      grep -E 'FAILURES|INSTRUMENTATION_|Process crashed|^[0-9]+\) |^Tests run' "$out/instrumentation.txt" | head -n 40 || true
      status=1
    fi
  fi
fi

pkg=io.github.kasaco223.kdownloader.qa
activity="$pkg/io.github.kasaco223.kdownloader.MainActivity"
qa=$(pick '*x86_64*-qa.apk')
if [ -z "$qa" ]; then
  echo "::error::Falta el APK qa x86_64 en $apk_dir"
  status=1
elif ! adb install -r "$qa"; then
  echo "::error::No se pudo instalar el APK qa x86_64"
  status=1
else
  # 1) Arranque: el build minificado abre sin cerrarse (así aparece una regla de R8 que falte).
  adb logcat -b all -c >/dev/null 2>&1 || true
  adb shell am start -W -n "$activity" | tr -d '\r' > "$out/qa_start.txt"
  sleep 5
  adb logcat -d -b crash > "$out/qa_crash.txt" 2>&1 || true
  if [ -n "$(app_pid "$pkg")" ] && ! grep -q "Process: $pkg" "$out/qa_crash.txt"; then
    echo "Arranque del build qa (R8): OK" | tee "$out/selftest.txt"
  else
    echo "::error::El build qa (R8) se cerró al abrir (ver qa_crash.txt y logcat.txt)"
    echo "Arranque del build qa (R8): FALLÓ" | tee "$out/selftest.txt"
    status=1
  fi

  # 2) Autoprueba del motor: solo cuando el APK ya trae el motor. La lista se guarda antes de buscar en ella:
  #    con pipefail, `unzip | grep -q` puede dar falso (unzip muere por SIGPIPE) y saltarse la autoprueba.
  if ! qa_files=$(unzip -Z1 "$qa"); then
    echo "::error::No se pudo leer el contenido de $qa"
    qa_files=""
    status=1
  fi
  if grep -qE '^lib/[^/]+/libpython\.so$' <<< "$qa_files"; then
    adb shell am force-stop "$pkg" || true
    adb logcat -c || true
    adb shell am start -W -n "$activity" --ez kdl.selftest true > /dev/null || status=1
    result=""
    for _ in $(seq 1 60); do # hasta 5 minutos: el primer inicio descomprime el motor
      result=$(adb logcat -d -s KDL-SELFTEST:I | tr -d '\r' | grep -oE '(PASS|FAIL).*' | tail -n 1 || true)
      [ -n "$result" ] && break
      sleep 5
    done
    echo "Autoprueba del motor: ${result:-sin respuesta}" | tee -a "$out/selftest.txt"
    allowlist="$(dirname "$0")/elf_alignment_allowlist.txt"
    if [ "${page_size:-}" = "16384" ] && grep -qE '^[^#].* pending=M5' "$allowlist" 2>/dev/null; then
      case "$result" in
        "FAIL engine_incompatible:"*)
          echo "::notice::Páginas de 16 KB: engine_incompatible es lo esperado hasta M5 (libwebp propia)."
          echo "Resultado esperado con páginas de 16 KB hasta M5" >> "$out/selftest.txt" ;;
        *)
          echo "::error::Con páginas de 16 KB se esperaba FAIL engine_incompatible (hasta M5) y llegó: ${result:-nada}"
          status=1 ;;
      esac
    else
      case "$result" in PASS*) ;; *) status=1 ;; esac
    fi
  else
    echo "::notice::Autoprueba del motor omitida: el APK todavía no trae el motor (lib/*/libpython.so)."
    echo "Autoprueba del motor: omitida (el APK todavía no trae el motor)" >> "$out/selftest.txt"
  fi
fi

adb logcat -d > "$out/logcat.txt" 2>&1 || true
exit "$status"
