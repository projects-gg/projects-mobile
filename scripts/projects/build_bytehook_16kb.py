#!/usr/bin/env python3
"""Builds bytehook 1.0.10 with 16 KB page alignment (M8, docs: mobile.md "16 KB page size").

bytehook 1.0.10 (bytedance/bhook, MIT) ships 4 KB-aligned libraries, and its bh_util.c rounds mprotect's range to the
headers' fixed 4 KB PAGE_SIZE, so on a 16 KB device its hooks fail. 1.1.0 and later fix both, but upstream went back to
1.0.10 because the hooks did not work properly on the newer version (614603ae1). So this keeps 1.0.10's code with one
change taken from 1.1.0 (the device's page size, read once at load) and builds the tag's sources with the NDK's 16 KB
page switch, then repacks Maven Central's 1.0.10 AAR around the new libraries (classes.jar and the headers stay as
published). The result is com.bytedance:bytehook:1.0.10-16kb in app_pojavlauncher/libs/maven, a Maven repository
folder in the tree.

Usage:  python scripts/projects/build_bytehook_16kb.py [--ndk DIR] [--cmake DIR] [--work DIR]

Needs git, CMake and Ninja (the Android SDK's cmake/3.22.1), an NDK r27 or later (default: the project's NDK) and the
network (GitHub, Maven Central). Ends with check_16kb.py's test on every 64-bit library of the new AAR (jni/ and
prefab/): exit status 1 while one of them is not 16 KB ready.
"""

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TAG = "v1.0.10"
TAG_COMMIT = "642257e470a3d80ebc3428ff1e3b2d195721b636"
REPO = "https://github.com/bytedance/bhook.git"
MAVEN = "https://repo1.maven.org/maven2/com/bytedance/bytehook/1.0.10/bytehook-1.0.10.aar"
VERSION = "1.0.10-16kb"
OUT_DIR = os.path.join(ROOT, "app_pojavlauncher", "libs", "maven", "com", "bytedance", "bytehook", VERSION)
ABIS = ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"]
API = 21
# A fixed time for the repacked entries, so the same build gives the same file.
ZIP_TIME = (2024, 1, 18, 0, 0, 0)


def run(cmd, cwd=None):
    print("+ " + " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def project_ndk_version():
    with io.open(os.path.join(ROOT, "build.gradle"), encoding="utf-8") as f:
        m = re.search(r'projectsNdkVersion\s*=\s*"([0-9.]+)"', f.read())
    return m.group(1) if m else None


def host_tag():
    if sys.platform.startswith("win"):
        return "windows-x86_64"
    if sys.platform == "darwin":
        return "darwin-x86_64"
    return "linux-x86_64"


def exe(name):
    return name + (".exe" if sys.platform.startswith("win") else "")


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def checkout(work):
    src = os.path.join(work, "bhook")
    if not os.path.isdir(os.path.join(src, ".git")):
        run(["git", "clone", "--depth", "1", "--branch", TAG, REPO, src])
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=src, check=True, capture_output=True, text=True).stdout.strip()
    if head != TAG_COMMIT:
        sys.exit("bhook %s is %s, expected %s" % (TAG, head, TAG_COMMIT))
    return src


# bh_util.c rounds mprotect's range with the headers' fixed 4 KB PAGE_SIZE, which the NDK's 16 KB switch hides (and
# which is wrong on a 16 KB device: mprotect refuses a start that is not page aligned, so every hook would fail).
# This is how bytehook 1.1.0 does it: the device's page size, read once when the library loads.
PAGE_SIZE_OLD = """#define PAGE_START(addr) ((addr) & (uintptr_t)PAGE_MASK)
"""
PAGE_SIZE_NEW = """// Projects (16 KB pages): the device's page size, not the headers' fixed PAGE_SIZE (bytehook 1.1.0's way).
static uintptr_t bh_util_page_size = 4096;

__attribute__((constructor)) static void bh_util_page_size_ctor(void) {
  long size = sysconf(_SC_PAGESIZE);
  if (size > 0) bh_util_page_size = (uintptr_t)size;
}

#define PAGE_START(addr) ((addr) & ~(bh_util_page_size - 1))
"""
PAGE_END_OLD = ") + PAGE_SIZE;"
PAGE_END_NEW = ") + bh_util_page_size;"


def replace_once(path, old, new, count=1):
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    if text.count(old) != count:
        sys.exit("%s changed: %r found %d times, expected %d" % (os.path.basename(path), old, text.count(old), count))
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text.replace(old, new))


def prepared_sources(src, work):
    """The library's sources: the device's page size in bh_util.c, and no -Werror (a newer clang warns about more under
    -Weverything)."""
    cpp = os.path.join(work, "cpp")
    if os.path.isdir(cpp):
        shutil.rmtree(cpp)
    shutil.copytree(os.path.join(src, "bytehook", "src", "main", "cpp"), cpp)
    replace_once(os.path.join(cpp, "CMakeLists.txt"), " -Werror", "")
    util = os.path.join(cpp, "bh_util.c")
    replace_once(util, PAGE_SIZE_OLD, PAGE_SIZE_NEW)
    replace_once(util, PAGE_END_OLD, PAGE_END_NEW, count=2)
    return cpp


def build(cpp, work, ndk, cmake_dir):
    toolchain = os.path.join(ndk, "build", "cmake", "android.toolchain.cmake")
    libs = {}
    for abi in ABIS:
        out = os.path.join(work, "out", abi)
        if os.path.isdir(out):
            shutil.rmtree(out)
        run([os.path.join(cmake_dir, exe("cmake")), "-S", cpp, "-B", out, "-G", "Ninja",
             "-DCMAKE_MAKE_PROGRAM=" + os.path.join(cmake_dir, exe("ninja")),
             "-DCMAKE_TOOLCHAIN_FILE=" + toolchain, "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-%d" % API,
             "-DANDROID_STL=none", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DCMAKE_BUILD_TYPE=Release"])
        run([os.path.join(cmake_dir, exe("cmake")), "--build", out])
        lib = os.path.join(out, "libbytehook.so")
        if not os.path.isfile(lib):
            sys.exit("no libbytehook.so for " + abi)
        stripped = os.path.join(out, "stripped", "libbytehook.so")
        os.makedirs(os.path.dirname(stripped), exist_ok=True)
        strip = os.path.join(ndk, "toolchains", "llvm", "prebuilt", host_tag(), "bin", exe("llvm-strip"))
        run([strip, "--strip-unneeded", "-o", stripped, lib])
        libs[abi] = (lib, stripped)
    return libs


def ndk_major(ndk):
    with io.open(os.path.join(ndk, "source.properties"), encoding="utf-8") as f:
        m = re.search(r"Pkg\.Revision\s*=\s*(\d+)", f.read())
    return int(m.group(1))


def repack(original, libs, major):
    """Maven Central's AAR with the new libraries: stripped in jni/, unstripped for prefab, abi.json naming the NDK."""
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename
            data = src.read(name)
            m = re.match(r"jni/([^/]+)/libbytehook\.so$", name)
            p = re.match(r"prefab/modules/bytehook/libs/android\.([^/]+)/(libbytehook\.so|abi\.json)$", name)
            if m:
                data = open(libs[m.group(1)][1], "rb").read()
            elif p and p.group(2) == "libbytehook.so":
                data = open(libs[p.group(1)][0], "rb").read()
            elif p:
                abi = json.loads(data.decode("utf-8"))
                abi["api"] = API
                abi["ndk"] = major
                data = (json.dumps(abi, indent=2) + "\n").encode("utf-8")
            entry = zipfile.ZipInfo(name, ZIP_TIME)
            entry.external_attr = info.external_attr
            entry.compress_type = zipfile.ZIP_STORED if name.endswith("/") else zipfile.ZIP_DEFLATED
            dst.writestr(entry, data)
    return out.getvalue()


POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <!-- bytehook 1.0.10 (bytedance/bhook %s, MIT) rebuilt with 16 KB page alignment and the device's page size in
       bh_util.c: scripts/projects/build_bytehook_16kb.py. -->
  <groupId>com.bytedance</groupId>
  <artifactId>bytehook</artifactId>
  <version>%s</version>
  <packaging>aar</packaging>
  <licenses>
    <license>
      <name>The MIT License</name>
      <url>https://opensource.org/licenses/MIT</url>
    </license>
  </licenses>
</project>
"""


def main():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Android/Sdk")
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--ndk", default=os.path.join(sdk, "ndk", project_ndk_version() or "27.3.13750724"))
    parser.add_argument("--cmake", default=os.path.join(sdk, "cmake", "3.22.1", "bin"))
    parser.add_argument("--work", default=os.path.join(ROOT, "build", "bytehook-16kb"))
    args = parser.parse_args()
    if ndk_major(args.ndk) < 27:
        sys.exit("an NDK r27 or later is needed (ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES)")
    os.makedirs(args.work, exist_ok=True)
    src = checkout(args.work)
    libs = build(prepared_sources(src, args.work), args.work, args.ndk, args.cmake)
    original = fetch(MAVEN)
    expected = fetch(MAVEN + ".sha1").decode("ascii").split()[0].strip()
    if hashlib.sha1(original).hexdigest() != expected:
        sys.exit("Maven Central's AAR does not match its SHA-1")
    aar = repack(original, libs, ndk_major(args.ndk))
    os.makedirs(OUT_DIR, exist_ok=True)
    target = os.path.join(OUT_DIR, "bytehook-%s.aar" % VERSION)
    with open(target, "wb") as f:
        f.write(aar)
    with io.open(os.path.join(OUT_DIR, "bytehook-%s.pom" % VERSION), "w", encoding="utf-8", newline="\n") as f:
        f.write(POM % (TAG, VERSION))
    print("wrote %s (%d bytes, sha256 %s)" % (target, len(aar), hashlib.sha256(aar).hexdigest()))
    # check_16kb.py's own rule: every 64-bit library in the AAR (jni/ and prefab/) must be ready.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    sys.dont_write_bytecode = True  # no __pycache__ in the tree
    import check_16kb
    bad = 0
    for name, data, _zip_ok in check_16kb.collect(target):
        info = check_16kb.inspect(data)
        if info is None or info[1] != 64:
            continue
        print("%-60s %s" % (name, "ok" if info[3] is None else info[3]))
        bad += info[3] is not None
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
