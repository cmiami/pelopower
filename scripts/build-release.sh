#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
expected=50391e973e547fcd3885678150f3b77f0541edee126d7b28fa14614c748c93cc
./gradlew testDebugUnitTest assembleHeadless
apk=app/build/outputs/apk/headless/app-headless.apk
certs=$("$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$apk")
signers=$(sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' <<<"$certs")
if [[ "$signers" != "$expected" ]]; then
    echo "Signing certificate differs from the v1.3.1 release. Check the configured keystore." >&2
    exit 1
fi
echo "Verified release APK: $apk"
