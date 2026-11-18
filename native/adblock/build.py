"""Build Peachy's JNI library from locked sources; never use checked-in binaries."""
import argparse
import os
import pathlib
import platform
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
TARGETS = {
    'arm64-v8a': ('aarch64-linux-android', 'aarch64-linux-android'),
    'armeabi-v7a': ('armv7-linux-androideabi', 'armv7a-linux-androideabi'),
    'x86': ('i686-linux-android', 'i686-linux-android'),
    'x86_64': ('x86_64-linux-android', 'x86_64-linux-android'),
}
NDK = '28.2.13676358'


def main():
    args = argparse.ArgumentParser()
    args.add_argument('profile', choices=['debug', 'release'])
    args.add_argument('output', type=pathlib.Path)
    args.add_argument('--perf-qa', action='store_true', help='Build the arm64-only isolated measurement library')
    args = args.parse_args()
    output = args.output.resolve()
    # Windows Gradle can use the existing Debian compiler without copying a Linux NDK to Windows.
    if os.name == 'nt' and not os.getenv('ANDROID_NDK_HOME'):
        def linux_path(path):
            return subprocess.check_output(['wsl.exe', '-d', 'Debian', '--exec', 'wslpath', '-a', path.as_posix()], text=True).strip()
        subprocess.run(['wsl.exe', '-d', 'Debian', '--exec', 'python3', linux_path(ROOT / 'build.py'), args.profile, linux_path(output), *(['--perf-qa'] if args.perf_qa else [])], check=True)
        return
    home = pathlib.Path.home()
    ndk = pathlib.Path(os.environ.get('ANDROID_NDK_HOME', home / 'Android' / 'ndk' / NDK))
    if f'Pkg.Revision = {NDK}' not in (ndk / 'source.properties').read_text():
        sys.exit(f'Expected Android NDK {NDK} at {ndk}')
    host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    tools = ndk / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin'
    env = os.environ.copy()
    private = home / '.local' / 'share' / 'peachy-adblock'
    pinned = private / 'toolchain-1.99.1' / 'usr' / 'bin'
    if pinned.is_dir():
        env['PATH'] = str(pinned) + os.pathsep + env['PATH']
        env.setdefault('CARGO_HOME', str(private / 'cargo'))
    cargo = shutil.which('cargo', path=env['PATH'])
    rustc = shutil.which('rustc', path=env['PATH'])
    if not cargo or not rustc or not subprocess.check_output([rustc, '-V'], text=True).startswith('rustc 1.99.1 '):
        sys.exit('Rust 1.99.1 and the four Android targets are required; nothing is installed automatically')
    cache = pathlib.Path(env.setdefault('CARGO_TARGET_DIR', str(home / '.cache' / 'peachy-adblock' / 'target')))
    for abi, (target, clang_target) in TARGETS.items():
        if args.perf_qa and abi != 'arm64-v8a':
            continue
        linker = str(tools / (clang_target + '26-clang' + ('.cmd' if os.name == 'nt' else '')))
        key = target.upper().replace('-', '_')
        env[f'CARGO_TARGET_{key}_LINKER'] = linker
        env[f'CARGO_TARGET_{key}_RUSTFLAGS'] = '-C link-arg=-Wl,-z,max-page-size=16384'
        env['CC_' + target.replace('-', '_')] = linker
        subprocess.run([cargo, 'build', '--manifest-path', str(ROOT / 'Cargo.toml'), '--locked', '--target', target, *(['--release'] if args.profile == 'release' else []), *(['--features', 'perf-qa'] if args.perf_qa else [])], env=env, check=True)
        destination = output / abi / 'libpeachy_adblock.so'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(cache / target / args.profile / destination.name, destination)


if __name__ == '__main__':
    main()
