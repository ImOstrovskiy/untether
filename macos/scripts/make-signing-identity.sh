#!/bin/sh
# Creates a self-signed code signing identity "Untether Dev" in the login keychain.
# Rebuilt app bundles then keep the same signature, so the Keychain stops asking for access
# to the pairing after every build. Run once; `make app` picks the identity up automatically.
set -e
NAME="Untether Dev"
if security find-identity -p codesigning | grep -q "$NAME"; then
    echo "\"$NAME\" already exists"
    exit 0
fi
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
/usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=$NAME" \
    -addext "keyUsage=critical,digitalSignature" -addext "extendedKeyUsage=critical,codeSigning" \
    -keyout "$dir/key.pem" -out "$dir/cert.pem" 2>/dev/null
/usr/bin/openssl pkcs12 -export -inkey "$dir/key.pem" -in "$dir/cert.pem" -out "$dir/id.p12" -passout pass:pht
security import "$dir/id.p12" -k "$HOME/Library/Keychains/login.keychain-db" -P pht -T /usr/bin/codesign
echo "Created \"$NAME\""
