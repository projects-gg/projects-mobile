#!/usr/bin/env python3
"""Puts upstream's prebuilt JRE 8 into app_pojavlauncher/src/main/assets/components/jre.

Upstream CI downloads the jre8-pojav artifact from AngelAuraMC/angelauramc-openjdk-build; the
same files ship inside every upstream release APK. This script takes them from a pinned upstream
release (checked by SHA-256) so local builds do not need a GitHub token. The JRE is OpenJDK 8
(GPLv2 with Classpath Exception); its source is https://github.com/AngelAuraMC/openjdk-multiarch-jdk8u.

Usage: python3 scripts/projects/fetch_upstream_jre8.py [path/to/Amethyst.apk]
"""
import hashlib
import os
import sys
import urllib.request
import zipfile

RELEASE = "1.1.7"
APK_URL = "https://github.com/AngelAuraMC/Amethyst-Android/releases/download/%s/Amethyst.apk" % RELEASE
APK_SHA256 = "6b5579e93f50a62f4d96279683bb447325e5a4de9015deae787eee56c248211f"
FILES = ["universal.tar.xz", "bin-arm.tar.xz", "bin-arm64.tar.xz", "bin-x86.tar.xz", "bin-x86_64.tar.xz", "version"]

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TARGET = os.path.join(ROOT, "app_pojavlauncher", "src", "main", "assets", "components", "jre")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    if len(sys.argv) > 1:
        apk = sys.argv[1]
    else:
        apk = os.path.join(ROOT, "build", "Amethyst-%s.apk" % RELEASE)
        if not os.path.isfile(apk):
            os.makedirs(os.path.dirname(apk), exist_ok=True)
            print("downloading", APK_URL)
            urllib.request.urlretrieve(APK_URL, apk)
    found = sha256(apk)
    if found != APK_SHA256:
        sys.exit("unexpected SHA-256 %s for %s (expected %s)" % (found, apk, APK_SHA256))
    os.makedirs(TARGET, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        for name in FILES:
            with z.open("assets/components/jre/" + name) as src, open(os.path.join(TARGET, name), "wb") as dst:
                dst.write(src.read())
            print("extracted", name)
    print("JRE 8 ready in", TARGET)


if __name__ == "__main__":
    main()
