#!/usr/bin/env python3
"""Verify bundled runner provenance and build the personal-install Android APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--native", action="store_true", help="Rebuild native runner from pinned upstream source")
    p.add_argument("--release", action="store_true", help="Omit debug-only functional verification runner")
    args = p.parse_args()
    if args.native:
        subprocess.run([sys.executable, str(ROOT / "tools/prepare_native.py")], check=True)
    manifest = json.loads((ROOT / "app/src/main/assets/native-build.json").read_text())
    binary = ROOT / "app/src/main/jniLibs/arm64-v8a/libmealvision.so"
    if not binary.exists() or hashlib.sha256(binary.read_bytes()).hexdigest() != manifest["sha256"]:
        raise SystemExit("Native runner is absent or changed. Rebuild with --native.")
    env = os.environ.copy()
    if "JAVA_HOME" not in env:
        brew = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
        if brew.exists(): env["JAVA_HOME"] = str(brew)
    if "ANDROID_HOME" not in env and (ROOT.parent / "android-sdk").exists():
        env["ANDROID_HOME"] = str(ROOT.parent / "android-sdk")
    if "GRADLE_USER_HOME" not in env and (ROOT.parent / "gradle-home").exists():
        env["GRADLE_USER_HOME"] = str(ROOT.parent / "gradle-home")
    tasks = [":app:assembleRelease" if args.release else ":app:assembleDebug", ":app:testDebugUnitTest", ":app:lintDebug"]
    subprocess.run(["sh", str(ROOT / "gradlew"), *tasks], cwd=ROOT, env=env, check=True)


if __name__ == "__main__": main()
