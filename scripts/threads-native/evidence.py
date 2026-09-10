#!/usr/bin/env python3
"""Exact-source APK handoff and fail-closed, allowlisted instrumentation evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

CLASS = "io.element.android.libraries.matrix.impl.threads.NativeThreadReceiptTest"
EXPECTED = {
    "explicitReceiptsAndOwnLatestSurviveNativeSqliteReopen",
    "nativePaginationAndProductionListAdapterReachOlderRoots",
    "receiptOnlyRefreshNewRootAndBrowsingNeverWriteReceipts",
    "productionDirectoryResolvesReceiptChangesAcrossRoomsWithoutWriting",
    "confirmedNativeTextFileAndVoiceSendsPersistRecentButFailureDoesNot",
}
AAR_SHA256 = "5b3a4c337704b926137c55e1f2d36451bf8a90066a624ce8454752cc5323387b"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*command):
    return subprocess.check_output(command, text=True, stderr=subprocess.DEVNULL).strip()


def elf_identity(data):
    """Compare real GNU build ID and code/constant bytes, tolerating AGP debug stripping."""
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected little-endian ELF64")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    if header[2] != 62:
        raise ValueError("Expected x86_64 ELF machine")
    offset, size, count, names_index = header[6], header[11], header[12], header[13]
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, offset + index * size) for index in range(count)]
    names_section = sections[names_index]
    names = data[names_section[4]:names_section[4] + names_section[5]]
    content = {}
    for section in sections:
        start = section[0]
        name = names[start:names.index(b"\0", start)].decode("ascii")
        content[name] = data[section[4]:section[4] + section[5]]
    notes = content[".note.gnu.build-id"]
    offset, build_id = 0, None
    while offset < len(notes):
        name_size, description_size, kind = struct.unpack_from("<III", notes, offset)
        offset += 12
        name = notes[offset:offset + name_size]
        offset += (name_size + 3) & ~3
        description = notes[offset:offset + description_size]
        offset += (description_size + 3) & ~3
        if kind == 3 and name == b"GNU\0":
            build_id = description.hex()
    if not build_id or not content.get(".text") or not content.get(".rodata"):
        raise ValueError("Missing ELF build ID/code/constant evidence")
    return {"gnu_build_id": build_id,
            "text_sha256": hashlib.sha256(content[".text"]).hexdigest(),
            "rodata_sha256": hashlib.sha256(content[".rodata"]).hexdigest()}


def source_sha(expected):
    if not re.fullmatch(r"[0-9a-f]{40}", expected):
        raise ValueError("Expected an exact lowercase 40-character source SHA")
    if run("git", "rev-parse", "HEAD") != expected:
        raise ValueError("Checkout does not match expected SHA")
    if Path("libraries/rustsdk/matrix-rust-sdk.aar").exists():
        raise ValueError("Local SDK override forbidden")
    return expected


def package_apk(output, sha):
    source_sha(sha)
    apks = list(Path("libraries/matrix/impl/build/outputs/apk/androidTest/debug").glob("*.apk"))
    if len(apks) != 1:
        raise ValueError("Expected exactly one library instrumentation APK")
    apk = apks[0]
    sdk = Path(os.environ["ANDROID_HOME"])
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    manifest = ET.fromstring(run(str(analyzer), "manifest", "print", str(apk)))
    instrumentation = manifest.find("instrumentation")
    if instrumentation is None:
        raise ValueError("APK has no instrumentation")
    package = manifest.attrib["package"]
    runner = instrumentation.attrib[ANDROID + "name"]
    if package != instrumentation.attrib[ANDROID + "targetPackage"]:
        raise ValueError("Library fixture must be self-instrumenting, no app build required")
    if runner != "androidx.test.runner.AndroidJUnitRunner":
        raise ValueError("Unexpected runner")
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    aars = list((cache / "caches/modules-2/files-2.1/org.matrix.rustcomponents/sdk-android/26.09.08").glob("*/*.aar"))
    if len(aars) != 1 or digest(aars[0]) != AAR_SHA256:
        raise ValueError("Pinned SDK AAR checksum mismatch")
    with zipfile.ZipFile(aars[0]) as source, zipfile.ZipFile(apk) as packaged:
        native = source.read("jni/x86_64/libmatrix_sdk_ffi.so")
        packaged_native = packaged.read("lib/x86_64/libmatrix_sdk_ffi.so")
        native_identity = elf_identity(native)
        if native_identity != elf_identity(packaged_native):
            raise ValueError("Packaged SDK ELF build ID, machine code or read-only data differs from pinned AAR")
    output.mkdir(parents=True, exist_ok=True)
    target = output / "threads-native-test.apk"
    target.write_bytes(apk.read_bytes())
    metadata = {"source_sha": sha, "sdk_version": "26.09.08", "aar_sha256": AAR_SHA256,
                "apk_sha256": digest(target), "aar_native_x86_64_sha256": hashlib.sha256(native).hexdigest(),
                "packaged_native_x86_64_sha256": hashlib.sha256(packaged_native).hexdigest(), "native_elf": native_identity,
                "rust_source_sha": "0af7a3217d29c0f92b7cd888772357eff0c57186", "runtime_sdk_sha_expected": "0af7a3217",
                "package": package, "runner": runner, "expected_class": CLASS, "expected_tests": sorted(EXPECTED)}
    (output / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")


def safe_stack_line(line, diagnostic):
    """Allow class identifiers and owned Kotlin/Java source locations, never messages."""
    exception = re.match(r"^(?:(?:Caused by:|Suppressed:)\s*)?((?:org\.matrix\.|io\.element\.|java\.|kotlin\.|kotlinx\.|org\.junit\.|androidx\.)[A-Za-z0-9_.$]+)(?::|$)", line.strip())
    if exception and exception[1] not in diagnostic["exception_classes"] and len(diagnostic["exception_classes"]) < 8:
        diagnostic["exception_classes"].append(exception[1])
    frame = re.match(r"^\s*at ((?:io\.element\.android\.|org\.matrix\.rustcomponents\.sdk\.)[A-Za-z0-9_.$<>-]+)\(([A-Za-z0-9_$-]+\.(?:kt|java)):(\d+)\)\s*$", line)
    if frame and len(diagnostic["frames"]) < 24:
        diagnostic["frames"].append({"symbol": frame[1], "file": frame[2], "line": int(frame[3])})


def parse_instrumentation(text):
    """Raw streams/stacks are discarded after extracting allowlisted diagnostics."""
    bundle, started, finished, errors = {}, [], {}, []
    diagnostics, in_stack = {}, False
    pending_diagnostic = {"exception_classes": [], "frames": []}
    final_code = None
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator and key in {"class", "test", "numtests"}:
                bundle[key] = value
            in_stack = key == "stack"
            if in_stack:
                safe_stack_line(value, pending_diagnostic)
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1])
            test = bundle.get("test")
            if test is not None:
                valid = bundle.get("class") == CLASS and test in EXPECTED and bundle.get("numtests") == str(len(EXPECTED))
                if not valid:
                    errors.append("unexpected_case_or_count")
                elif code == 1:
                    started.append(test)
                elif code in {0, -1, -2, -3, -4}:
                    if test in finished:
                        errors.append("duplicate_result")
                    finished[test] = "passed" if code == 0 else "failed"
                    if code != 0:
                        diagnostics[test] = pending_diagnostic
                else:
                    errors.append("unexpected_status")
            elif code not in {1, 2}:
                errors.append("missing_case_identity")
            bundle = {}
            pending_diagnostic = {"exception_classes": [], "frames": []}
            in_stack = False
        elif line.startswith("INSTRUMENTATION_CODE: "):
            final_code = int(line.split(":", 1)[1])
        elif in_stack:
            safe_stack_line(line, pending_diagnostic)
    if set(started) != EXPECTED or len(started) != len(EXPECTED):
        errors.append("missing_or_duplicate_start")
    if set(finished) != EXPECTED:
        errors.append("missing_results")
    if final_code != -1:
        errors.append("runner_did_not_finish")
    if any(value != "passed" for value in finished.values()):
        errors.append("test_failed_or_skipped")
    return {"expected": len(EXPECTED), "observed": len(finished), "cases": finished,
            "errors": sorted(set(errors)), "diagnostics": diagnostics, "passed": not errors}


def write_results(result, output):
    output.mkdir(parents=True, exist_ok=True)
    (output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    suite = ET.Element("testsuite", name=CLASS, tests=str(len(EXPECTED)),
                       failures=str(sum(result["cases"].get(case) != "passed" for case in EXPECTED)), errors="0", skipped="0")
    for case in sorted(EXPECTED):
        node = ET.SubElement(suite, "testcase", classname=CLASS, name=case)
        if result["cases"].get(case) != "passed":
            failure = ET.SubElement(node, "failure", message="Native case failed or did not execute; raw messages intentionally excluded")
            failure.text = json.dumps(result.get("diagnostics", {}).get(case, {}), indent=2)
    ET.ElementTree(suite).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)


def instrument(handoff, output, sha):
    source_sha(sha)
    build = json.loads((handoff / "build.json").read_text())
    apk = handoff / "threads-native-test.apk"
    if build["source_sha"] != sha or digest(apk) != build["apk_sha256"] or build["aar_sha256"] != AAR_SHA256:
        raise ValueError("APK handoff identity mismatch")
    if build["expected_class"] != CLASS or set(build["expected_tests"]) != EXPECTED:
        raise ValueError("APK plan mismatch")
    package, runner = build["package"], build["runner"]
    if not re.fullmatch(r"[a-zA-Z0-9_.]+", package) or runner != "androidx.test.runner.AndroidJUnitRunner":
        raise ValueError("Invalid instrumentation component")
    output.mkdir(parents=True, exist_ok=True)
    (output / "build.json").write_text(json.dumps(build, indent=2) + "\n")
    run("adb", "wait-for-device")
    if run("adb", "shell", "getprop", "ro.product.cpu.abi") != "x86_64":
        raise ValueError("Expected x86_64 emulator")
    run("adb", "install", "-r", str(apk))
    component = package + "/" + runner
    if component not in run("adb", "shell", "pm", "list", "instrumentation"):
        raise ValueError("Expected installed instrumentation missing")
    device = {name: run("adb", "shell", "getprop", prop) for name, prop in {
        "abi": "ro.product.cpu.abi", "api": "ro.build.version.sdk", "build_fingerprint": "ro.build.fingerprint"}.items()}
    (output / "device.json").write_text(json.dumps(device, indent=2) + "\n")
    try:
        execution = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS, component],
                                   capture_output=True, text=True, timeout=900)
        result = parse_instrumentation(execution.stdout)
        if execution.returncode != 0:
            result["errors"].append("adb_failed")
            result["passed"] = False
    except subprocess.TimeoutExpired:
        result = parse_instrumentation("")
        result["errors"].append("instrumentation_timeout")
    write_results(result, output)
    print(json.dumps({"native_cases_observed": result["observed"], "passed": result["passed"], "errors": result["errors"]}))
    if not result["passed"]:
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["package", "instrument", "verify"])
    parser.add_argument("--sha", required=True)
    parser.add_argument("--handoff", type=Path, default=Path("native-handoff"))
    parser.add_argument("--evidence", type=Path, default=Path("native-evidence"))
    args = parser.parse_args()
    if args.mode == "package":
        package_apk(args.handoff, args.sha)
    elif args.mode == "instrument":
        instrument(args.handoff, args.evidence, args.sha)
    else:
        source_sha(args.sha)
        result = json.loads((args.evidence / "results.json").read_text())
        audit = json.loads((args.evidence / "audit.json").read_text())
        server = json.loads((args.evidence / "server.json").read_text())
        assert result["passed"] and result["observed"] == len(EXPECTED)
        assert set(result["cases"]) == EXPECTED and set(result["cases"].values()) == {"passed"}
        assert audit["sss_successes"] > 0 and audit["thread_pages"] >= 2
        assert audit["receipt_writes"] == 1, "Only the explicit native positive control may write a receipt"
        assert audit["browse_after"] - audit["browse_before"] == 0, "Browsing window must have zero receipt writes"
        assert server["synapse"] == "1.160.0" and server["native_sss"] and server["synthetic_only"]
        print("Verified: exact-source native test plan, nonzero cases, SSS, pagination and write-audit positive control")


if __name__ == "__main__":
    main()
