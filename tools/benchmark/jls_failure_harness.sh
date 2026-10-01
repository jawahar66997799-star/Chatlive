#!/usr/bin/env bash
set -euo pipefail

# Jawahar Live Sync failure-injection helper.
# Linux netem operations require root and should be run only on a dedicated
# test interface/namespace; do not run on a remote production SSH interface.
#
# Examples:
#   sudo IFACE=eth0 ./jls_failure_harness.sh netem loss1
#   sudo IFACE=eth0 ./jls_failure_harness.sh netem jitter50
#   sudo IFACE=eth0 ./jls_failure_harness.sh netem outage1000
#   SERIAL=... ./jls_failure_harness.sh android wifi500
#   SERIAL=... ./jls_failure_harness.sh android lock
#
# Every command injects a failure; it does not claim a measured result.

MODE="\${1:-}"
CASE="\${2:-}"
IFACE="\${IFACE:-eth0}"
ADB=(adb)
if [[ -n "\${SERIAL:-}" ]]; then ADB+=( -s "$SERIAL" ); fi

clear_netem() {
  tc qdisc del dev "$IFACE" root 2>/dev/null || true
}

apply_netem() {
  clear_netem
  case "$1" in
    loss1)      tc qdisc add dev "$IFACE" root netem loss 1% ;;
    loss5)      tc qdisc add dev "$IFACE" root netem loss 5% ;;
    loss10)     tc qdisc add dev "$IFACE" root netem loss 10% ;;
    jitter50)   tc qdisc add dev "$IFACE" root netem delay 60ms 50ms distribution normal ;;
    jitter200)  tc qdisc add dev "$IFACE" root netem delay 120ms 200ms distribution normal ;;
    asym-up)    tc qdisc add dev "$IFACE" root netem delay 180ms 20ms ;;
    outage500|outage1000|outage3000)
      local ms="\${1#outage}"
      tc qdisc add dev "$IFACE" root netem loss 100%
      python3 - "$ms" <<'PY'
import sys,time
time.sleep(float(sys.argv[1])/1000.0)
PY
      clear_netem
      ;;
    clear) clear_netem ;;
    *) echo "unknown netem case: $1" >&2; exit 2 ;;
  esac
}

android_case() {
  local c="$1"
  case "$c" in
    wifi500|wifi1000|wifi3000)
      local ms="\${c#wifi}"
      "\${ADB[@]}" shell svc wifi disable
      python3 - "$ms" <<'PY'
import sys,time
time.sleep(float(sys.argv[1])/1000.0)
PY
      "\${ADB[@]}" shell svc wifi enable
      ;;
    wifi-to-cell)
      "\${ADB[@]}" shell svc data enable || true
      "\${ADB[@]}" shell svc wifi disable
      echo "Wi-Fi disabled; verify cellular route and public IP changed, then re-enable Wi-Fi manually."
      ;;
    ip-change)
      "\${ADB[@]}" shell svc wifi disable
      sleep 2
      "\${ADB[@]}" shell svc wifi enable
      echo "Reconnect to a different AP/VPN/NAT if a guaranteed public IP change is required."
      ;;
    home)
      "\${ADB[@]}" shell input keyevent KEYCODE_HOME
      ;;
    lock)
      "\${ADB[@]}" shell input keyevent KEYCODE_POWER
      ;;
    youtube-pause)
      "\${ADB[@]}" shell input keyevent KEYCODE_MEDIA_PAUSE
      ;;
    youtube-resume)
      "\${ADB[@]}" shell input keyevent KEYCODE_MEDIA_PLAY
      ;;
    youtube-next)
      "\${ADB[@]}" shell input keyevent KEYCODE_MEDIA_NEXT
      ;;
    youtube-seek)
      echo "Seek is intentionally manual: open YouTube and move the scrubber while capture/stream logs are running."
      ;;
    playlist-switch)
      echo "Playlist switch is intentionally manual because UI coordinates vary by device/account."
      ;;
    ad-transition)
      echo "Ad transition cannot be forced reliably; keep telemetry running until a natural transition or use a test scenario where one occurs."
      ;;
    *)
      echo "unknown Android case: $c" >&2
      exit 2
      ;;
  esac
}

local_relay_restart() {
  local pid="\${RELAY_PID:-}"
  local cmd="\${RELAY_CMD:-go run .}"
  if [[ -z "$pid" ]]; then
    echo "Set RELAY_PID to the local relay PID; optional RELAY_CMD controls restart command." >&2
    exit 2
  fi
  kill "$pid" || true
  sleep 1
  (cd "\${RELAY_DIR:-relay}" && nohup bash -lc "$cmd" > /tmp/jls-relay-restart.log 2>&1 &)
  echo "Local relay restarted. Guest probe should reconnect automatically; host probe must be restarted/reconnected separately."
}

case "$MODE" in
  netem) apply_netem "$CASE" ;;
  android) android_case "$CASE" ;;
  relay)
    if [[ "$CASE" == "restart-local" ]]; then local_relay_restart; else echo "unknown relay case" >&2; exit 2; fi
    ;;
  *)
    cat <<EOF
Usage:
  $0 netem {loss1|loss5|loss10|jitter50|jitter200|outage500|outage1000|outage3000|clear}
  $0 android {wifi500|wifi1000|wifi3000|wifi-to-cell|ip-change|home|lock|youtube-pause|youtube-resume|youtube-next|youtube-seek|playlist-switch|ad-transition}
  $0 relay restart-local
EOF
    exit 2
    ;;
esac
