#!/usr/bin/env bash
# Installs Elder Launcher on an Android 11+ phone over ADB and grants what it needs.
# Run ./install.sh --help for the options.
set -euo pipefail

PKG=io.github.cfpandrade.elderlauncher
REPO=cfpandrade/elder-launcher
CONFIG_URI="content://$PKG.config"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERIAL="" APK="" CONFIG="" PHOTOS="" REMOTE="" SERVER_URL="" TOKEN_FILE="" TOKEN="" NO_SERVER=0
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

  Control plane (optional: a service you host on your own server, not on the phone).
  Without these options the installer asks, or keeps the current choice when it cannot ask.
      --no-server         Standalone: no server, and forget one configured before
      --server URL        Use the control plane at this https:// address
      --token-file FILE   File with the token for --server (asked for if missing)
      --remote FILE       Use an existing remote.json with {"url": ..., "token": ...}

      --set KEY=VALUE     Change a setting (repeatable), e.g. --set night_brightness=30
      --block-power-menu  Close the power menu automatically (enables an accessibility service; experimental)
      --keep-debugging    Keep wireless debugging on; with a control plane, also report its port to it
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
    --server) SERVER_URL="${2:?}"; shift 2 ;;
    --token-file) TOKEN_FILE="${2:?}"; shift 2 ;;
    --no-server) NO_SERVER=1; shift ;;
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
[ -z "$TOKEN_FILE" ] || [ -f "$TOKEN_FILE" ] || die "not found: $TOKEN_FILE"
MODES=$NO_SERVER
[ -z "$SERVER_URL" ] || MODES=$((MODES + 1))
[ -z "$REMOTE" ] || MODES=$((MODES + 1))
[ "$MODES" -le 1 ] || die "choose only one of --no-server, --server and --remote."
[ -z "$TOKEN_FILE" ] || [ -n "$SERVER_URL" ] || die "--token-file needs --server."
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
# Hands a local file to the app (see ConfigProvider.java).
put() {
  if [ "$DRY_RUN" = 1 ]; then echo "    [dry-run] adb shell content write --uri $CONFIG_URI/$2 < $1" >&3; return 0; fi
  "${ADB[@]}" shell content write --uri "$CONFIG_URI/$2" < "$1"
}
# Whether the app already has that file; no otherwise, also when the app is not installed yet.
has() { q content query --uri "$CONFIG_URI/$1" 2>/dev/null | grep -q 'exists=1'; }
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

cleanup() { [ -z "$TMP" ] || rm -rf "$TMP"; }
trap cleanup EXIT
TMP=$(mktemp -d)

# --- control plane (optional) ---------------------------------------------------
valid_url() { [[ "$1" =~ ^https://[^[:space:]\"\\]+$ ]]; }
valid_token() { [[ "$1" =~ ^[^[:space:]\"\\]+$ ]]; }

ask_token() {
  [ -t 0 ] || die "--server needs --token-file when it cannot ask."
  read -rsp "Token: " TOKEN; echo
}

# Warns only: the server may be reachable from the phone's network and not from this computer.
check_server() {
  command -v curl >/dev/null || return 0
  printf 'X-Launcher-Token: %s\n' "$TOKEN" > "$TMP/headers"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H "@$TMP/headers" "${SERVER_URL%/}/api/launcher") || code=000
  case "$code" in
    200) info "The control plane answered correctly" ;;
    401|403) warn "the control plane rejected the token (HTTP $code)." ;;
    000) warn "could not reach the control plane from this computer. The phone must be able to reach it, with a trusted certificate." ;;
    *) warn "the control plane answered HTTP $code on /api/launcher." ;;
  esac
}

ask_server() {
  local current=2 answer
  HAS_REMOTE=$1
  echo
  echo "Control plane (optional)"
  echo "  The phone works on its own. A control plane is a web service you host on your own always-on"
  echo "  server, to manage buttons and settings from afar. It does not run on the phone and it is not"
  echo "  included: see \"Control plane\" in the README."
  echo "    1) No server: contacts come from contacts.json"
  echo "    2) Use my server"
  [ "$HAS_REMOTE" = 0 ] || echo "    3) Keep the server already configured on this phone"
  [ "$HAS_REMOTE" = 0 ] && current=1 || current=3
  read -rp "Choose [$current]: " answer
  case "${answer:-$current}" in
    1) NO_SERVER=1 ;;
    2) read -rp "Server address (https://...): " SERVER_URL ;;
    3) [ "$HAS_REMOTE" = 1 ] || die "not an option: 3" ;;
    *) die "not an option: $answer" ;;
  esac
}

HAS_REMOTE=0
! has remote.json || HAS_REMOTE=1
if [ "$NO_SERVER" = 0 ] && [ -z "$SERVER_URL" ] && [ -z "$REMOTE" ] && [ -t 0 ]; then ask_server "$HAS_REMOTE"; fi

if [ -n "$REMOTE" ] && command -v python3 >/dev/null; then
  { IFS= read -r SERVER_URL; IFS= read -r TOKEN; } < <(python3 -c '
import json, sys
o = json.load(open(sys.argv[1]))
print(o.get("url", "")); print(o.get("token", ""))' "$REMOTE") || die "$REMOTE is not valid JSON."
fi
if [ -n "$SERVER_URL" ]; then
  valid_url "$SERVER_URL" || die "the server address must start with https:// (Android blocks unencrypted traffic for this app)."
  if [ -z "$REMOTE" ]; then
    if [ -n "$TOKEN_FILE" ]; then TOKEN=$(head -n 1 "$TOKEN_FILE" | tr -d '\r\n'); else ask_token; fi
    valid_token "$TOKEN" || die "the token cannot be empty or contain spaces, quotes or backslashes."
    REMOTE="$TMP/remote.json"
    ( umask 077; printf '{"url": "%s", "token": "%s"}\n' "${SERVER_URL%/}" "$TOKEN" > "$REMOTE" )
  fi
  check_server
fi

# What the phone ends up with
if [ -n "$REMOTE" ]; then USES_SERVER=1
elif [ "$NO_SERVER" = 1 ]; then USES_SERVER=0
else USES_SERVER=$HAS_REMOTE; fi
[ "$KEEP_DEBUG" = 0 ] || [ "$USES_SERVER" = 1 ] \
  || warn "--keep-debugging without a control plane: nothing reports the port, which changes; read it on the phone."

# --- APK -----------------------------------------------------------------------
if [ -z "$APK" ] && [ -f "$HERE/build/elder-launcher.apk" ]; then APK="$HERE/build/elder-launcher.apk"; fi
if [ -z "$APK" ]; then
  command -v curl >/dev/null || die "curl not found; download the APK yourself and pass --apk."
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
# A control plane can turn on the power menu guard and wireless debugging, which need this
if [ "$BLOCK_POWER" = 1 ] || [ "$KEEP_DEBUG" = 1 ] || [ "$USES_SERVER" = 1 ]; then
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
if [ -n "$CONFIG" ]; then
  command -v python3 >/dev/null && { python3 -m json.tool "$CONFIG" >/dev/null || die "$CONFIG is not valid JSON."; }
  info "Pushing contacts"
  put "$CONFIG" contacts.json || die "could not copy the contacts to the phone."
elif [ "$USES_SERVER" = 0 ] && ! has contacts.json; then
  warn "no --config given and no server: the home screen has no contact buttons (see contacts.example.json)."
fi
if [ -n "$PHOTOS" ]; then
  info "Pushing photos"
  for f in "$PHOTOS"/*; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    if [[ ! "$name" =~ ^[A-Za-z0-9_-][A-Za-z0-9._-]*$ ]]; then
      warn "skipped $name: use only letters, numbers, dots, dashes and underscores in photo names."
      continue
    fi
    put "$f" "photos/$name" || die "could not copy $name to the phone."
  done
fi
if [ -n "$REMOTE" ]; then
  info "Connecting the phone to the control plane"
  put "$REMOTE" remote.json || die "could not copy the server address to the phone."
elif [ "$NO_SERVER" = 1 ] && [ "$HAS_REMOTE" = 1 ]; then
  info "Disconnecting the phone from the control plane"
  run shell content delete --uri "$CONFIG_URI/remote.json" >/dev/null
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
if [ "$USES_SERVER" = 1 ]; then info "Done. Mode: managed by your control plane; the phone checks it when returning home."
else info "Done. Mode: standalone, no server."; fi
echo "Next: make sure WhatsApp is installed and signed in, then check it from the phone (see the README)."
