#!/usr/bin/env bash
# Installs Elder Launcher on an Android 11+ phone over ADB and grants what it needs.
# Run ./install.sh --help for the options.
set -euo pipefail

PKG=io.github.cfpandrade.elderlauncher
REPO=cfpandrade/elder-launcher
DEVICE_DIR="/sdcard/Android/data/$PKG/files"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERIAL="" APK="" CONFIG="" PHOTOS="" REMOTE=""
BLOCK_POWER=0 KEEP_DEBUG=0 SET_HOME=1 DRY_RUN=0 UNINSTALL=0
SETTINGS=()
TMP=""

usage() {
  cat <<'USAGE'
Usage: ./install.sh [options]

  -s, --serial SERIAL     Target device, when several are connected
      --apk FILE          APK to install (default: build/elder-launcher.apk, else the latest release)
      --config FILE       contacts.json to push to the phone
      --photos DIR        Folder with the photos named in contacts.json
      --remote FILE       remote.json with {"url": ..., "token": ...} for remote management
      --set KEY=VALUE     Change a setting (repeatable), e.g. --set night_brightness=30
      --block-power-menu  Close the power menu automatically (enables an accessibility service; experimental)
      --keep-debugging    Keep wireless debugging on and report its port to your server (remote access)
      --no-home           Do not set it as the default home app
      --uninstall         Remove the app and everything it was granted
      --dry-run           Print the commands that change the phone without running them
  -h, --help              Show this help
USAGE
}

die() { echo "error: $*" >&2; exit 1; }
info() { echo "==> $*"; }
warn() { echo "warning: $*" >&2; }

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial) SERIAL="${2:?}"; shift 2 ;;
    --apk) APK="${2:?}"; shift 2 ;;
    --config) CONFIG="${2:?}"; shift 2 ;;
    --photos) PHOTOS="${2:?}"; shift 2 ;;
    --remote) REMOTE="${2:?}"; shift 2 ;;
    --set) [[ "${2:-}" == *=* ]] || die "--set needs KEY=VALUE"; SETTINGS+=("$2"); shift 2 ;;
    --block-power-menu) BLOCK_POWER=1; shift ;;
    --keep-debugging) KEEP_DEBUG=1; shift ;;
    --no-home) SET_HOME=0; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[ -z "$CONFIG" ] || [ -f "$CONFIG" ] || die "not found: $CONFIG"
[ -z "$PHOTOS" ] || [ -d "$PHOTOS" ] || die "not found: $PHOTOS"
[ -z "$REMOTE" ] || [ -f "$REMOTE" ] || die "not found: $REMOTE"
[ -z "$APK" ] || [ -f "$APK" ] || die "not found: $APK"
command -v adb >/dev/null || die "adb not found. Install Android platform-tools first."

exec 3>&1      # dry-run output survives the redirects used on quiet commands
ADB=(adb)
[ -z "$SERIAL" ] || ADB+=(-s "$SERIAL")

# Reads from the phone, always executed.
q() { "${ADB[@]}" shell "$@" </dev/null | tr -d '\r'; }
# Changes the phone: skipped by --dry-run. Failures are reported by the caller.
run() {
  if [ "$DRY_RUN" = 1 ]; then echo "    [dry-run] adb $*" >&3; return 0; fi
  "${ADB[@]}" "$@" </dev/null
}
# A grant that may be refused: warns with what to do and carries on.
try() {
  local why="$1"; shift
  run "$@" >/dev/null 2>&1 || warn "$why"
}

# --- device --------------------------------------------------------------------
if [ -z "$SERIAL" ]; then
  COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
  [ "$COUNT" -ge 1 ] || die "no authorized device. Enable USB debugging, connect the phone and accept the prompt."
  [ "$COUNT" -eq 1 ] || die "several devices connected: choose one with --serial."
fi
SDK=$(q getprop ro.build.version.sdk)
[ "${SDK:-0}" -ge 30 ] || die "Android 11 (API 30) or newer is required; this device has API ${SDK:-unknown}."
info "Device: $(q getprop ro.product.model), Android $(q getprop ro.build.version.release)"

# --- uninstall -----------------------------------------------------------------
if [ "$UNINSTALL" = 1 ]; then
  info "Disabling the power menu guard"
  run shell am broadcast -n "$PKG/.SettingsReceiver" -a "$PKG.SETTING" --es block_power_menu false >/dev/null 2>&1 || true
  run shell cmd notification disallow_listener "$PKG/$PKG.CallWatcherService" >/dev/null 2>&1 || true
  info "Removing the app"
  run uninstall "$PKG"
  echo "Done. Press the Home button and choose your previous launcher."
  exit 0
fi

# --- APK -----------------------------------------------------------------------
cleanup() { [ -z "$TMP" ] || rm -rf "$TMP"; }
trap cleanup EXIT
if [ -z "$APK" ] && [ -f "$HERE/build/elder-launcher.apk" ]; then APK="$HERE/build/elder-launcher.apk"; fi
if [ -z "$APK" ]; then
  command -v curl >/dev/null || die "curl not found; download the APK yourself and pass --apk."
  TMP=$(mktemp -d)
  BASE="https://github.com/$REPO/releases/latest/download"
  info "Downloading the latest release"
  curl -fsSL "$BASE/elder-launcher.apk" -o "$TMP/elder-launcher.apk" || die "could not download the APK."
  curl -fsSL "$BASE/elder-launcher.apk.sha256" -o "$TMP/elder-launcher.apk.sha256" || die "could not download the checksum."
  if command -v sha256sum >/dev/null; then (cd "$TMP" && sha256sum -c --status elder-launcher.apk.sha256)
  else (cd "$TMP" && shasum -a 256 -c -s elder-launcher.apk.sha256); fi || die "checksum mismatch: not installing."
  APK="$TMP/elder-launcher.apk"
fi

info "Installing $(basename "$APK")"
run install -r "$APK" || die "install failed. On Xiaomi phones also enable \"Install via USB\" in Developer options."

# --- permissions ---------------------------------------------------------------
SEC_HINT="On Xiaomi phones enable \"USB debugging (Security settings)\" in Developer options and run this again."
info "Granting permissions"
for p in READ_CONTACTS WRITE_CONTACTS; do
  try "could not grant $p. $SEC_HINT" shell pm grant "$PKG" "android.permission.$p"
done
try "could not allow brightness control. $SEC_HINT" shell appops set "$PKG" WRITE_SETTINGS allow
try "could not allow drawing over apps. $SEC_HINT" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
try "could not enable the notification listener; enable it in Settings > Notification access." \
  shell cmd notification allow_listener "$PKG/$PKG.CallWatcherService"
try "could not allow Do Not Disturb access." shell cmd notification allow_dnd "$PKG"
try "could not exempt it from battery optimization; do it by hand, or it may be killed." \
  shell dumpsys deviceidle whitelist "+$PKG"
if [ "$BLOCK_POWER" = 1 ] || [ "$KEEP_DEBUG" = 1 ]; then
  try "could not grant WRITE_SECURE_SETTINGS. $SEC_HINT" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
fi

# --- start it, so its folders exist ---------------------------------------------
if [ "$SET_HOME" = 1 ]; then
  info "Setting it as the home app"
  if ! run shell cmd package set-home-activity "$PKG/.HomeActivity" >/dev/null 2>&1; then
    warn "could not set the home app; press Home on the phone and choose Elder Launcher, \"Always\"."
  fi
fi
run shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
[ "$DRY_RUN" = 1 ] || sleep 2

# --- configuration --------------------------------------------------------------
run shell mkdir -p "$DEVICE_DIR/photos" >/dev/null
if [ -n "$CONFIG" ]; then
  command -v python3 >/dev/null && { python3 -m json.tool "$CONFIG" >/dev/null || die "$CONFIG is not valid JSON."; }
  info "Pushing contacts"
  run push "$CONFIG" "$DEVICE_DIR/contacts.json" >/dev/null
else
  warn "no --config given: the home screen has no contact buttons (see contacts.example.json)."
fi
if [ -n "$PHOTOS" ]; then
  info "Pushing photos"
  run push "$PHOTOS/." "$DEVICE_DIR/photos/" >/dev/null
fi
if [ -n "$REMOTE" ]; then
  info "Pushing the remote configuration"
  run push "$REMOTE" "$DEVICE_DIR/remote.json" >/dev/null
fi

# --- settings -------------------------------------------------------------------
[ "$BLOCK_POWER" = 0 ] || SETTINGS+=("block_power_menu=true")
[ "$KEEP_DEBUG" = 0 ] || SETTINGS+=("keep_wireless_debugging=true")
if [ "${#SETTINGS[@]}" -gt 0 ]; then
  EXTRAS=()
  for s in "${SETTINGS[@]}"; do EXTRAS+=(--es "${s%%=*}" "${s#*=}"); done
  info "Applying settings: ${SETTINGS[*]}"
  run shell am broadcast -n "$PKG/.SettingsReceiver" -a "$PKG.SETTING" "${EXTRAS[@]}" >/dev/null
fi

run shell am start -n "$PKG/.HomeActivity" >/dev/null 2>&1 || true
info "Done."
echo "Next: make sure WhatsApp is installed and signed in, then check it from the phone (see the README)."
