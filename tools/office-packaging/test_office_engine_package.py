#!/usr/bin/env python3
"""Isolated unit tests for office_engine_package.py.

Synthetic fixtures only: tiny ELF files, a fake per-ABI stage and a fake Java glue
directory. Nothing here reads the real stage or runs Gradle.

Run: python -m unittest discover -s tools/office-packaging -p "test_*.py"
"""

import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import office_engine_package as pkg  # noqa: E402

MPL_NOTE = b"/* MPL header: Mozilla Public License, v. 2.0, MPL/2.0 */\n"
GLUE_BODY = b"package org.libreoffice.kit;\n\nfinal class Fixture {}\n"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def align8(value):
    return (value + 7) & ~7


def make_elf(machine=62, load_align=0x4000, needed=(), etype=3, elf_class=2, data_encoding=1):
    """Minimal but structurally valid ELF64 shared object with a dynamic section."""
    phnum = 2
    phoff = 64
    dynstr = b"\x00"
    offsets = []
    for name in needed:
        offsets.append(len(dynstr))
        dynstr += name.encode("ascii") + b"\x00"
    entries = [(1, offset) for offset in offsets] + [(0, 0)]
    dynamic = b"".join(struct.pack("<QQ", tag, value) for tag, value in entries)
    dynstr_off = align8(phoff + 56 * phnum)
    dynamic_off = align8(dynstr_off + len(dynstr))
    shoff = align8(dynamic_off + len(dynamic))
    shnum = 3
    total = shoff + 64 * shnum

    ident = b"\x7fELF" + bytes([elf_class, data_encoding, 1, 0]) + b"\x00" * 8
    header = struct.pack(
        "<HHIQQQIHHHHHH",
        etype,
        machine,
        1,
        0,
        phoff,
        shoff,
        0,
        64,
        56,
        phnum,
        64,
        shnum,
        0,
    )
    buffer = bytearray(total)
    buffer[0:16] = ident
    buffer[16:64] = header
    struct.pack_into("<IIQQQQQQ", buffer, phoff, 1, 5, 0, 0, 0, total, total, load_align)
    struct.pack_into(
        "<IIQQQQQQ", buffer, phoff + 56, 2, 6, dynamic_off, dynamic_off, dynamic_off, len(dynamic), len(dynamic), 8
    )
    buffer[dynstr_off : dynstr_off + len(dynstr)] = dynstr
    buffer[dynamic_off : dynamic_off + len(dynamic)] = dynamic
    struct.pack_into("<IIQQQQIIQQ", buffer, shoff + 64, 0, 3, 0, 0, dynstr_off, len(dynstr), 0, 0, 1, 0)
    struct.pack_into("<IIQQQQIIQQ", buffer, shoff + 128, 0, 6, 0, 0, dynamic_off, len(dynamic), 1, 0, 16, 0)
    return bytes(buffer)


BASE_ASSETS = {
    "program/fundamentalrc": b"[Bootstrap]\nBRAND_BASE_DIR=file:///assets\n",
    "program/bootstraprc": b"[Bootstrap]\nProductKey=LibreOffice 26.8\n",
    "program/unorc": b"[Bootstrap]\nUNO_TYPES=file:///assets/program/udkapi.rdb\n",
    "program/versionrc": b"[Version]\nAllLanguages=en-US\n",
    "program/services.rdb": b"fixture-services-rdb",
    "program/services/services.rdb": b"fixture-inner-services-rdb",
    "unpack/program/offapi.rdb": b"fixture-offapi",
    "unpack/program/oovbaapi.rdb": b"fixture-oovbaapi",
    "unpack/program/udkapi.rdb": b"fixture-udkapi",
    "unpack/program/sofficerc": b"[Bootstrap]\nLogo=1\n",
    "unpack/etc/fonts/fonts.conf": b"<fontconfig><cachedir>/data/data/ru.spgsroot.peachy/fontconfig</cachedir></fontconfig>\n",
    "unpack/user/fonts/Fixture-Regular.ttf": b"\x00\x01\x00\x00fixture-ttf",
    "share/registry/main.xcd": b"fixture-main-xcd",
    "share/registry/res/registry_en-US.xcd": b"fixture-registry-xcd",
    "share/filter/fixture.xcu": b"fixture-filter",
    "share/config/images_colibre.zip": b"PK\x03\x04fixture-not-a-real-zip",
    # The real stage ships an empty placeholder; an empty non-required file is fine.
    "share/config/soffice.cfg/empty": b"",
}

DECLARATION = {
    "commit": pkg.PINNED_COMMIT,
    "tag": pkg.PINNED_TAG,
    "have_feature_android_lok": "false",
    "tile_mode": "rgba",
    "abi": "x86_64",
    "application_id": pkg.APP_ID,
}


def lo_needed():
    return ("libnspr4.so", "libc++_shared.so", "liblog.so", "libc.so")


class Fixture:
    """Builds a synthetic stage and Java glue directory in a temporary tree."""

    def __init__(self, root):
        self.root = Path(root)
        self.stage = self.root / "stage"
        self.glue = self.root / "glue"
        self.out = self.root / "out"

    def build_glue(self):
        hashes = {}
        for index, name in enumerate(sorted(pkg.JAVA_GLUE_SHA256)):
            body = MPL_NOTE + GLUE_BODY + ("// %d\n" % index).encode("ascii")
            path = self.glue / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
            hashes[name] = sha256(body)
        return hashes

    def build_stage(self, header_overrides=None, declaration_overrides=None, lib_overrides=None, extra_lib=None, extra_root=None):
        assets = dict(BASE_ASSETS)
        declaration = dict(DECLARATION)
        declaration.update(declaration_overrides or {})
        for rel, data in assets.items():
            path = self.stage / "assets" / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        declaration_path = self.stage / "assets" / "office-engine" / "build-manifest.properties"
        declaration_path.parent.mkdir(parents=True, exist_ok=True)

        libs = {}
        for name in pkg.ENGINE_LIBS:
            overrides = (lib_overrides or {}).get(name)
            if overrides is not None:
                libs[name] = overrides
            elif name == "liblo-native-code.so":
                libs[name] = make_elf(needed=lo_needed())
            else:
                libs[name] = make_elf(needed=("libc.so", "libdl.so"))
        if extra_lib:
            libs.update(extra_lib)
        for name, data in libs.items():
            path = self.stage / "lib" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        if extra_root:
            for rel, data in extra_root.items():
                path = self.stage / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)

        (self.stage / "licences").mkdir(parents=True, exist_ok=True)
        (self.stage / "licences" / "LICENSE").write_text(
            "# Licensing\n\nMozilla Public License, v. 2.0\n", encoding="utf-8"
        )
        (self.stage / "licences" / "NOTICE").write_text("Portions under the Apache License v2.0\n", encoding="utf-8")
        declaration_path.write_text(
            "".join("%s=%s\n" % item for item in declaration.items()), encoding="utf-8"
        )

        header = {
            "source_tag": pkg.PINNED_TAG,
            "source_commit": pkg.PINNED_COMMIT,
            "abi": "x86_64",
            "android_api_level": "26",
            "min_sdk_app": "26",
            "have_feature_android_lok": "0",
            "enable_cairo_rgba": "TRUE",
            "tile_mode": "rgba",
            "application_id": pkg.APP_ID,
            "elf_load_align": "0x4000",
        }
        header.update(header_overrides or {})
        lines = ["# fixture MANIFEST", ""]
        lines.extend("%s=%s" % (key, value) for key, value in header.items())
        lines.extend(["", "## sizes", "1M\t/tmp/not-a-real-path/lib", "", "total 1", "", "## sha256"])
        for rel in sorted(self._hashable_files()):
            lines.append("%s  %s" % (sha256((self.stage / rel).read_bytes()), rel))
        (self.stage / "MANIFEST.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def _hashable_files(self):
        files = []
        for base in ("lib", "assets", "licences"):
            for dirpath, _, filenames in os.walk(self.stage / base):
                for name in filenames:
                    rel = (Path(dirpath) / name).relative_to(self.stage).as_posix()
                    if rel == "assets/office-engine/build-manifest.properties":
                        continue
                    files.append(rel)
        return files


class PackageTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.mkdtemp(prefix="office-pkg-test-")
        self.fixture = Fixture(self.temp)
        self.glue_hashes = self.fixture.build_glue()
        self.saved_glue = dict(pkg.JAVA_GLUE_SHA256)
        pkg.JAVA_GLUE_SHA256 = dict(self.glue_hashes)

    def tearDown(self):
        pkg.JAVA_GLUE_SHA256 = self.saved_glue
        shutil.rmtree(self.temp, ignore_errors=True)

    def prepare(self, **kwargs):
        arguments = dict(
            stage_dir=self.fixture.stage,
            java_glue_dir=self.fixture.glue,
            out_dir=self.fixture.out,
            app_id=pkg.APP_ID,
            min_sdk=26,
        )
        arguments.update(kwargs)
        pkg.prepare(**arguments)

    def assert_refused(self, fragment, **kwargs):
        with self.assertRaises(pkg.PackageError) as caught:
            self.prepare(**kwargs)
        self.assertIn(fragment, str(caught.exception))
        self.assertFalse(self.fixture.out.exists(), "a refused run must not publish output")


class PrepareSuccessTest(PackageTestCase):
    def test_prepares_verified_tree(self):
        self.fixture.build_stage()
        self.prepare()

        self.assertEqual(
            sorted(path.name for path in (self.fixture.out / "jniLibs" / "x86_64").iterdir()),
            sorted(pkg.ENGINE_LIBS),
        )
        self.assertEqual(
            sorted(path.name for path in (self.fixture.out / "java" / "org" / "libreoffice" / "kit").iterdir()),
            sorted(pkg.JAVA_GLUE_SHA256),
        )
        for rel, data in BASE_ASSETS.items():
            self.assertEqual((self.fixture.out / "assets" / rel).read_bytes(), data)
        self.assertEqual(
            (self.fixture.out / "assets" / "program" / "fundamentalrc").read_bytes(),
            BASE_ASSETS["program/fundamentalrc"],
        )
        self.assertTrue((self.fixture.out / "licences" / "LICENSE").is_file())

        provenance = json.loads((self.fixture.out / "provenance.json").read_text(encoding="utf-8"))
        self.assertEqual(provenance["applicationId"], pkg.APP_ID)
        self.assertEqual(provenance["abi"], "x86_64")
        self.assertEqual(provenance["engine"]["commit"], pkg.PINNED_COMMIT)
        self.assertEqual(provenance["engine"]["tileMode"], "rgba")
        self.assertFalse(provenance["engine"]["haveFeatureAndroidLok"])
        self.assertEqual(len(provenance["nativeLibraries"]), len(pkg.ENGINE_LIBS))
        self.assertEqual(len(provenance["javaGlue"]), 4)
        self.assertEqual(
            sorted(item["loadAlign"][0] for item in provenance["nativeLibraries"]),
            ["0x4000"] * len(pkg.ENGINE_LIBS),
        )
        serialized = json.dumps(provenance)
        self.assertNotIn(str(Path(self.temp)), serialized)
        self.assertNotIn("wsl", serialized.lower())

    def test_second_run_replaces_previous_output(self):
        self.fixture.build_stage()
        self.prepare()
        marker = self.fixture.out / "assets" / "stale.txt"
        marker.write_text("stale", encoding="utf-8")
        self.prepare()
        self.assertFalse(marker.exists())

    def test_stage_is_not_modified(self):
        self.fixture.build_stage()
        before = {
            rel: sha256(path.read_bytes())
            for rel in self.fixture._hashable_files() + ["MANIFEST.txt"]
            for path in [self.fixture.stage / rel]
        }
        self.prepare()
        after = {
            rel: sha256(path.read_bytes())
            for rel in self.fixture._hashable_files() + ["MANIFEST.txt"]
            for path in [self.fixture.stage / rel]
        }
        self.assertEqual(before, after)


class PrepareRefusalTest(PackageTestCase):
    def test_wrong_application_id(self):
        self.fixture.build_stage()
        self.assert_refused("applicationId", app_id="org.example.libreoffice")

    def test_min_sdk_below_engine_minimum(self):
        self.fixture.build_stage()
        self.assert_refused("minSdk", min_sdk=24)

    def test_arm64_library_in_x86_64_stage_is_rejected(self):
        self.fixture.build_stage(
            lib_overrides={name: make_elf(machine=183, needed=()) for name in pkg.ENGINE_LIBS}
        )
        self.assert_refused("ELF machine")

    def test_x86_64_library_in_arm64_stage_is_rejected(self):
        self.fixture.build_stage(
            header_overrides={"abi": "arm64-v8a"},
            declaration_overrides={"abi": "arm64-v8a"},
        )
        self.assert_refused("ELF machine 62, expected 183")

    def test_low_load_alignment(self):
        self.fixture.build_stage(
            lib_overrides={name: make_elf(load_align=0x1000, needed=()) for name in pkg.ENGINE_LIBS}
        )
        self.assert_refused("16 KiB")

    def test_host_dependency_in_needed(self):
        self.fixture.build_stage(
            lib_overrides={
                "liblo-native-code.so": make_elf(needed=lo_needed() + ("libicuuc.so",)),
            }
        )
        self.assert_refused("libicuuc.so")

    def test_wrong_source_commit(self):
        self.fixture.build_stage(header_overrides={"source_commit": "0" * 40})
        self.assert_refused("source_commit")

    def test_wrong_source_tag(self):
        self.fixture.build_stage(header_overrides={"source_tag": "libreoffice-26.2.7.3"})
        self.assert_refused("source_tag")

    def test_wrong_abi(self):
        self.fixture.build_stage(header_overrides={"abi": "armeabi-v7a"})
        self.assert_refused("abi")

    def test_manifest_and_asset_disagree_on_abi(self):
        self.fixture.build_stage(header_overrides={"abi": "arm64-v8a"})
        self.assert_refused("build-manifest.properties abi")

    def test_macro_flag_enabled(self):
        self.fixture.build_stage(declaration_overrides={"have_feature_android_lok": "true"})
        self.assert_refused("have_feature_android_lok")

    def test_wrong_tile_mode(self):
        self.fixture.build_stage(declaration_overrides={"tile_mode": "bgra"})
        self.assert_refused("tile_mode")

    def test_declaration_repeats_key(self):
        self.fixture.build_stage()
        declaration = self.fixture.stage / "assets" / "office-engine" / "build-manifest.properties"
        declaration.write_text(declaration.read_text(encoding="utf-8") + "tile_mode=rgba\n", encoding="utf-8")
        self.assert_refused("repeats key")

    def test_missing_required_asset(self):
        self.fixture.build_stage()
        (self.fixture.stage / "assets" / "share" / "filter" / "fixture.xcu").unlink()
        self.assert_refused("share/filter")

    def test_fonts_conf_for_wrong_app(self):
        self.fixture.build_stage()
        (self.fixture.stage / "assets" / "unpack" / "etc" / "fonts" / "fonts.conf").write_text(
            "<cachedir>/data/data/com.other.app/fontconfig</cachedir>\n", encoding="utf-8"
        )
        self.assert_refused("fontconfig")

    def test_asset_leaks_a_local_path(self):
        self.fixture.build_stage()
        (self.fixture.stage / "assets" / "program" / "fundamentalrc").write_text(
            "LO_LIB_DIR=file:///home/somebody/office-engine/out/x86_64/lib/\n", encoding="utf-8"
        )
        self.assert_refused("local-path marker")

    def test_unlisted_asset(self):
        self.fixture.build_stage()
        (self.fixture.stage / "assets" / "program" / "extrarc").write_text("x\n", encoding="utf-8")
        self.assert_refused("no sha256")

    def test_tampered_library(self):
        self.fixture.build_stage()
        path = self.fixture.stage / "lib" / "liblo-native-code.so"
        path.write_bytes(path.read_bytes() + b"tamper")
        self.assert_refused("does not match MANIFEST.txt")

    def test_extra_library(self):
        self.fixture.build_stage(extra_lib={"libunverified.so": b"\x7fELFnot-really"})
        self.assert_refused("unverified libraries")

    def test_unverified_stage_root_entry(self):
        self.fixture.build_stage(extra_root={"logs/build.log": b"log"})
        self.assert_refused("unverified entries")

    def test_java_glue_hash_mismatch(self):
        self.fixture.build_stage()
        pkg.JAVA_GLUE_SHA256 = dict(self.glue_hashes)
        pkg.JAVA_GLUE_SHA256["Document.java"] = "0" * 64
        self.assert_refused("expected")

    def test_java_glue_extra_file(self):
        self.fixture.build_stage()
        (self.fixture.glue / "Extra.java").write_text("package org.libreoffice.kit;\n", encoding="utf-8")
        self.assert_refused("pinned four files")

    def test_java_glue_missing_file(self):
        self.fixture.build_stage()
        (self.fixture.glue / "Office.java").unlink()
        self.assert_refused("pinned four files")


class VerifyMergedTest(PackageTestCase):
    def merged(self, engine_bits=None, other_abi_engine_lib=None, wrapper=True):
        merged_root = Path(self.temp) / ("merged-%d" % len(list(Path(self.temp).glob("merged-*"))))
        target = merged_root / "lib" if wrapper else merged_root
        (target / "x86_64").mkdir(parents=True)
        (target / "arm64-v8a").mkdir(parents=True)
        (target / "x86_64" / "libmupdf_java.so").write_bytes(b"unrelated")
        (target / "arm64-v8a" / "libmupdf_java.so").write_bytes(b"unrelated-arm")
        for name in pkg.ENGINE_LIBS:
            data = (engine_bits or {}).get(name, (self.fixture.out / "jniLibs" / "x86_64" / name).read_bytes())
            (target / "x86_64" / name).write_bytes(data)
        if other_abi_engine_lib:
            name = other_abi_engine_lib
            (target / "arm64-v8a" / name).write_bytes(b"leak")
        return merged_root

    def test_merged_tree_is_accepted(self):
        self.fixture.build_stage()
        self.prepare()
        pkg.verify_merged(self.fixture.out / "jniLibs", self.merged())
        pkg.verify_merged(self.fixture.out / "jniLibs", self.merged(wrapper=False))

    def test_arm64_stage_is_merged_only_under_arm64(self):
        abi = "arm64-v8a"
        self.fixture.build_stage(
            header_overrides={"abi": abi},
            declaration_overrides={"abi": abi},
            lib_overrides={name: make_elf(machine=183, needed=lo_needed()) for name in pkg.ENGINE_LIBS},
        )
        self.prepare()
        prepared = self.fixture.out / "jniLibs"
        merged = Path(self.temp) / "merged"
        shutil.copytree(prepared, merged)
        pkg.verify_merged(prepared, merged)
        leaked = merged / "x86_64"
        leaked.mkdir()
        shutil.copyfile(merged / abi / "liblo-native-code.so", leaked / "liblo-native-code.so")
        with self.assertRaisesRegex(pkg.PackageError, "also appears in x86_64"):
            pkg.verify_merged(prepared, merged)

    def test_bitflip_is_rejected(self):
        self.fixture.build_stage()
        self.prepare()
        merged = self.merged(engine_bits={"libnss3.so": b"changed"})
        with self.assertRaises(pkg.PackageError) as caught:
            pkg.verify_merged(self.fixture.out / "jniLibs", merged)
        self.assertIn("x86_64/libnss3.so", str(caught.exception))

    def test_engine_library_in_another_abi_is_rejected(self):
        self.fixture.build_stage()
        self.prepare()
        merged = self.merged(other_abi_engine_lib="liblo-native-code.so")
        with self.assertRaises(pkg.PackageError) as caught:
            pkg.verify_merged(self.fixture.out / "jniLibs", merged)
        self.assertIn("arm64-v8a", str(caught.exception))

    def test_missing_merged_library_is_rejected(self):
        self.fixture.build_stage()
        self.prepare()
        merged = self.merged()
        target = merged / "lib" if (merged / "lib").is_dir() else merged
        (target / "x86_64" / "libssl3.so").unlink()
        with self.assertRaises(pkg.PackageError) as caught:
            pkg.verify_merged(self.fixture.out / "jniLibs", merged)
        self.assertIn("lack x86_64/libssl3.so", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
