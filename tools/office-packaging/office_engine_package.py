#!/usr/bin/env python3
"""Validate a staged LibreOffice engine and prepare it for one debug APK variant.

Two subcommands:

  prepare         validate one local ABI stage and the pinned Java glue, then publish
                  one payload (assets, jniLibs, java, licences, provenance) into the
                  caller's preparation root. Atomic publish: a failed run leaves the
                  previous output untouched. Gradle then syncs each category into the
                  directories AGP assigns to its generated-source properties.
  verify-merged   after AGP merges native libraries, prove that every engine library
                  survived byte-for-byte in the prepared ABI and that no engine library
                  was injected into another ABI.

The stage is read-only input. This script never writes to it and never downloads
anything. All expected values (application id, ABI, source pin, tile mode, macro
flag, required files, Java glue hashes) are fixed here: a stage that disagrees is
rejected instead of being packaged.

Standard library only; run with Python 3.8+.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Fixed packaging contract (evidence: .impeccable/office-engine-report.md and
# .impeccable/office-bridge-report.md). Do not relax without a new verified stage.
# ---------------------------------------------------------------------------
APP_ID = "ru.spgsroot.peachy"
ELF_MACHINE_BY_ABI = {"x86_64": 62, "arm64-v8a": 183}
PINNED_TAG = "libreoffice-26.8.3.1"
PINNED_COMMIT = "861effc5289d539f15c4c18d1ba8579ffd78728b"
ANDROID_API_LEVEL = 26
MIN_SDK_APP = 26
TILE_MODE = "rgba"
MACRO_FLAG = "false"

# Every staged .so, exactly. A missing or additional library is a hard failure.
NSS_LIBS = (
    "libfreebl3.so",
    "libnspr4.so",
    "libnss3.so",
    "libnssckbi.so",
    "libnssdbm3.so",
    "libnssutil3.so",
    "libplc4.so",
    "libplds4.so",
    "libsmime3.so",
    "libsoftokn3.so",
    "libsqlite3.so",
    "libssl3.so",
)
ENGINE_LIBS = ("liblo-native-code.so", "libc++_shared.so") + NSS_LIBS

# Android platform libraries, taken verbatim from NEEDED of the verified stage.
SYSTEM_NEEDED = frozenset(
    {
        "libGLESv2.so",
        "libandroid.so",
        "libjnigraphics.so",
        "liblog.so",
        "libz.so",
        "libm.so",
        "libdl.so",
        "libc.so",
    }
)

# Asset files the engine needs before LibreOfficeKit can initialise. Extracted
# from tools/office-engine/20-stage.sh and office-engine-report.md section 8.
REQUIRED_ASSETS = (
    "program/fundamentalrc",
    "program/bootstraprc",
    "program/unorc",
    "program/versionrc",
    "program/services.rdb",
    "program/services/services.rdb",
    "unpack/program/offapi.rdb",
    "unpack/program/oovbaapi.rdb",
    "unpack/program/udkapi.rdb",
    "unpack/program/sofficerc",
    "unpack/etc/fonts/fonts.conf",
    "share/registry/main.xcd",
    "share/registry/res/registry_en-US.xcd",
    "office-engine/build-manifest.properties",
)
REQUIRED_NONEMPTY_DIRS = (
    "unpack/user/fonts",
    "share/filter",
    "share/config",
)
REQUIRED_LICENCES = ("LICENSE", "NOTICE")

# SHA-256 of the four MPL-2.0 Java glue files at the pinned commit, computed from
# android/Bootstrap/src/org/libreoffice/kit/ in the pinned checkout. These hashes
# are the binding to the source pin; the files are copied, never edited.
JAVA_GLUE_SHA256 = {
    "DirectBufferAllocator.java": "4472c23cc0e73c9406fc13625c7ae24296e6def46c7ff94560e9dd1efea3957c",
    "Document.java": "321461b4c99788895b7470e25c3fc950ee1b2c3f982c12d67096497b188b6d68",
    "LibreOfficeKit.java": "f487c7c16ac5d4313d6c38ba2328c3dade2bf504b376e6c0f1b13eaa12ec3f9d",
    "Office.java": "e2b8bf695095dcd172e7f2a5398574376f648899922a930d19d566d16f9c169a",
}
JAVA_GLUE_PACKAGE = "package org.libreoffice.kit;"
JAVA_GLUE_LICENSE_MARKERS = ("Mozilla Public", "MPL/2.0")

# Strings that must never be baked into a packaged asset. They reveal the local
# build machine or the upstream Gradle applicationId substitution.
LEAK_MARKERS = (b"/home/", b"C:\\Users", b"org.example.libreoffice", b"\\\\wsl", b"//wsl")

HASH_LINE = re.compile(r"^([0-9a-f]{64})  (\S.*)$")
PT_LOAD = 1
SHT_DYNAMIC = 6
DT_NULL = 0
DT_NEEDED = 1


class PackageError(Exception):
    """A validation failure. The message is the complete user-facing reason."""


def fail(message):
    raise PackageError(message)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def walk_files(root, label):
    """Map '<relative posix path>' -> Path for every file under root.

    Rejects symlinks and names that collide case-insensitively, because the APK is
    assembled on a case-insensitive filesystem where two such entries would silently
    overwrite each other.
    """
    root = Path(root)
    if not root.is_dir():
        fail("%s is not a directory: %s" % (label, root))
    files = {}
    seen = {}
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames.sort()
        for name in list(dirnames):
            entry = Path(dirpath) / name
            if entry.is_symlink():
                fail("%s contains a symlinked directory: %s" % (label, entry.relative_to(root).as_posix()))
        for name in sorted(filenames):
            entry = Path(dirpath) / name
            if entry.is_symlink():
                fail("%s contains a symlink: %s" % (label, entry.relative_to(root).as_posix()))
            if not entry.is_file():
                fail("%s contains a non-regular file: %s" % (label, entry.relative_to(root).as_posix()))
            rel = entry.relative_to(root).as_posix()
            folded = rel.casefold()
            if folded in seen:
                fail("%s has names differing only by case: %s and %s" % (label, seen[folded], rel))
            seen[folded] = rel
            files[rel] = entry
    return files


def read_text(path, label):
    try:
        return Path(path).read_text(encoding="utf-8")
    except UnicodeDecodeError:
        fail("%s is not UTF-8 text: %s" % (label, path))
    except OSError as error:
        fail("cannot read %s: %s" % (label, error))


def parse_declaration(path):
    """Strict key=value parser matching OfficeBuildManifest.parse's failure modes."""
    values = {}
    for number, line in enumerate(read_text(path, "build-manifest.properties").splitlines(), 1):
        trimmed = line.strip()
        if not trimmed or trimmed.startswith("#") or trimmed.startswith("!"):
            continue
        if "=" not in trimmed:
            fail("build-manifest.properties line %d is malformed" % number)
        key, value = trimmed.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key:
            fail("build-manifest.properties line %d has an empty key" % number)
        if key in values:
            fail("build-manifest.properties repeats key %r" % key)
        values[key] = value
    return values


def parse_stage_manifest(path):
    """Parse MANIFEST.txt: header key=values plus the '## sha256' listing."""
    header = {}
    hashes = {}
    section = "header"
    for number, line in enumerate(read_text(path, "MANIFEST.txt").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("## "):
            section = "sha256" if stripped.lower().startswith("## sha256") else "other"
            continue
        if section == "sha256":
            if not stripped:
                continue
            match = HASH_LINE.match(stripped)
            if not match:
                fail("MANIFEST.txt line %d is not a sha256 entry" % number)
            digest, name = match.group(1), match.group(2)
            if name.startswith("/") or ".." in name.split("/"):
                fail("MANIFEST.txt line %d has an unsafe path %r" % (number, name))
            if name in hashes:
                fail("MANIFEST.txt repeats hash entry %r" % name)
            hashes[name] = digest
        elif section == "header" and "=" in stripped and not stripped.startswith("#"):
            key, value = stripped.split("=", 1)
            header[key.strip()] = value.strip()
    return header, hashes


# ---------------------------------------------------------------------------
# ELF inspection (no external binutils: llvm-readelf is not on every host).
# ---------------------------------------------------------------------------
def read_at(handle, offset, size, label):
    handle.seek(offset)
    data = handle.read(size)
    if len(data) != size:
        fail("%s is truncated at offset %d" % (label, offset))
    return data


def parse_elf(path):
    label = Path(path).name
    size = os.path.getsize(path)
    with open(path, "rb") as handle:
        ident = read_at(handle, 0, 16, label)
        if ident[:4] != b"\x7fELF":
            fail("%s is not an ELF file" % label)
        elf_class, data_encoding = ident[4], ident[5]
        header = read_at(handle, 16, 48, label)
        e_type, e_machine = struct.unpack_from("<HH", header, 0)
        e_phoff = struct.unpack_from("<Q", header, 16)[0]
        e_shoff = struct.unpack_from("<Q", header, 24)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", header, 38)
        e_shentsize, e_shnum = struct.unpack_from("<HH", header, 42)
        if e_phentsize != 56 or e_shentsize not in (0, 64):
            fail("%s has unexpected ELF header sizes" % label)
        if e_phoff + e_phnum * e_phentsize > size:
            fail("%s program headers exceed the file" % label)
        loads = []
        for index in range(e_phnum):
            entry = read_at(handle, e_phoff + index * e_phentsize, 56, label)
            p_type = struct.unpack_from("<I", entry, 0)[0]
            if p_type == PT_LOAD:
                p_offset = struct.unpack_from("<Q", entry, 8)[0]
                p_vaddr = struct.unpack_from("<Q", entry, 16)[0]
                p_align = struct.unpack_from("<Q", entry, 48)[0]
                loads.append((p_offset, p_vaddr, p_align))
        needed = []
        if e_shoff and e_shnum:
            if e_shoff + e_shnum * e_shentsize > size:
                fail("%s section headers exceed the file" % label)
            sections = []
            for index in range(e_shnum):
                entry = read_at(handle, e_shoff + index * e_shentsize, 64, label)
                sections.append(
                    {
                        "type": struct.unpack_from("<I", entry, 4)[0],
                        "offset": struct.unpack_from("<Q", entry, 24)[0],
                        "size": struct.unpack_from("<Q", entry, 32)[0],
                        "link": struct.unpack_from("<I", entry, 40)[0],
                        "entsize": struct.unpack_from("<Q", entry, 56)[0],
                    }
                )
            for section in sections:
                if section["type"] != SHT_DYNAMIC:
                    continue
                if section["link"] >= len(sections):
                    fail("%s dynamic section has an invalid string table link" % label)
                strings = sections[section["link"]]
                if strings["type"] != 3:
                    fail("%s dynamic section does not link a string table" % label)
                if section["entsize"] not in (0, 16):
                    fail("%s dynamic section has an unexpected entry size" % label)
                raw = read_at(handle, section["offset"], section["size"], label)
                strtab = read_at(handle, strings["offset"], strings["size"], label)
                for offset in range(0, len(raw) - 15, 16):
                    tag, value = struct.unpack_from("<QQ", raw, offset)
                    if tag == DT_NULL:
                        break
                    if tag != DT_NEEDED:
                        continue
                    end = strtab.find(b"\x00", value)
                    if end < 0:
                        fail("%s has an unterminated NEEDED string" % label)
                    needed.append(strtab[value:end].decode("ascii", "replace"))
    return {
        "elfClass": elf_class,
        "dataEncoding": data_encoding,
        "type": e_type,
        "machine": e_machine,
        "loads": loads,
        "needed": needed,
    }


def validate_elf(path, expected_needed, abi):
    info = parse_elf(path)
    label = Path(path).name
    if info["elfClass"] != 2:
        fail("%s is not ELF64" % label)
    if info["dataEncoding"] != 1:
        fail("%s is not little-endian" % label)
    if info["type"] != 3:
        fail("%s is not a shared object (ET_DYN)" % label)
    machine = ELF_MACHINE_BY_ABI[abi]
    if info["machine"] != machine:
        fail("%s is built for ELF machine %d, expected %d (%s)" % (label, info["machine"], machine, abi))
    if not info["loads"]:
        fail("%s has no PT_LOAD segment" % label)
    alignments = set()
    for offset, vaddr, align in info["loads"]:
        if align < 0x4000 or align & (align - 1):
            fail("%s has PT_LOAD alignment 0x%x below the 16 KiB page requirement" % (label, align))
        if (vaddr - offset) % align:
            fail("%s has a PT_LOAD whose vaddr/offset are not congruent modulo its alignment" % label)
        alignments.add(align)
    for name in info["needed"]:
        if name not in expected_needed:
            fail("%s depends on %s, which is neither staged for %s nor an Android system library" % (label, name, abi))
    info["alignments"] = alignments
    return info


# ---------------------------------------------------------------------------
# prepare
# ---------------------------------------------------------------------------
def prepare(stage_dir, java_glue_dir, out_dir, app_id, min_sdk):
    stage = Path(stage_dir)
    glue = Path(java_glue_dir)
    out = Path(out_dir)

    if app_id != APP_ID:
        fail("applicationId %r does not match the engine's substituted applicationId %r" % (app_id, APP_ID))
    if min_sdk < MIN_SDK_APP:
        fail("app minSdk %d is below the engine's minimum %d" % (min_sdk, MIN_SDK_APP))
    if not stage.is_dir():
        fail("stage directory does not exist: %s" % stage)

    header, manifest_hashes = parse_stage_manifest(stage / "MANIFEST.txt")
    abi = header.get("abi")
    if abi not in ELF_MACHINE_BY_ABI:
        fail("MANIFEST.txt has an unsupported or missing abi: %r" % abi)
    expected_header = {
        "source_tag": PINNED_TAG,
        "source_commit": PINNED_COMMIT,
        "android_api_level": str(ANDROID_API_LEVEL),
        "min_sdk_app": str(MIN_SDK_APP),
        "have_feature_android_lok": "0",
        "enable_cairo_rgba": "TRUE",
        "tile_mode": TILE_MODE,
        "application_id": APP_ID,
    }
    for key, expected in expected_header.items():
        if key not in header:
            fail("MANIFEST.txt is missing %s" % key)
        if header[key] != expected:
            fail("MANIFEST.txt %s=%r, expected %r" % (key, header[key], expected))
    if "elf_load_align" not in header:
        fail("MANIFEST.txt is missing elf_load_align")

    declaration_path = stage / "assets" / "office-engine" / "build-manifest.properties"
    declaration = parse_declaration(declaration_path)
    for key, expected in {
        "commit": PINNED_COMMIT,
        "tag": PINNED_TAG,
        "have_feature_android_lok": MACRO_FLAG,
        "tile_mode": TILE_MODE,
        "abi": abi,
        "application_id": APP_ID,
    }.items():
        if key not in declaration:
            fail("build-manifest.properties is missing %s" % key)
        if declaration[key] != expected:
            fail("build-manifest.properties %s=%r, expected %r" % (key, declaration[key], expected))

    lib_dir = stage / "lib"
    if not lib_dir.is_dir():
        fail("stage is missing lib/")
    lib_names = set()
    for entry in sorted(lib_dir.iterdir()):
        if entry.is_dir():
            fail("stage lib/ contains a directory: %s" % entry.name)
        if entry.suffix != ".so":
            fail("stage lib/ contains a non-library file: %s" % entry.name)
        lib_names.add(entry.name)
    missing_libs = sorted(set(ENGINE_LIBS) - lib_names)
    extra_libs = sorted(lib_names - set(ENGINE_LIBS))
    if missing_libs:
        fail("stage lib/ is missing: %s" % ", ".join(missing_libs))
    if extra_libs:
        fail("stage lib/ contains unverified libraries: %s" % ", ".join(extra_libs))

    allowed_needed = set(ENGINE_LIBS) | set(SYSTEM_NEEDED)
    elf_info = {}
    all_alignments = set()
    for name in ENGINE_LIBS:
        info = validate_elf(lib_dir / name, allowed_needed, abi)
        elf_info[name] = info
        all_alignments |= info["alignments"]
    declared_alignments = set(header["elf_load_align"].split())
    actual_alignments = {"0x%x" % value for value in all_alignments}
    if declared_alignments != actual_alignments:
        fail(
            "MANIFEST.txt elf_load_align=%r does not match the staged ELF segments %s"
            % (header["elf_load_align"], sorted(actual_alignments))
        )

    asset_names = set()
    if not (stage / "assets").is_dir():
        fail("stage is missing assets/")
    for rel, path in walk_files(stage / "assets", "stage assets").items():
        asset_names.add(rel)
        size = path.stat().st_size
        if size <= 4 * 1024 * 1024:
            data = path.read_bytes()
            for marker in LEAK_MARKERS:
                if marker in data:
                    fail("staged asset assets/%s contains a local-path marker %r" % (rel, marker))
    missing_assets = [name for name in REQUIRED_ASSETS if name not in asset_names]
    if missing_assets:
        fail("stage assets are missing: %s" % ", ".join(missing_assets))
    empty_assets = [name for name in REQUIRED_ASSETS if (stage / "assets" / name).stat().st_size == 0]
    if empty_assets:
        fail("required assets are empty: %s" % ", ".join(empty_assets))
    for name in REQUIRED_NONEMPTY_DIRS:
        prefix = name + "/"
        if not any(rel.startswith(prefix) for rel in asset_names):
            fail("stage assets contain no files under %s/" % name)
    if not any(rel.startswith("unpack/user/fonts/") and rel.endswith(".ttf") for rel in asset_names):
        fail("stage assets contain no unpacked .ttf fonts")
    fonts_conf = (stage / "assets" / "unpack" / "etc" / "fonts" / "fonts.conf").read_text(
        encoding="utf-8", errors="replace"
    )
    if "/data/data/%s/fontconfig" % APP_ID not in fonts_conf:
        fail("fonts.conf does not point its fontconfig cache at /data/data/%s/fontconfig" % APP_ID)

    licence_dir = stage / "licences"
    for name in REQUIRED_LICENCES:
        path = licence_dir / name
        if not path.is_file() or path.stat().st_size == 0:
            fail("stage licences/ is missing a non-empty %s" % name)
    license_text = (licence_dir / "LICENSE").read_text(encoding="utf-8", errors="replace")
    if "Mozilla Public License" not in license_text:
        fail("stage licences/LICENSE does not contain the MPL text")

    glue_files = walk_files(glue, "Java glue")
    if set(glue_files) != set(JAVA_GLUE_SHA256):
        fail(
            "Java glue directory must contain exactly the pinned four files; found %s"
            % ", ".join(sorted(glue_files))
        )
    glue_hashes = {}
    for name, expected in JAVA_GLUE_SHA256.items():
        path = glue_files[name]
        actual = sha256_file(path)
        if actual != expected:
            fail("Java glue %s has sha256 %s, expected %s at the pinned commit" % (name, actual, expected))
        text = path.read_text(encoding="utf-8", errors="replace")
        if JAVA_GLUE_PACKAGE not in text:
            fail("Java glue %s is not in package org.libreoffice.kit" % name)
        for marker in JAVA_GLUE_LICENSE_MARKERS:
            if marker not in text:
                fail("Java glue %s carries no MPL-2.0 header (missing %r)" % (name, marker))
        glue_hashes[name] = actual

    # Byte-for-byte integrity of every file the stage manifest claims.
    stage_files = walk_files(stage, "stage")
    allowed_roots = {"lib", "assets", "licences", "MANIFEST.txt"}
    unexpected_roots = sorted({rel.split("/", 1)[0] for rel in stage_files} - allowed_roots)
    if unexpected_roots:
        fail("stage root contains unverified entries: %s" % ", ".join(unexpected_roots))
    covered = {}
    for rel, path in stage_files.items():
        if rel.startswith(("lib/", "assets/", "licences/")):
            covered[rel] = path
    declaration_rel = "assets/office-engine/build-manifest.properties"
    if declaration_rel not in covered:
        fail("stage is missing %s" % declaration_rel)
    # 20-stage.sh writes MANIFEST.txt before the app-facing declaration, so the
    # declaration may be absent from the hash listing; every other file must be listed.
    listed = set(manifest_hashes)
    missing_from_manifest = sorted(set(covered) - listed - {declaration_rel})
    if missing_from_manifest:
        fail("MANIFEST.txt has no sha256 for: %s" % ", ".join(missing_from_manifest))
    unlisted = sorted(listed - set(covered))
    if unlisted:
        fail("MANIFEST.txt lists files that are not part of the stage: %s" % ", ".join(unlisted))
    hashes = {}
    for rel in sorted(covered):
        actual = sha256_file(covered[rel])
        hashes[rel] = actual
        if rel in manifest_hashes and actual != manifest_hashes[rel]:
            fail("stage file %s does not match MANIFEST.txt (sha256 %s != %s)" % (rel, actual, manifest_hashes[rel]))
    if declaration_rel not in manifest_hashes:
        hashes[declaration_rel] = sha256_file(stage / declaration_rel)

    # ---- assemble in a sibling temporary directory, publish atomically ----
    out = Path(out)
    parent = out.parent
    parent.mkdir(parents=True, exist_ok=True)
    staging = parent / (out.name + ".tmp-%d" % os.getpid())
    if staging.exists():
        shutil.rmtree(staging)

    try:
        assets_out = staging / "assets"
        jni_out = staging / "jniLibs" / abi
        java_out = staging / "java" / "org" / "libreoffice" / "kit"
        licences_out = staging / "licences"
        for directory in (assets_out, jni_out, java_out, licences_out):
            directory.mkdir(parents=True, exist_ok=True)

        asset_bytes = 0
        for rel in sorted(asset_names):
            source = stage / "assets" / rel
            destination = assets_out / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            asset_bytes += source.stat().st_size
        for name in ENGINE_LIBS:
            shutil.copyfile(lib_dir / name, jni_out / name)
        for name in JAVA_GLUE_SHA256:
            shutil.copyfile(glue_files[name], java_out / name)
        for name in REQUIRED_LICENCES:
            shutil.copyfile(licence_dir / name, licences_out / name)

        copied = {}
        tmp_assets = walk_files(assets_out, "prepared assets")
        for rel, path in tmp_assets.items():
            copied["assets/" + rel] = sha256_file(path)
        for name in ENGINE_LIBS:
            copied["jniLibs/%s/%s" % (abi, name)] = sha256_file(jni_out / name)
        for name in JAVA_GLUE_SHA256:
            copied["java/org/libreoffice/kit/%s" % name] = sha256_file(java_out / name)
        for name in REQUIRED_LICENCES:
            copied["licences/" + name] = sha256_file(licences_out / name)

        for rel, digest in copied.items():
            if rel.startswith("assets/"):
                expected = hashes[rel]
            elif rel.startswith("jniLibs/"):
                expected = hashes["lib/" + rel.rsplit("/", 1)[-1]]
            elif rel.startswith("java/"):
                expected = glue_hashes[rel.rsplit("/", 1)[-1]]
            else:
                expected = hashes[rel]
            if digest != expected:
                fail("copied file %s does not match its source hash" % rel)
        if set(tmp_assets) != asset_names:
            fail("prepared assets do not contain exactly the verified asset set")

        provenance = {
            "schema": 1,
            "generatedBy": "tools/office-packaging/office_engine_package.py",
            "applicationId": APP_ID,
            "abi": abi,
            "minSdk": min_sdk,
            "engine": {
                "tag": PINNED_TAG,
                "commit": PINNED_COMMIT,
                "tileMode": TILE_MODE,
                "haveFeatureAndroidLok": False,
                "androidApiLevel": ANDROID_API_LEVEL,
                "stageManifestSha256": sha256_file(stage / "MANIFEST.txt"),
                "buildDeclarationSha256": hashes[declaration_rel],
                "elfLoadAlign": sorted(actual_alignments),
            },
            "nativeLibraries": [
                {
                    "path": "jniLibs/%s/%s" % (abi, name),
                    "sha256": hashes["lib/" + name],
                    "elfMachine": elf_info[name]["machine"],
                    "loadAlign": sorted("0x%x" % value for value in elf_info[name]["alignments"]),
                    "needed": elf_info[name]["needed"],
                }
                for name in ENGINE_LIBS
            ],
            "javaGlue": [
                {
                    "path": "org/libreoffice/kit/" + name,
                    "sha256": glue_hashes[name],
                    "license": "MPL-2.0",
                }
                for name in sorted(JAVA_GLUE_SHA256)
            ],
            "assets": {"count": len(asset_names), "bytes": asset_bytes},
            "licences": {name: hashes["licences/" + name] for name in REQUIRED_LICENCES},
            "notes": [
                "build-manifest.properties is a build-time declaration; it is not a "
                "cryptographic attestation of the packaged .so",
                "only the %s engine is packaged; other ABIs are unchanged" % abi,
            ],
        }
        (staging / "provenance.json").write_text(
            json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

        if out.exists():
            previous = parent / (out.name + ".prev-%d" % os.getpid())
            if previous.exists():
                shutil.rmtree(previous, ignore_errors=True)
            os.rename(out, previous)
            try:
                os.rename(staging, out)
            except OSError:
                os.rename(previous, out)
                raise
            shutil.rmtree(previous, ignore_errors=True)
        else:
            os.rename(staging, out)
    except OSError as error:
        if staging.exists():
            shutil.rmtree(staging, ignore_errors=True)
        fail("cannot prepare %s: %s" % (out, error))
    except Exception:
        if staging.exists():
            shutil.rmtree(staging, ignore_errors=True)
        raise

    print("prepared %s: %d assets (%.1f MiB), %d %s libraries, 4 Java glue files"
          % (out, len(asset_names), asset_bytes / (1024.0 * 1024.0), len(ENGINE_LIBS), abi))
    print("engine %s @ %s, tile_mode=%s, have_feature_android_lok=%s"
          % (PINNED_TAG, PINNED_COMMIT[:12], TILE_MODE, MACRO_FLAG))


# ---------------------------------------------------------------------------
# verify-merged
# ---------------------------------------------------------------------------
def verify_merged(prepared_jni_dir, merged_dir):
    prepared_root = Path(prepared_jni_dir)
    if not prepared_root.is_dir():
        fail("prepared jniLibs directory does not exist: %s" % prepared_root)
    entries = list(prepared_root.iterdir())
    if len(entries) != 1 or not entries[0].is_dir() or entries[0].name not in ELF_MACHINE_BY_ABI:
        fail("prepared jniLibs must contain exactly one supported ABI directory")
    prepared = entries[0]
    engine_abi = prepared.name

    expected = {}
    for entry in sorted(prepared.iterdir()):
        if entry.is_dir() or entry.suffix != ".so":
            fail("prepared jniLibs/%s contains an unexpected entry: %s" % (engine_abi, entry.name))
        expected[entry.name] = sha256_file(entry)
    if set(expected) != set(ENGINE_LIBS):
        fail("prepared jniLibs/%s does not contain exactly the verified engine libraries" % engine_abi)

    root = Path(merged_dir)
    if not root.is_dir():
        fail("merged native libraries directory does not exist: %s" % root)
    if (root / "lib").is_dir() and not (root / engine_abi).is_dir():
        root = root / "lib"
    abi_dirs = {entry.name for entry in root.iterdir() if entry.is_dir()}
    if engine_abi not in abi_dirs:
        fail("merged native libraries do not contain ABI %s" % engine_abi)

    for name, digest in expected.items():
        path = root / engine_abi / name
        if not path.is_file():
            fail("merged native libraries lack %s/%s; the engine source directory was not packaged" % (engine_abi, name))
        actual = sha256_file(path)
        if actual != digest:
            fail("merged %s/%s differs from the verified stage (sha256 %s != %s)" % (engine_abi, name, actual, digest))

    for abi in sorted(abi_dirs - {engine_abi}):
        for name in ENGINE_LIBS:
            if (root / abi / name).is_file():
                fail(
                    "engine library %s also appears in %s/; only verified %s may be packaged "
                    "and a competing producer must be resolved explicitly, not with pickFirst" % (name, abi, engine_abi)
                )

    print("merged native libraries verified for %s; no engine library leaked into another ABI" % engine_abi)
    for abi in sorted(abi_dirs):
        names = sorted(entry.name for entry in (root / abi).iterdir() if entry.is_file())
        print("  %s: %s" % (abi, ", ".join(names) if names else "(none)"))


# ---------------------------------------------------------------------------
def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    prepare_parser = commands.add_parser("prepare", help="validate the stage and copy it into a build directory")
    prepare_parser.add_argument("--stage", required=True, help="per-ABI stage directory (read-only)")
    prepare_parser.add_argument("--java-glue", required=True, help="directory with the four pinned org/libreoffice/kit/*.java")
    prepare_parser.add_argument("--out", required=True, help="preparation root under app/build; assets/, jniLibs/ and java/ are published inside")
    prepare_parser.add_argument("--app-id", required=True, help="applicationId of the packaged app")
    prepare_parser.add_argument("--min-sdk", required=True, type=int, help="app minSdk")

    verify_parser = commands.add_parser("verify-merged", help="verify AGP's merged native libraries")
    verify_parser.add_argument("--prepared-jni", required=True, help="prepared jniLibs directory")
    verify_parser.add_argument("--merged-native-libs", required=True, help="AGP MERGED_NATIVE_LIBS artifact directory")

    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "prepare":
            prepare(arguments.stage, arguments.java_glue, arguments.out, arguments.app_id, arguments.min_sdk)
        else:
            verify_merged(arguments.prepared_jni, arguments.merged_native_libs)
    except PackageError as error:
        print("office-engine packaging refused: %s" % error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
