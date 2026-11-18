"""Check actual packaged ABI, native machine and 16 KiB ELF/ZIP layout; no Android device needed."""
import argparse
import struct
import zipfile

MACHINES = {'armeabi-v7a': 40, 'arm64-v8a': 183, 'x86': 3, 'x86_64': 62}

def verify(apk):
    seen = set()
    with open(apk, 'rb') as source, zipfile.ZipFile(source) as archive:
        for entry in archive.infolist():
            fields = entry.filename.split('/')
            if len(fields) != 3 or fields[0] != 'lib' or not fields[2].endswith('.so'):
                continue
            abi, library = fields[1:]
            blob = archive.read(entry)
            if blob[:4] != b'\x7fELF' or blob[5] != 1 or blob[4] not in (1, 2):
                raise ValueError(f'{entry.filename}: not an Android ELF')
            is64 = blob[4] == 2
            if abi not in MACHINES or struct.unpack_from('<H', blob, 18)[0] != MACHINES[abi] or is64 != (abi in ('arm64-v8a', 'x86_64')):
                raise ValueError(f'{entry.filename}: invalid machine or ELF class')
            if library == 'libpeachy_adblock.so':
                if abi in seen:
                    raise ValueError(f'{entry.filename}: duplicate')
                seen.add(abi)
            if not is64:
                continue
            phoff = struct.unpack_from('<Q', blob, 32)[0]
            entry_size, count = struct.unpack_from('<HH', blob, 54)
            load = 0
            for n in range(count):
                pos = phoff + n * entry_size
                if struct.unpack_from('<I', blob, pos)[0] == 1:
                    load += 1
                    off, addr = struct.unpack_from('<QQ', blob, pos + 8)
                    align = struct.unpack_from('<Q', blob, pos + 48)[0]
                    if align < 16384 or (off - addr) % 16384 != 0:
                        raise ValueError(f'{entry.filename}: incompatible PT_LOAD')
            if not load:
                raise ValueError(f'{entry.filename}: missing PT_LOAD')
            if entry.compress_type == zipfile.ZIP_STORED:
                source.seek(entry.header_offset + 26)
                name, extra = struct.unpack('<HH', source.read(4))
                if (entry.header_offset + 30 + name + extra) % 16384:
                    raise ValueError(f'{entry.filename}: incompatible ZIP data offset')
    if seen != MACHINES.keys():
        raise ValueError(f'{apk}: missing blockers for {MACHINES.keys() - seen}')
    print(f'{apk}: four JNI ABIs; all 64-bit ELF and stored native ZIP entries support 16 KiB alignment')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('apk', nargs='+')
    for filename in parser.parse_args().apk:
        verify(filename)
