#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
out="$root/build/test-https"
raw="$root/host-app/src/main/res/raw"
assets="$root/host-app/src/main/assets"
password='ep02-test-only'

command -v openssl >/dev/null || { echo 'openssl is required for the ephemeral Android HTTPS fixture' >&2; exit 2; }
rm -rf "$out"
mkdir -p "$out" "$raw" "$assets"

cat > "$out/server.ext" <<'EOF'
[server_cert]
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:example.org
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 \
  -keyout "$out/ca.key" -out "$out/ca.pem" \
  -subj '/CN=EP02 Ephemeral Android HTTPS Test CA' \
  -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -addext 'subjectKeyIdentifier=hash' \
  -addext 'authorityKeyIdentifier=keyid:always,issuer'

openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -keyout "$out/server.key" -out "$out/server.csr" -subj '/CN=example.org'
openssl x509 -req -sha256 -days 3650 -in "$out/server.csr" \
  -CA "$out/ca.pem" -CAkey "$out/ca.key" -CAcreateserial \
  -out "$out/server.pem" -extfile "$out/server.ext" -extensions server_cert

# The Android 7 / API 24 KeyStore can read the legacy interoperable PKCS#12 PBE suite.
openssl pkcs12 -export -inkey "$out/server.key" -in "$out/server.pem" \
  -certfile "$out/ca.pem" -out "$assets/ep02_test_server.p12" \
  -passout "pass:$password" -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1
cp "$out/ca.pem" "$raw/ep02_test_ca.pem"
chmod 600 "$out/ca.key" "$out/server.key" "$assets/ep02_test_server.p12"
openssl verify -CAfile "$out/ca.pem" -purpose sslserver -verify_hostname example.org "$out/server.pem"
echo 'Generated an ephemeral test-only CA, example.org server certificate, and Android PKCS#12 fixture.'
