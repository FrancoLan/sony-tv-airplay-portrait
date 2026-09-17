#!/usr/bin/env bash
# Builds a portrait-capable AirPlay receiver for a sideways-mounted Android TV.
#
# Clones the upstream open-source receiver at a pinned commit, applies the
# rotation patch, and builds a sideloadable APK. Re-running is safe: the source
# tree is reset to the pinned commit and the patch is reapplied from scratch.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPSTREAM="https://github.com/jqssun/android-airplay-server.git"

# The NDK toolchain emits -ffile-prefix-map=<path> unquoted, so a space anywhere in
# the source path splits the flag and every C file fails to compile. This project
# lives under "SONY TV", so the build tree goes somewhere space-free and $HERE/src
# is just a symlink into it.
BUILD_ROOT="${AIRPLAY_BUILD_ROOT:-$HOME/.cache/airplay-portrait}"
case "$BUILD_ROOT" in
  *" "*) BUILD_ROOT="/tmp/airplay-portrait" ;;
esac
SRC="$BUILD_ROOT/src"
mkdir -p "$BUILD_ROOT"
[ -L "$HERE/src" ] || [ -e "$HERE/src" ] || ln -sfn "$SRC" "$HERE/src"

COMMIT="$(cat "$HERE/patches/UPSTREAM_COMMIT")"
PATCH="$HERE/patches/0001-portrait-rotation.patch"

# Kept in sync with upstream app/build.gradle.kts — a mismatch fails the native build.
NDK_VERSION="27.0.12077973"
CMAKE_VERSION="3.22.1"
COMPILE_SDK="36"

say() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null || die "git not found"
command -v java >/dev/null || die "No JDK. Install one:  brew install --cask temurin@17"

# ----------------------------------------------------------------- SDK
say "Locating the Android SDK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/local/share/android-sdk"; do
    [ -d "$candidate" ] && { SDK="$candidate"; break; }
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "No Android SDK found. Install Android Studio and open it once."
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
echo "    $SDK"

# The native core needs the NDK and CMake, which a stock Android Studio install
# does not include. Add them with sdkmanager rather than failing deep in CMake.
missing=()
[ -d "$SDK/ndk/$NDK_VERSION" ]     || missing+=("ndk;$NDK_VERSION")
[ -d "$SDK/cmake/$CMAKE_VERSION" ] || missing+=("cmake;$CMAKE_VERSION")
[ -d "$SDK/platforms/android-$COMPILE_SDK" ] || missing+=("platforms;android-$COMPILE_SDK")

if [ ${#missing[@]} -gt 0 ]; then
  say "Installing missing SDK components: ${missing[*]}"
  command -v sdkmanager >/dev/null \
    || die "sdkmanager not found. Install it:  brew install --cask android-commandlinetools"
  yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  sdkmanager --sdk_root="$SDK" "${missing[@]}"
fi

# ----------------------------------------------------------------- source
if [ ! -d "$SRC/.git" ]; then
  say "Cloning upstream receiver"
  git clone "$UPSTREAM" "$SRC"
fi

say "Checking out pinned commit ${COMMIT:0:12}"
git -C "$SRC" fetch --all --tags --quiet
git -C "$SRC" checkout --quiet --force "$COMMIT"
git -C "$SRC" reset --hard --quiet "$COMMIT"
git -C "$SRC" clean -fd --quiet -e local.properties

say "Applying portrait rotation patch"
git -C "$SRC" apply --verbose "$PATCH"

# Gradle reads the SDK path from here; without it the build dies at project configure.
# Upstream also takes its release signing config from this file, and only creates one
# when storeFile is present — without it assembleRelease emits an UNSIGNED apk that
# fails to install with INSTALL_PARSE_FAILED_NO_CERTIFICATES. Sideloading to your own
# TV doesn't need a real release key, so point it at the standard debug keystore.
DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
[ -f "$DEBUG_KEYSTORE" ] || die "No debug keystore at $DEBUG_KEYSTORE — build any project once with Android Studio, or run: keytool -genkey -v -keystore \"$DEBUG_KEYSTORE\" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'"
cat > "$SRC/local.properties" <<PROPS
sdk.dir=$SDK
storeFile=$DEBUG_KEYSTORE
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
PROPS

ln -sfn "$SRC" "$HERE/src"

say "Fetching native submodules (UxPlay core) — this takes a while the first time"
git -C "$SRC" submodule update --init --recursive

say "Building release APK"
( cd "$SRC" && ./gradlew --no-daemon assembleRelease )

APK="$(find "$SRC/app/build/outputs/apk" -name '*.apk' -print | head -1)"
[ -n "$APK" ] || die "Build finished but no APK was produced"
cp "$APK" "$HERE/airplay-portrait.apk"

say "Done:  $HERE/airplay-portrait.apk"
cat <<'NEXT'

Install it on the TV:
    adb connect <TV-IP>:5555
    adb install -r airplay-portrait.apk

Then in the app's Settings:
    Resolution      -> portrait     (makes the iPhone stream portrait natively)
    Picture rotation-> 90 or 270    (whichever way your TV is turned)
NEXT
