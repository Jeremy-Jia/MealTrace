#!/usr/bin/env python3
"""Build the optional Hexagon worker in a Linux SDK environment, or stage a local build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from prepare_native import REVISION, ROOT, prepare_source, run


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ndk", type=Path)
    p.add_argument("--sdk", type=Path, help="Hexagon SDK 6.6.0.0 (build mode)")
    p.add_argument("--runner", type=Path, help="Import an already built worker (requires --skeleton and --source)")
    p.add_argument("--skeleton", type=Path)
    p.add_argument("--source", type=Path)
    args = p.parse_args()
    ndk = args.ndk or Path(os.environ.get("ANDROID_NDK_HOME", ROOT.parent / "ndk/android-ndk-r30"))
    toolbin = next(ndk.glob("toolchains/llvm/prebuilt/*/bin"))
    if args.runner:
        if not args.skeleton or not args.source:
            p.error("Import requires --runner, --skeleton, --source")
        source, runner, skeleton = args.source, args.runner, args.skeleton
        revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
        if revision != REVISION or "mealtrace_worker(ctx" not in (source / "tools/mtmd/mtmd-cli.cpp").read_text():
            raise SystemExit("Runner source must be pinned and include the worker extension.")
        if (source / "tools/mtmd/mealtrace-worker.inc").read_bytes() != (ROOT / "tools/native/worker.inc").read_bytes():
            raise SystemExit("Worker source differs from this repository.")
    else:
        sdk = args.sdk or Path(os.environ.get("HEXAGON_SDK_ROOT", "/opt/hexagon/6.6.0.0"))
        if not sdk.is_dir(): raise SystemExit("Build in the documented Hexagon SDK container, or import a local build.")
        source = ROOT / ".native/accel-llama"
        prepare_source(source)
        sys.path.insert(0, str(ROOT / "tools/native"))
        from apply_worker import apply
        apply(source)
        build = ROOT / ".native/build-hexagon"
        run(["cmake", "-S", source, "-B", build,
             f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
             "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-30", "-DANDROID_STL=c++_static",
             "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF", "-DGGML_NATIVE=OFF",
             "-DGGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod+i8mm", "-DGGML_HEXAGON=ON",
             "-DGGML_OPENCL=OFF", "-DGGML_VULKAN=OFF", "-DGGML_OPENMP=OFF", "-DGGML_LLAMAFILE=OFF",
             "-DLLAMA_OPENSSL=OFF", "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF",
             "-DLLAMA_BUILD_SERVER=OFF", f"-DHEXAGON_SDK_ROOT={sdk}"])
        run(["cmake", "--build", build, "--target", "llama-mtmd-cli", "htp-v81", "-j", "4"])
        runner = build / "bin/llama-mtmd-cli"
        skeleton = build / "ggml/src/ggml-hexagon/libggml-htp-v81.so"
    dest = ROOT / "app/src/main/jniLibs/arm64-v8a"
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(runner, dest / "libmealvision_npu.so")
    run([toolbin / "llvm-strip", "--strip-unneeded", dest / "libmealvision_npu.so"])
    shutil.copyfile(skeleton, dest / "libggml-htp-v81.so")
    run([toolbin / "aarch64-linux-android30-clang", "-O2", "-march=armv8-a",
         ROOT / "tools/native/capabilities.c", "-ldl", "-o", dest / "libmealaccelprobe.so"])
    run([toolbin / "llvm-strip", "--strip-unneeded", dest / "libmealaccelprobe.so"])
    names = ["libmealvision_npu.so", "libggml-htp-v81.so", "libmealaccelprobe.so"]
    manifest = {
        "upstream": "https://github.com/ggml-org/llama.cpp", "revision": REVISION,
        "patches": ["Existing Qwen no-thinking prompt", "tools/native/worker.inc and apply_worker.py"],
        "worker_protocol": 1, "backend": "Hexagon HTP0 with V81 DSP skeleton; no OpenCL/Vulkan",
        "cpu_requirement": "FP16 scalar/vector, dotprod and i8mm; gated by ARMv8 capability executable",
        "cpu_fallback": "Separate generic ARM64 libmealvision.so, no vendor dependencies",
        "sdk": "Hexagon SDK 6.6.0.0, tools 19.0.07", "android_ndk": ndk.name,
        "sdk_image": "ghcr.io/snapdragon-toolchain/arm64-android@sha256:91714433626f0d94a926538a1e46ec43756c5b8e3262b91b95df1e812940aed1",
        "worker_source_sha256": hashlib.sha256((ROOT / "tools/native/worker.inc").read_bytes()).hexdigest(),
        "files": [{"name": n, "size": (dest/n).stat().st_size,
                   "sha256": hashlib.sha256((dest/n).read_bytes()).hexdigest()} for n in names],
    }
    (ROOT / "app/src/main/assets/acceleration-build.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__": main()
