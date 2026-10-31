#!/usr/bin/env python3
"""Checks native code for 16 KB memory page support.

Usage: python3 scripts/projects/check_16kb.py <apk|aar|zip|tar.xz|folder> [--json out.json]

Every 64-bit ELF file inside is checked: lib/<abi>/, assets/ (the LWJGL natives) and the Java
runtimes packed in assets/components/jre*/bin-*.tar.xz. A file is 16 KB ready when
  - every PT_LOAD segment has p_align >= 16384 and p_offset == p_vaddr (mod 16384), and
  - no writable data shares the last 16 KB page of PT_GNU_RELRO (after relocation the loader
    makes every page the RELRO segment touches read-only, so such data would fault on write).
Stored (uncompressed) lib/ entries must also start at a 16 KB offset in the archive.

Android's install-time check (the "isn't 16 KB compatible" dialog) only looks at
lib/<device abi>/ and only at p_align == 4096. Outside page size compat mode the runtime loader
refuses every 4 KB-aligned library, including the ones the app extracts or downloads itself.

Exit status: 1 when a 64-bit file under lib/ (in an APK: what the install check and Google Play
look at) is not 16 KB ready, otherwise 0.
"""
import io
import json
import os
import struct
import sys
import tarfile
import zipfile

PAGE = 16384
PT_LOAD, PT_GNU_RELRO, PF_W = 1, 0x6474E552, 2
MACHINES = {0xB7: "arm64-v8a", 0x3E: "x86_64", 0x28: "armeabi-v7a", 0x03: "x86"}


def inspect(data):
    """Returns (abi, bits, min p_align, problem) for an ELF file, or None."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    end = "<" if data[5] == 1 else ">"
    machine = struct.unpack_from(end + "H", data, 0x12)[0]
    loads, relros = [], []
    if is64:
        phoff = struct.unpack_from(end + "Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
        layout = end + "IIQQQQQQ"
    else:
        phoff = struct.unpack_from(end + "I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x2A)
        layout = end + "IIIIIIII"
    for i in range(phnum):
        fields = struct.unpack_from(layout, data, phoff + i * phentsize)
        if is64:
            p_type, flags, off, vaddr, _paddr, _filesz, memsz, align = fields
        else:
            p_type, off, vaddr, _paddr, _filesz, memsz, flags, align = fields
        if p_type == PT_LOAD:
            loads.append((off, vaddr, memsz, flags, align))
        elif p_type == PT_GNU_RELRO:
            relros.append((vaddr, memsz))
    min_align = min((l[4] for l in loads), default=0)
    problem = None
    if min_align < PAGE:
        problem = "%d KB aligned" % (min_align // 1024)
    elif any(off % PAGE != vaddr % PAGE for off, vaddr, _m, _f, _a in loads):
        problem = "offset/address mismatch"
    else:
        for rvaddr, rmemsz in relros:
            rend = rvaddr + rmemsz
            pend = -(-rend // PAGE) * PAGE
            if pend > rend and any(f & PF_W and v < pend and v + m > rend
                                   for _o, v, m, f, _a in loads):
                problem = "writable data in the last RELRO page"
    return MACHINES.get(machine, hex(machine)), 64 if is64 else 32, min_align, problem


def from_tar(fileobj, prefix):
    rows = []
    with tarfile.open(fileobj=fileobj, mode="r:*") as tar:
        for member in tar:
            name = member.name.lstrip("./")
            if member.isfile() and (".so" in os.path.basename(name) or name.endswith("bin/java")):
                rows.append((prefix + name, tar.extractfile(member).read(), None))
    return rows


def from_zip(path):
    rows = []
    with zipfile.ZipFile(path) as archive, open(path, "rb") as raw:
        for entry in archive.infolist():
            name = entry.filename
            if name.endswith(".so"):
                zip_ok = None
                # Only lib/ entries are mapped straight from the APK; assets are copied out first.
                if name.startswith("lib/") and entry.compress_type == zipfile.ZIP_STORED:
                    raw.seek(entry.header_offset)
                    name_len, extra_len = struct.unpack_from("<HH", raw.read(30), 26)
                    zip_ok = (entry.header_offset + 30 + name_len + extra_len) % PAGE == 0
                rows.append((name, archive.read(entry), zip_ok))
            elif "/bin-" in name and name.endswith((".tar.xz", ".tar.gz")):
                rows += from_tar(io.BytesIO(archive.read(entry)), name + "!/")
    return rows


def collect(path):
    if os.path.isdir(path):
        rows = []
        for folder, _dirs, files in os.walk(path):
            for file in files:
                if ".so" in file:
                    full = os.path.join(folder, file)
                    with open(full, "rb") as f:
                        rows.append((os.path.relpath(full, path).replace(os.sep, "/"), f.read(), None))
        return rows
    if path.endswith((".tar.xz", ".tar.gz", ".tgz")):
        with open(path, "rb") as f:
            return from_tar(f, "")
    return from_zip(path)


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        sys.exit(__doc__)
    json_out = args[args.index("--json") + 1] if "--json" in args else None
    results = []
    for name, data, zip_ok in collect(args[0]):
        info = inspect(data)
        if info is None:
            continue
        abi, bits, min_align, problem = info
        if zip_ok is False:
            problem = (problem + ", " if problem else "") + "stored entry not 16 KB aligned in the archive"
        results.append({"path": name, "abi": abi, "bits": bits, "min_align": min_align,
                        "ready": problem is None, "problem": problem})
    results.sort(key=lambda r: r["path"])
    if json_out:
        with open(json_out, "w") as f:
            json.dump(results, f, indent=1)

    # 16 KB pages only exist on 64-bit devices; 32-bit (and legacy mips) libraries are not checked.
    checked = [r for r in results if r["abi"] in ("arm64-v8a", "x86_64")]
    groups = {}
    for r in checked:
        where = "lib" if r["path"].startswith("lib/") else ("runtime" if "!/" in r["path"] else "other")
        total, bad = groups.get((r["abi"], where), (0, 0))
        groups[(r["abi"], where)] = (total + 1, bad + (0 if r["ready"] else 1))
    for (abi, where), (total, bad) in sorted(groups.items()):
        print("%-10s %-8s %3d files, %3d not 16 KB ready" % (abi, where, total, bad))
    for r in checked:
        if not r["ready"]:
            print("  %s: %s" % (r["path"], r["problem"]))
    sys.exit(1 if any(not r["ready"] and r["path"].startswith("lib/") for r in checked) else 0)


if __name__ == "__main__":
    main()
