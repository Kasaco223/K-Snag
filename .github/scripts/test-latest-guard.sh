#!/usr/bin/env bash
# Prueba latest-guard.sh contra un gh simulado (mockbin/), sin red. Lo corre workflows-lint.yml.
#   bash .github/scripts/test-latest-guard.sh
# Cada caso: estado de los Releases ("tag draft prerelease created"), Latest inicial, fallos simulados y lo
# esperado: Latest final, código de salida y cuántos android-* quedan publicados como normales.
# Invariante de todos los casos: al terminar, Latest NUNCA es un android-*.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
export PATH="$HERE/mockbin:$PATH" GH_REPO="Kasaco223/K-Snag" KD_SLEEP_SCALE=0
total=0; bad=0

run() {
  local name="$1" latest="$2" want_latest="$3" want_rc="$4" want_android="$5" fails="$6"; shift 6
  local st rc got android status=OK
  st="$(mktemp -d)"; export MOCK_STATE="$st"
  printf '%s\n' "$@" > "$st/releases"
  printf '%s' "$latest" > "$st/latest"
  printf '%s\n' "$fails" | tr ';' '\n' | sed '/^ *$/d' > "$st/fail"
  bash "$HERE/latest-guard.sh" > "$st/out" 2>&1; rc=$?
  got=$(cat "$st/latest")
  android=$(awk '$1 ~ /^android-/ && $2=="false" && $3=="false"' "$st/releases" | wc -l | tr -d ' ')
  [ "$got" = "$want_latest" ] || status=FAIL
  [ "$rc" = "$want_rc" ] || status=FAIL
  [ "$android" = "$want_android" ] || status=FAIL
  case "$got" in android-*) status=FAIL ;; esac
  total=$((total + 1)); [ "$status" = OK ] || bad=$((bad + 1))
  printf '%-4s %-66s rc=%s latest=%-12s android_normal=%s\n' "$status" "$name" "$rc" "${got:-none}" "$android"
  [ "$status" = OK ] || sed 's/^/      /' "$st/out"
  rm -rf "$st"
}

#   nombre                                                    latest  esperado  rc android fallos  releases...
run "A: todo en orden"                                        v2.0.1 v2.0.1 0 0 "" \
  "v2.0.0 false false 1" "v2.0.1 false false 2" "android-v1.0.0 false true 3"
run "B: Android publicado normal y GitHub lo hizo Latest"     android-v1.0.0 v2.0.1 0 0 "" \
  "v2.0.0 false false 1" "v2.0.1 false false 2" "android-v1.0.0 false false 3"
run "C: v2.1.0 publicado pero GitHub no lo marcó"             v2.0.1 v2.1.0 0 0 "" \
  "v2.0.1 false false 1" "android-v1.0.0 false true 2" "v2.1.0 false false 3"
run "D: arreglo viejo v2.0.2 se quedó con Latest"             v2.0.2 v2.1.0 0 0 "" \
  "v2.0.1 false false 1" "v2.1.0 false false 2" "v2.0.2 false false 3"
run "E: sin Windows publicado, Android normal"                android-v1.0.0 "" 0 0 "" \
  "v2.0.0 true false 1" "android-v1.0.0 false false 2"
run "F: rc de Windows (pre-release) no cuenta"                v2.1.0 v2.1.0 0 0 "" \
  "v2.1.0 false false 1" "v2.2.0-rc.1 false true 2" "android-v1.1.0-rc.1 false true 3"
run "G: versión mala de Windows marcada pre-release"          v2.1.0 v2.1.0 0 0 "" \
  "v2.1.0 false false 1" "android-v1.0.0 false true 2" "v2.2.0 false true 3"
run "H: 2.10.0 > 2.9.0 (orden de versiones, no de texto)"     v2.9.0 v2.10.0 0 0 "" \
  "v2.9.0 false false 1" "v2.10.0 false false 2" "android-v10.0.0 false false 3"
run "I: borrador de Windows más alto no cuenta"               v2.0.1 v2.0.1 0 0 "" \
  "v2.0.1 false false 1" "v9.0.0 true false 2"
run "J: espejo de fuentes publicado como normal"              android-sources-ytdl-0.18.1 v2.0.1 0 0 "" \
  "v2.0.1 false false 1" "android-sources-ytdl-0.18.1 false false 2"
run "K: rc de Windows publicada normal y hecha Latest"        v2.2.0-rc.1 v2.1.0 0 0 "" \
  "v2.1.0 false false 1" "v2.2.0-rc.1 false false 2" "android-v1.0.0 false true 3"
run "L: Android rc publicada normal, con Latest"              android-v1.1.0-rc.1 v2.1.0 0 0 "" \
  "v2.1.0 false false 1" "android-v1.0.0 false true 2" "android-v1.1.0-rc.1 false false 3"
# --- fallos de la API ---
run "M: la lista falla una vez (se reintenta)"                android-v1.0.0 v2.0.1 0 0 "list-android 1;list-published 1" \
  "v2.0.1 false false 1" "android-v1.0.0 false false 2"
run "N: marcar Latest falla siempre; Android ya es pre-release" android-v1.0.0 v2.0.2 1 0 "edit-latest always" \
  "v2.1.0 false false 1" "v2.0.2 false false 2" "android-v1.0.0 false false 3"
run "O: pasar a pre-release falla 2 veces y luego funciona"   android-v1.0.0 v2.0.1 0 0 "edit-prerelease 2" \
  "v2.0.1 false false 1" "android-v1.0.0 false false 2"
run "P: pasar a pre-release falla siempre; Latest se corrige igual" android-v1.0.0 v2.0.1 1 1 "edit-prerelease always" \
  "v2.0.1 false false 1" "android-v1.0.0 false false 2"
run "Q: la API de Latest falla 3 veces al comprobar"           android-v1.0.0 v2.0.1 0 0 "api-latest 3" \
  "v2.0.1 false false 1" "android-v1.0.0 false false 2"
run "R: la lista de publicados falla siempre"                 android-v1.0.0 v2.0.1 1 0 "list-published always" \
  "v2.0.1 false false 1" "android-v1.0.0 false false 2"
run "S: la primera lista falla siempre; el paso 2 corrige Latest" android-v1.0.0 v2.0.1 1 1 "list-android always" \
  "v2.0.1 false false 1" "android-v1.0.0 false false 2"

echo "$((total - bad))/$total casos OK"
[ "$bad" -eq 0 ]
