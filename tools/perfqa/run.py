"""Install only a fresh, permissionless QA APK on the named Xiaomi and run R8 measurements.

Build: gradlew -Ppeachy.perfQa=true :perfqa:assembleRelease
Run:   python tools/perfqa/run.py --sdk <Android SDK> --serial <verified USB serial>
Add --update-created-qa only to replace this run's own QA install for A/B verification.
No permission grant, cleanup of existing data, external storage, or network access.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent
QA = 'ru.spgsroot.peachy.perfqa'
RUNNER = QA + '/' + QA + '.QaRunner'


def run(*argv):
    return subprocess.check_output(argv, text=True, encoding='utf-8', errors='replace').strip()


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--update-created-qa', action='store_true')
    parser.add_argument('--scenario', choices=['combined', 'browser', 'ocr'], default='combined')
    args = parser.parse_args()
    exe = '.exe' if os.name == 'nt' else ''
    adb = args.sdk / 'platform-tools' / ('adb' + exe)
    aapt = args.sdk / 'build-tools/36.0.0' / ('aapt2' + exe)
    apk = ROOT / 'build/outputs/apk/release/perfqa-release.apk'
    def device(*argv):
        return run(adb, '-s', args.serial, *argv)
    require(device('get-state') == 'device' and device('shell', 'getprop', 'ro.product.model') == '22071212AG', 'Wrong device')
    require(device('shell', 'am', 'get-current-user') == '0', 'Wrong Android user')
    badging = run(aapt, 'dump', 'badging', apk)
    require(badging.startswith(f"package: name='{QA}' ") and 'application-debuggable' not in badging, 'Wrong package/build')
    require('uses-permission:' not in badging and 'uses-permission-sdk-' not in badging, 'Unexpected permission')
    manifest = run(aapt, 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml')
    require(re.search(r'android:targetPackage[^\n]*="' + re.escape(QA) + r'"', manifest) and 'QaRunner' in manifest, 'Wrong instrumentation target')
    present = device('shell', 'pm', 'list', 'packages', '--user', '0', QA).splitlines()
    require((f'package:{QA}' in present) == args.update_created_qa, 'Existing QA install or unexpected missing package; refusing')
    result = device('install', '--user', '0', *(['-r'] if args.update_created_qa else []), apk)
    print(result, flush=True)
    require('Success' in result, 'QA install failed')
    runners = device('shell', 'pm', 'list', 'instrumentation', QA).splitlines()
    require(any(line == f'instrumentation:{component} (target={QA})'
                for line in runners for component in (RUNNER, QA + '/.QaRunner')), 'Wrong installed runner')
    scenarios = [args.scenario]
    if args.scenario == 'combined': scenarios.extend(['legacyArray4096', 'canvas4096'])
    pids = set()
    for scenario in scenarios:
        device('shell', 'am', 'force-stop', '--user', '0', QA)
        output = device('shell', 'am', 'instrument', '--user', '0', '-w', '-r', '-e', 'scenario', scenario,
                        '-e', 'imageRasterMemory', 'true', RUNNER)
        print(output, flush=True)
        require('INSTRUMENTATION_CODE: -1' in output and 'FAIL' not in output, 'Instrumentation failed')
        pid = re.search(r'pid=(\d+)', output)
        require(pid and pid[1] not in pids, 'Independent process not verified')
        pids.add(pid[1])


if __name__ == '__main__':
    main()
