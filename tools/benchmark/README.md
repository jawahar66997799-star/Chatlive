# Jawahar Live Sync Benchmark Tools

All generated numbers must be tagged with one of:
[MEASURED], [SIMULATED], [LITERATURE], [ESTIMATE].

## 1. Multi-listener simulation

Run 20 listeners across Wi-Fi, good cellular, poor cellular, plus mixed:

    python3 tools/benchmark/jls_network_clock_sim.py \
      --listeners 20 --duration-s 300 --scenario all --mode both \
      --json-out jls_sim.json --md-out jls_sim.md

The current model approximates the checked-in raw-PCM guest prototype.
The adaptive model is a reference validation model only.

## 2. Relay synthetic host/guest

Terminal A:

    cd relay
    go run ./cmd/jlsprobe --role host --base http://127.0.0.1:8080 --room bench --seconds 120

Terminal B:

    cd relay
    go run ./cmd/jlsprobe --role guest --base http://127.0.0.1:8080 --room bench --seconds 120 --reconnect=true

## 3. Network failure injection

Use a disposable Linux test namespace/interface:

    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem loss1
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem loss5
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem loss10
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem jitter50
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem jitter200
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem outage500
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem outage1000
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem outage3000
    sudo IFACE=veth-test tools/benchmark/jls_failure_harness.sh netem clear

Do not apply netem to the interface carrying your only remote admin connection.

## 4. Android/YouTube failure injection

    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android wifi500
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android wifi1000
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android wifi3000
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android wifi-to-cell
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android ip-change
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android home
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android lock
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android youtube-pause
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android youtube-resume
    SERIAL=<adb-serial> tools/benchmark/jls_failure_harness.sh android youtube-next

Seek, playlist switch, and ad transition remain manual because reliable YouTube UI automation is device/account dependent.

## 5. Acoustic calibration

Generate 20 unique chirps:

    python3 tools/benchmark/jls_calibration.py generate \
      --devices 20 --out-dir calibration_chirps

Schedule each device's chirp at the manifest server time while one microphone records all speakers.

Analyze:

    python3 tools/benchmark/jls_calibration.py analyze \
      --manifest calibration_chirps/manifest.json \
      --recording room_recording.wav \
      --out jls_calibration_profile.json

Positive recommended_playout_advance_ms means that speaker arrived late at the microphone and should be scheduled earlier by that amount.

Synthetic analyzer verification:

    python3 tools/benchmark/jls_calibration_selftest.py

## 6. End-to-end latency reference

Audio only:

    python3 tools/benchmark/jls_generate_e2e_signal.py \
      --duration-s 120 \
      --out jls_e2e_reference.wav \
      --manifest jls_e2e_reference.json

Video with one visual flash per second:

    DURATION=120 tools/benchmark/jls_make_e2e_video.sh

## 7. Log analysis

Android CSV:

    python3 tools/benchmark/jls_log_analyzer.py capture-*.csv \
      --evidence MEASURED --gap-threshold-ms 1500 \
      --json-out capture_report.json --md-out capture_report.md

Relay/browser JSONL or CSV:

    python3 tools/benchmark/jls_log_analyzer.py relay.jsonl browser.jsonl \
      --evidence MEASURED --gap-threshold-ms 100 \
      --json-out stream_report.json --md-out stream_report.md

Never label simulator output MEASURED.
