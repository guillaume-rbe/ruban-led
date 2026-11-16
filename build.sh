#!/usr/bin/env bash
# Builds the Ruban LED app with plain SDK tools (like binnacle/app/build.sh): build/ruban-led.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-35/android.jar"
JDK="${RUBAN_JDK:-/c/Program Files/Android/Android Studio/jbr}"
OUT=build
export JAVA_HOME="$JDK"; export PATH="$JDK/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes"

"$BT/aapt2" compile --dir res -o "$OUT/res/"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 29 "$OUT"/res/*.flat

"$JDK/bin/javac" -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')
"$BT/d8.bat" --min-api 26 --release --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

python -I - "$OUT/unsigned.apk" "$OUT/classes.dex" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a') as z:
    z.write(sys.argv[2], 'classes.dex')
EOF
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --out "$OUT/ruban-led.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify "$OUT/ruban-led.apk"
echo "built $OUT/ruban-led.apk"
