#!/usr/bin/env python3
"""Bind product APK native entries to the pinned, same-run Wasmtime build."""
import argparse
import hashlib
import json
import pathlib
import re
import struct
import subprocess
import zipfile

LOCK_SHA256 = "0f1caff29b8444068b46e96c3a3641d3d805d86c827a4b2ed9189b86a00fd7df"
ABI_MACHINES = {"arm64-v8a": 183, "x86_64": 62}
UNSUPPORTED_RUNTIME_ABIS = {"armeabi-v7a", "x86"}
NATIVE_PATHS = ["Cargo.lock", *[f"{abi}/libarex_runtime.so" for abi in ABI_MACHINES]]
JNI_PREFIX = "Java_com_axiel7_anihyou_release_data_extension_WasmtimeNativeBridge_"
JNI_METHODS = {
    "nativeValidate", "nativeExecute", "nativeCancel", "nativeMetrics",
    "nativeRuntimeVersion", "nativeClearModuleCache",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def native_hashes(directory):
    result = {}
    for name in NATIVE_PATHS:
        path = directory / name
        require(path.is_file() and path.stat().st_size > 0, f"Missing native build input: {path}")
        result[name] = sha256(path.read_bytes())
    require(result["Cargo.lock"] == LOCK_SHA256, "Native Cargo.lock dependency graph drift")
    return result


def verify_manifest(directory, hashes):
    records = (directory / "SHA256SUMS").read_text().splitlines()
    require(len(records) == len(NATIVE_PATHS), "Incomplete or duplicate native manifest")
    parsed = {}
    for record in records:
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", record)
        require(match is not None, "Invalid native checksum record")
        digest, name = match.groups()
        require(name in NATIVE_PATHS and name not in parsed, "Unexpected or duplicate native checksum path")
        parsed[name] = digest
    require(parsed == hashes, "Native artifact checksum mismatch")


def verify_elf(path, abi, readelf):
    data = path.read_bytes()
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", f"Expected ELF64 little-endian runtime: {abi}")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    require(header[1] == 3 and header[2] == ABI_MACHINES[abi], f"Wrong shared-object architecture: {abi}")
    program_offset, entry_size, entry_count = header[5], header[9], header[10]
    require(entry_size >= 56 and entry_count > 0, f"Missing ELF program headers: {abi}")
    loads = 0
    for index in range(entry_count):
        offset = program_offset + index * entry_size
        require(offset + 56 <= len(data), f"Truncated ELF program header: {abi}")
        segment = struct.unpack_from("<IIQQQQQQ", data, offset)
        if segment[0] == 1:
            loads += 1
            require(segment[7] >= 16384 and segment[2] % 16384 == segment[3] % 16384,
                    f"ELF load segment is not 16 KiB compatible: {abi}")
    require(loads > 0, f"Missing ELF load segments: {abi}")
    symbols = subprocess.check_output([readelf, "--dyn-syms", "--wide", str(path)], text=True)
    defined = set()
    for line in symbols.splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[3:6] == ["FUNC", "GLOBAL", "DEFAULT"] and fields[6] != "UND":
            defined.add(fields[7])
    missing = {JNI_PREFIX + method for method in JNI_METHODS} - defined
    require(not missing, f"Missing JNI exports for {abi}: {sorted(missing)}")
    return {"abi": abi, "elfMachine": header[2], "loadSegments": loads,
            "jniExports": sorted(JNI_PREFIX + method for method in JNI_METHODS)}


def verify_apks(hashes, apk_directory):
    metadata_files = sorted(apk_directory.rglob("output-metadata.json"))
    require(metadata_files, f"No APK output metadata under {apk_directory}")
    reports = []
    for metadata_file in metadata_files:
        metadata = json.loads(metadata_file.read_text())
        coverage = set()
        elements = metadata.get("elements", [])
        require(elements, f"No APK outputs in {metadata_file}")
        for element in elements:
            name = element["outputFile"]
            require(pathlib.Path(name).name == name, "APK output path must be a basename")
            apk = metadata_file.parent / name
            abi_filters = [item["identifier"] for item in element.get("filters", [])
                           if item["filterType"] == "ABI"]
            require(len(abi_filters) <= 1, "Ambiguous APK ABI filter")
            abi = abi_filters[0] if abi_filters else None
            require(abi is None or abi in ABI_MACHINES or abi in UNSUPPORTED_RUNTIME_ABIS,
                    f"Unknown product APK ABI: {abi}")
            expected_abis = set(ABI_MACHINES) if abi is None else ({abi} if abi in ABI_MACHINES else set())
            coverage.add(abi or "universal")
            expected_entries = {f"lib/{item}/libarex_runtime.so" for item in expected_abis}
            with zipfile.ZipFile(apk) as archive, apk.open("rb") as raw:
                actual_entries = [item.filename for item in archive.infolist()
                                  if item.filename.endswith("/libarex_runtime.so")]
                require(len(actual_entries) == len(set(actual_entries)) and set(actual_entries) == expected_entries,
                        f"Runtime ABI coverage mismatch in {apk}: {actual_entries}")
                for entry in sorted(expected_entries):
                    packaged_abi = entry.split("/")[1]
                    info = archive.getinfo(entry)
                    require(sha256(archive.read(entry)) == hashes[f"{packaged_abi}/libarex_runtime.so"],
                            f"Packaged native bytes differ from pinned build: {apk} / {entry}")
                    require(info.compress_type == zipfile.ZIP_STORED, f"Native library unexpectedly compressed: {entry}")
                    raw.seek(info.header_offset + 26)
                    filename_size, extra_size = struct.unpack("<HH", raw.read(4))
                    data_offset = info.header_offset + 30 + filename_size + extra_size
                    require(data_offset % 16384 == 0, f"APK native entry is not 16 KiB aligned: {apk} / {entry}")
            reports.append({"apk": str(apk), "apkSha256": sha256(apk.read_bytes()), "abi": abi or "universal",
                            "runtimeSupported": bool(expected_abis), "runtimeEntries": sorted(expected_entries)})
        require({"universal", *ABI_MACHINES} <= coverage,
                f"Missing universal or supported ABI APK outputs in {metadata_file}: {sorted(coverage)}")
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", type=pathlib.Path, default=pathlib.Path("tools/ep02-android/native-out"))
    parser.add_argument("--readelf", default="llvm-readelf")
    parser.add_argument("--write-checksums", action="store_true")
    parser.add_argument("--apk-dir", type=pathlib.Path, action="append", default=[])
    parser.add_argument("--report", type=pathlib.Path)
    args = parser.parse_args()
    hashes = native_hashes(args.native_dir)
    report = {"nativeSha256": hashes, "unsupportedRuntimeAbis": sorted(UNSUPPORTED_RUNTIME_ABIS)}
    if args.write_checksums:
        report["elf"] = [verify_elf(args.native_dir / f"{abi}/libarex_runtime.so", abi, args.readelf)
                         for abi in ABI_MACHINES]
        (args.native_dir / "SHA256SUMS").write_text("".join(f"{hashes[name]}  {name}\n" for name in NATIVE_PATHS))
    verify_manifest(args.native_dir, hashes)
    report["apks"] = [item for directory in args.apk_dir
                      for item in verify_apks(hashes, directory)]
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
