"""Rebuild the APK's licence archive from the exact Cargo.lock and installed Rust runtime."""
import json
import pathlib
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[2]
metadata = json.loads(subprocess.check_output(['cargo', 'metadata', '--manifest-path', str(root / 'native/adblock/Cargo.toml'), '--locked', '--offline', '--filter-platform', 'aarch64-linux-android', '--format-version', '1']))
packages = {p['name']: p for p in metadata['packages']}
archive = root / 'app/src/main/assets/adblock/native-notices.zip'
rows = []
with zipfile.ZipFile(archive, 'w') as z:
    def put(name, data):
        entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        z.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED)
    for p in sorted(metadata['packages'], key=lambda p: (p['name'], p['version'])):
        if p['name'] == 'peachy-adblock':
            continue
        directory = pathlib.Path(p['manifest_path']).parent
        label = p['name'] + '-' + p['version']
        texts = sorted(f for f in directory.iterdir() if f.is_file() and f.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'UNLICENSE', 'COPYRIGHT')))
        if not texts:
            # Some published crates omit a copy of the declared licence. Include the full common
            # licence text separately; retain their OWN author and source metadata (no relicensing).
            if p['name'] in ('flatbuffers', 'jni-sys-macros'):
                texts = [pathlib.Path(packages['bitflags']['manifest_path']).parent / 'LICENSE-APACHE']
            elif p['name'] == 'selectors':
                texts = [pathlib.Path(packages['adblock']['manifest_path']).parent / 'LICENSE']
            elif p['name'] == 'seahash':
                sample = (pathlib.Path(packages['arrayvec']['manifest_path']).parent / 'LICENSE-MIT').read_text()
                put(label + '/MIT.txt', 'SeaHash contributors (see AUTHORS in INDEX).\n' + sample[sample.index('Permission is hereby granted'):]);
            else:
                raise ValueError(f'Unreviewed licence: {label}')
        rows.append(f"{label} | {p['license']} | AUTHORS: {', '.join(p['authors'])} | {p['repository']} | https://crates.io/api/v1/crates/{p['name']}/{p['version']}/download")
        for f in texts:
            put(label + '/' + f.name, f.read_bytes())
    rust = pathlib.Path(subprocess.check_output(['rustc','--print','sysroot'],text=True).strip()) / 'share/doc/rust'
    put('Rust-1.99.1/COPYRIGHT-library.html', (rust/'COPYRIGHT-library.html').read_bytes())
    put('INDEX.txt', '\n'.join(rows)+'\nRust standard library 1.99.1: https://static.rust-lang.org/dist/rustc-1.99.1-src.tar.xz\n')
print(archive, len(rows), 'locked packages')
