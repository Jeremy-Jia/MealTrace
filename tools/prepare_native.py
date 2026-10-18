#!/usr/bin/env python3
"""Build a pinned, CPU-only ARM64 Qwen vision runner with the Android NDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REVISION = "547208dca250ceb3e4c8615598d043f2b602a527"


def run(args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def prepare_source(source):
    source.mkdir(parents=True, exist_ok=True)
    if not (source / ".git").exists():
        run(["git", "init", source])
        run(["git", "-C", source, "remote", "add", "origin", "https://github.com/ggml-org/llama.cpp.git"])
    run(["git", "-C", source, "fetch", "--depth=1", "origin", REVISION])
    run(["git", "-C", source, "checkout", "--detach", "--force", "FETCH_HEAD"])
    cli = source / "tools/mtmd/mtmd-cli.cpp"
    original = cli.read_text()
    old = '    ctx.chat_history.push_back(new_msg);\n    return formatted;'
    new = '    if (new_msg.role == "user") formatted += "<think>\\n\\n</think>\\n\\n";\n' + old
    if original.count(old) != 1:
        raise SystemExit("Pinned runner source no longer matches the audited Qwen no-thinking patch.")
    cli.write_text(original.replace(old, new))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path)
    args = parser.parse_args()
    ndk = args.ndk or Path(os.environ.get("ANDROID_NDK_HOME", ROOT.parent / "ndk/android-ndk-r30"))
    if not (ndk / "build/cmake/android.toolchain.cmake").exists():
        raise SystemExit("Set ANDROID_NDK_HOME to Android NDK r30, or pass --ndk.")
    cache = ROOT / ".native"
    source = cache / "llama"
    # This directory is an exclusively managed build cache, not the user's working tree.
    prepare_source(source)
    build = cache / "build-android"
    command = ["cmake", "-S", source, "-B", build,
               f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
               "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-30",
               "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
               "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF", "-DGGML_LLAMAFILE=OFF",
               "-DLLAMA_OPENSSL=OFF", "-DLLAMA_CURL=OFF", "-DBUILD_SHARED_LIBS=OFF",
               "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF", "-DLLAMA_BUILD_SERVER=OFF"]
    run(command)
    run(["cmake", "--build", build, "--target", "llama-mtmd-cli", "-j", "6"])
    target = ROOT / "app/src/main/jniLibs/arm64-v8a/libmealvision.so"
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(build / "bin/llama-mtmd-cli", target)
    strip = next(ndk.glob("toolchains/llvm/prebuilt/*/bin/llvm-strip"))
    run([strip, "--strip-unneeded", target])
    manifest = {
        "upstream": "https://github.com/ggml-org/llama.cpp",
        "revision": REVISION, "backend": "CPU ARM64, static C++ runtime",
        "patch": "Append an empty completed think block to Qwen's user generation prompt.",
        "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "size": target.stat().st_size,
        "configure_flags": [str(x) for x in command if str(x).startswith("-D") and "TOOLCHAIN" not in str(x)],
    }
    (ROOT / "app/src/main/assets/native-build.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(target, manifest["sha256"])


if __name__ == "__main__":
    main()
