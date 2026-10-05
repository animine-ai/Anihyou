#!/usr/bin/env python3
"""Reject optimized test APKs that drop the diagnostic sink or its log levels."""
import argparse
import fnmatch
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[2]
LOGGER = "Lcom/axiel7/anihyou/release/core/log/AppLog;"
MARKERS = ("AniHyou.", "diagnostic log on, profile=", "auto match start: series seasons=", "downloaded ext=")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def clean_rules(text):
    return "\n".join(line.split("#", 1)[0] for line in text.splitlines())


def verify_assumptions(rules):
    """Consumer optimizations are valid unless they can silence our diagnostic path."""
    owners = []
    protected = ("android.util.Log", "com.axiel7.anihyou.App", "com.axiel7.anihyou.AppKt",
                 "com.axiel7.anihyou.BuildConfig", "com.axiel7.anihyou.release.core.log.AppLog",
                 "com.axiel7.anihyou.release.core.log.AppLog$Sink",
                 "com.axiel7.anihyou.release.data.repository.SourceSeriesMatchingService")
    for rule in re.finditer(r"(?m)^\s*-(assume\w+)\b([\s\S]*?)(?=^\s*-\w|\Z)", rules):
        header = rule.group(2).split("{", 1)[0]
        match = re.search(r"\b(?:class|interface|enum)\s+([^\s{]+)", header)
        require(match is not None, "Unrecognized assumption rule: " + header.strip())
        # Compose animation 1.10 scopes this wildcard to one exact visual-debug return type.
        # AppLog, its sink and Android Log have no such methods. Reject every broader/member-added variant.
        body = rule.group(2).split("{", 1)[1].rsplit("}", 1)[0].strip() if "{" in rule.group(2) else ""
        if (rule.group(1) == "assumenosideeffects" and header.strip() == "class *" and
                re.fullmatch(r"static\s+androidx\.compose\.animation\.LookaheadAnimationVisualDebugConfig\s+\*\(\.\.\.\)\s+return\s+null\s*;", body)):
            owners.append("*:androidx.compose.animation.LookaheadAnimationVisualDebugConfig")
            continue
        # Treat all other wildcards conservatively, including negated filters or backreferences.
        for owner in match.group(1).split(","):
            require(owner and not owner.startswith("!") and "<" not in owner,
                    "Unsupported assumption filter: " + owner)
            potentially_protected = owner.startswith("com.axiel7.anihyou.") or any(
                fnmatch.fnmatchcase(name, owner) for name in protected)
            require(not potentially_protected, "Assumption can remove diagnostic data: " + owner)
            owners.append(owner)
    return sorted(set(owners))


def verify_rules(text):
    rules = clean_rules(text)
    owners = verify_assumptions(rules)
    require(not re.search(r"-maximumremovedandroidloglevel\s+[1-9]", rules), "Android log removal is forbidden")
    require("-dontobfuscate" in rules, "Diagnostic class names must remain readable")
    require(re.search(r"-keepattributes[^\n]*SourceFile[^\n]*LineNumberTable", rules), "Source lines must be retained")
    for name in ("com.axiel7.anihyou.release.core.log.AppLog**", "com.axiel7.anihyou.App"):
        require(re.search(r"-keep\s+class\s+" + re.escape(name) + r"\s*\{\s*\*;\s*\}", rules), "Missing full logger/application keep rule: " + name)
    return owners


def verify_source():
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    profile = gradle.split('create("performance") {', 1)[1].split('create("benchmarkRelease")', 1)[0]
    for setting in ('initWith(getByName("release"))', 'signingConfigs.getByName("debug")',
                    'isDebuggable = false', 'isMinifyEnabled = true', 'isShrinkResources = true',
                    'buildConfigField("boolean", "PERFORMANCE_LOGGING", "true")', 'proguardFile("proguard-performance.pro")'):
        require(setting in profile, "Missing performance setting: " + setting)
    app = (ROOT / "app/src/main/java/com/axiel7/anihyou/App.kt").read_text()
    require("if (BuildConfig.DEBUG || BuildConfig.PERFORMANCE_LOGGING) installDiagnosticLog()" in app,
            "Performance build must install the same diagnostic sink as debug")
    sink = app.split("private fun installDiagnosticLog()", 1)[1]
    for level, method in (("DEBUG", "d"), ("INFO", "i"), ("WARN", "w"), ("ERROR", "e")):
        require(re.search(r"AppLog\.Level\." + level + r"\s*->\s*android\.util\.Log\." + method, sink),
                "Missing Android log level " + level)
    require("chunked(3500)" in sink, "Long diagnostic messages must retain debug chunking")
    verify_rules((ROOT / "app/proguard-performance.pro").read_text())
    return {"minified": True, "resourceShrinking": True, "temporaryDebugSigning": True,
            "debuggable": False, "levels": ["DEBUG", "INFO", "WARN", "ERROR"], "readableSourceLines": True}


def dex_strings(data):
    require(data[:4] == b"dex\n" and len(data) >= 112, "Expected a standard DEX file")
    count, offset = struct.unpack_from("<II", data, 56)
    require(offset + count * 4 <= len(data), "Invalid DEX string table")
    for index in range(count):
        start = struct.unpack_from("<I", data, offset + index * 4)[0]
        require(start < len(data), "Invalid DEX string offset")
        # The UTF-16 length is a ULEB128 prefix, followed by zero-terminated MUTF-8.
        for _ in range(5):
            require(start < len(data), "Truncated DEX string prefix")
            byte = data[start]
            start += 1
            if byte < 128:
                break
        else:
            raise ValueError("Invalid DEX string prefix")
        end = data.find(b"\0", start)
        require(end >= 0, "Unterminated DEX string")
        yield data[start:end].decode("utf-8", errors="replace")


def verify_apk(path):
    with zipfile.ZipFile(path) as apk:
        dex = [name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        require(bool(dex), "APK has no DEX files")
        strings = {value for name in dex for value in dex_strings(apk.read(name))}
        require(LOGGER in strings, "AppLog class was removed from the APK")
        require("Lcom/axiel7/anihyou/release/core/log/AppLog$Level;" in strings, "AppLog level type was removed")
        for level in ("DEBUG", "INFO", "WARN", "ERROR"):
            require(level in strings, "Missing packaged log level " + level)
        for marker in MARKERS:
            require(any(marker in value for value in strings), "Missing packaged diagnostic marker " + marker)
        native = [name for name in apk.namelist() if name.startswith("lib/")]
        require(bool(native) and all(name.startswith("lib/arm64-v8a/") for name in native), "Download APK must contain only ARM64 native libraries")
    return {"apk": path.name, "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "dexFiles": dex, "diagnosticMarkers": list(MARKERS), "loggerAndAllLevelsPresent": True, "abi": "arm64-v8a"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path)
    parser.add_argument("--r8-config", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = {"sourceContract": verify_source(), "apks": []}
    if args.apk_dir:
        require(args.r8_config is not None, "Packaged check requires the actual merged R8 configuration")
        report["consumerAssumptionOwners"] = verify_rules(args.r8_config.read_text())
        apks = sorted(args.apk_dir.glob("*.apk"))
        require(len(apks) == 1, "Publish exactly one ARM64 APK")
        report["apks"] = [verify_apk(path) for path in apks]
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
