#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Firma los APK sin firmar de <entrada> y deja en <salida> K-Downloader-Android-<abi>.apk.
# Uso (lo llama .github/workflows/android-release.yml): sign_apks.sh <entrada> <salida>
# Variables: ABIS, BUILD_TOOLS, ANDROID_HOME, KS_FILE, KS_PASS, KEY_PASS, KEY_ALIAS, EXPECTED_CERT_SHA256.
# Las contraseñas llegan por variables de entorno (apksigner "env:"), nunca por la línea de comandos.
set -euo pipefail

in_dir="$1"
out_dir="$2"
bt="${ANDROID_HOME}/build-tools/${BUILD_TOOLS}"
expected=$(printf '%s' "${EXPECTED_CERT_SHA256}" | tr -d ':[:space:]' | tr 'A-F' 'a-f')
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "::error::EXPECTED_CERT_SHA256 no es un SHA-256 válido"; exit 1; }
mkdir -p "$out_dir"

for abi in $ABIS; do
  mapfile -t found < <(find "$in_dir" -type f -name "*-${abi}-release-unsigned.apk")
  if [ "${#found[@]}" -ne 1 ]; then
    echo "::error::${abi}: se esperaba 1 APK sin firmar, hay ${#found[@]}"
    exit 1
  fi
  aligned="${RUNNER_TEMP:-/tmp}/aligned-${abi}.apk"
  out="${out_dir}/K-Downloader-Android-${abi}.apk"

  # zipalign va ANTES de firmar (la firma v2/v3 cubre el archivo entero). -P 16: .so sin comprimir a 16 KB.
  "$bt/zipalign" -P 16 -f 4 "${found[0]}" "$aligned"
  "$bt/apksigner" sign --ks "$KS_FILE" --ks-type PKCS12 --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS --out "$out" "$aligned"
  rm -f "$aligned"

  report=$("$bt/apksigner" verify --verbose --print-certs "$out")
  grep -q '^Verified using v2 scheme (APK Signature Scheme v2): true$' <<<"$report" \
    || { echo "::error::${abi}: falta la firma v2"; echo "$report"; exit 1; }
  signers=$(sed -n 's/^Number of signers: //p' <<<"$report")
  [ "$signers" = "1" ] || { echo "::error::${abi}: ${signers:-0} firmantes (se espera 1)"; exit 1; }
  cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$report")
  if [ "$cert" != "$expected" ]; then
    echo "::error::${abi}: firmado con el certificado ${cert}, se esperaba ${expected} (variable ANDROID_CERT_SHA256)"
    exit 1
  fi
  "$bt/zipalign" -c -P 16 4 "$out" >/dev/null || { echo "::error::${abi}: quedó desalineado"; exit 1; }
  echo "${abi}: firmado y verificado (certificado ${cert})"
done
