#!/usr/bin/env bash
# build.sh — Build + sign X9RootOTA.apk without Gradle (aapt2 + javac + d8).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
SHIZUKU_API="${SHIZUKU_API:-$ROOT/Shizuku-API}"
if [ ! -d "$SHIZUKU_API" ]; then SHIZUKU_API="$ROOT/../Shizuku-API"; fi

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/37.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
FRAMEWORK_AIDL="$SDK/platforms/android-36/framework.aidl"
PLATFORM_DIR="$SDK/platforms/android-36"

VERSION_CODE="${VERSION_CODE:-3002}"
VERSION_NAME="${VERSION_NAME:-3.2}"
OUT="${OUT:-$ROOT/build}"

WORK="$OUT/work"
rm -rf "$WORK"
mkdir -p "$WORK/src" "$WORK/gen" "$WORK/classes" "$WORK/dex" "$WORK/aidl" \
         "$WORK/stubs/androidx/annotation"

echo "[*] copying sources"
cp -r "$SHIZUKU_API/api/src/main/java/"* "$WORK/src/"
cp -r "$SHIZUKU_API/shared/src/main/java/"* "$WORK/src/"
cp -r "$SHIZUKU_API/provider/src/main/java/"* "$WORK/src/"
cp -r "$SHIZUKU_API/aidl/src/main/aidl/"* "$WORK/aidl/"
cp -r "$APP/src/main/java/"* "$WORK/src/"
cp -r "$APP/src/main/aidl/"* "$WORK/aidl/"

# androidx.annotation stubs (the Shizuku API only uses the annotations)
cat > "$WORK/stubs/androidx/annotation/NonNull.java" <<'EOF'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
public @interface NonNull {}
EOF
cat > "$WORK/stubs/androidx/annotation/Nullable.java" <<'EOF'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
public @interface Nullable {}
EOF
cat > "$WORK/stubs/androidx/annotation/RestrictTo.java" <<'EOF'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
public @interface RestrictTo { Scope[] value(); enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, TESTS, SUBCLASSES } }
EOF

# The API uses android.util.ArraySet (hidden); swap it for a HashSet.
if [ -f "$WORK/src/rikka/shizuku/ShizukuRemoteProcess.java" ]; then
  sed -i '/import android\.util\.ArraySet;/d' \
         "$WORK/src/rikka/shizuku/ShizukuRemoteProcess.java"
  sed -i 's/new ArraySet<>()/new java.util.HashSet<>()/g' \
         "$WORK/src/rikka/shizuku/ShizukuRemoteProcess.java"
fi

echo "[*] aidl"
while IFS= read -r -d '' f; do
  "$BT/aidl" -I"$WORK/aidl" -I"$PLATFORM_DIR" -p"$FRAMEWORK_AIDL" -o"$WORK/gen" "$f"
done < <(find "$WORK/aidl" -name '*.aidl' -print0)

echo "[*] javac"
find "$WORK/src" "$WORK/gen" "$WORK/stubs" -name '*.java' > "$WORK/sources.txt"
javac --release 11 -nowarn -classpath "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"

echo "[*] d8"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 30 --output "$WORK/dex" @"$WORK/classes.txt"

echo "[*] aapt2"
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/base.apk" -I "$ANDROID_JAR" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  -A "$APP/src/main/assets" \
  --min-sdk-version 30 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$WORK/res.zip"

echo "[*] package + align"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
( cd "$WORK/dex" && zip -q -X "$WORK/unsigned.apk" classes.dex )
# Bundle native libs uncompressed (-0) so zipalign can 16 KB-align them
JNI="$APP/src/main/jniLibs"
if [ -d "$JNI/arm64-v8a" ]; then
  mkdir -p "$WORK/lib/arm64-v8a"
  cp "$JNI/arm64-v8a/"*.so "$WORK/lib/arm64-v8a/"
  ( cd "$WORK" && zip -q -X -0 unsigned.apk lib/arm64-v8a/*.so )
fi
# -P 16: align uncompressed .so files to 16 KB page boundaries (Android 15+)
"$BT/zipalign" -f -P 16 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

echo "[*] sign"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
STORE_PASS="${STORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-android}"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
mkdir -p "$OUT"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT/VSOCB-${VERSION_NAME}.apk" "$WORK/aligned.apk"

echo "built $OUT/VSOCB-${VERSION_NAME}.apk"
