#!/usr/bin/env bash
# Run from a normal terminal or CI. A connected device is optional unless --connected is passed.
set -euo pipefail

connected=0
gradle_args=(--no-daemon)
while (($#)); do
    case "$1" in
        --connected) connected=1; shift ;;
        --help|-h)
            cat <<'USAGE'
Usage: bash scripts/verify_android.sh [--connected] [-- GRADLE_OPTIONS...]

Builds debug and test APKs, runs JVM tests, lint, backend tests and migration SQL tests.
--connected also installs and runs Android instrumentation tests on one authorized device.
Select a device with ANDROID_SERIAL when more than one is attached.
Reports and APKs are copied to build/verification/run-* even if a check fails.
Pass Gradle options after --, for example:
  bash scripts/verify_android.sh --connected -- -Pstockflow.debugApiBaseUrl=http://127.0.0.1:8080/
USAGE
            exit 0 ;;
        --) shift; gradle_args+=("$@"); break ;;
        *) printf 'Unknown argument: %s\nUse --help for usage.\n' "$1" >&2; exit 2 ;;
    esac
done

root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"
output_root="${STOCKFLOW_VERIFY_OUTPUT:-$root_dir/build/verification}"
mkdir -p "$output_root"
run_dir="$(mktemp -d "$output_root/run-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
clean_succeeded=0
printf 'step\texit_code\n' > "$run_dir/results.tsv"

finish() {
    local result="$1"
    trap - EXIT
    set +e
    mkdir -p "$run_dir/artifacts"
    # Never present artifacts from an older build as results of a failed new attempt.
    if ((clean_succeeded)); then
        for entry in outputs/apk reports test-results outputs/androidTest-results; do
            if [[ -d "app/build/$entry" ]]; then
                cp -R "app/build/$entry" "$run_dir/artifacts/$(basename "$entry")"
            fi
        done
        if [[ -d app/schemas ]]; then cp -R app/schemas "$run_dir/artifacts/schemas"; fi
    fi
    printf 'exit_code=%s\nconnected_requested=%s\n' "$result" "$connected" > "$run_dir/status.txt"
    printf '\nVerification exit code: %s\nLogs and artifacts: %s\n' "$result" "$run_dir"
    exit "$result"
}
trap 'finish "$?"' EXIT

run_step() {
    local name="$1"
    shift
    local result
    local -a pipeline_codes
    printf '\nRunning %s\n' "$name"
    set +e
    "$@" 2>&1 | tee "$run_dir/$name.log"
    pipeline_codes=("${PIPESTATUS[@]}")
    result="${pipeline_codes[0]}"
    if ((result == 0 && pipeline_codes[1] != 0)); then result="${pipeline_codes[1]}"; fi
    set -e
    printf '%s\t%s\n' "$name" "$result" >> "$run_dir/results.tsv"
    return "$result"
}

command -v python3 >/dev/null || { printf 'Python 3.10+ is required.\n' >&2; exit 2; }
python3 -c 'import sys; assert sys.version_info >= (3, 10), "Python 3.10+ is required"'

if [[ -z "${JAVA_HOME:-}" && -x '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java' ]]; then
    export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_version="$("$java_bin" -version 2>&1)" || { printf 'Set JAVA_HOME to JDK 17–23.\n' >&2; exit 2; }
java_major="$(printf '%s\n' "$java_version" | python3 -c 'import re,sys; m=re.search(r"version \"(\d+)",sys.stdin.read()); print(m[1] if m else 0)')"
if ((java_major < 17 || java_major > 23)); then
    printf 'JDK 17–23 is required; selected Java version:\n%s\n' "$java_version" >&2
    exit 2
fi
printf '%s\n' "$java_version" > "$run_dir/java-version.txt"

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" && -f local.properties ]]; then
    sdk_dir="$(python3 - <<'PY'
from pathlib import Path
for line in Path('local.properties').read_text().splitlines():
    if line.strip().startswith('sdk.dir='):
        print(line.strip().split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ').replace('\\\\', '\\'))
        break
PY
)"
fi
if [[ -z "$sdk_dir" && -d "$HOME/Library/Android/sdk" ]]; then sdk_dir="$HOME/Library/Android/sdk"; fi
if [[ -z "$sdk_dir" || ! -f "$sdk_dir/platforms/android-36/android.jar" ]]; then
    printf 'Install Android SDK platform 36 and set ANDROID_HOME, or configure sdk.dir in local.properties.\n' >&2
    exit 2
fi
export ANDROID_HOME="$sdk_dir"
export ANDROID_SDK_ROOT="$sdk_dir"

overall=0
run_step backend python3 -W error::ResourceWarning -m unittest discover -s backend -v || overall=1
run_step migration-sql python3 scripts/check_migrations.py || overall=1

build_android() {
    if run_step android-clean bash ./gradlew :app:clean --stacktrace --console=plain "${gradle_args[@]}"; then
        clean_succeeded=1
    else
        return 1
    fi
    run_step android-build bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --stacktrace --console=plain "${gradle_args[@]}"
}

if build_android; then
    if ((connected)); then
        adb_bin="$sdk_dir/platform-tools/adb"
        if [[ ! -x "$adb_bin" ]]; then
            printf 'Android platform-tools (adb) is required for --connected.\n' >&2
            printf 'device\t2\n' >> "$run_dir/results.tsv"
            overall=1
        elif run_step devices "$adb_bin" devices -l; then
            if selected_serial="$(python3 - "$run_dir/devices.log" <<'PY'
import os, sys
from pathlib import Path
rows = [line.split() for line in Path(sys.argv[1]).read_text().splitlines()]
devices = [row[0] for row in rows if len(row) >= 2 and row[1] == 'device']
requested = os.environ.get('ANDROID_SERIAL')
if requested and requested in devices:
    print(requested)
elif not requested and len(devices) == 1:
    print(devices[0])
else:
    print('Start an emulator or authorize USB debugging. Set ANDROID_SERIAL if several devices are attached.', file=sys.stderr)
    sys.exit(2)
PY
)"; then
                export ANDROID_SERIAL="$selected_serial"
                run_step instrumentation bash ./gradlew :app:connectedDebugAndroidTest --stacktrace --console=plain "${gradle_args[@]}" || overall=1
            else
                printf 'device-selection\t2\n' >> "$run_dir/results.tsv"
                overall=1
            fi
        else
            overall=1
        fi
    fi
else
    overall=1
    if ((connected)); then printf 'instrumentation-skipped-after-build-failure\t1\n' >> "$run_dir/results.tsv"; fi
fi
exit "$overall"
