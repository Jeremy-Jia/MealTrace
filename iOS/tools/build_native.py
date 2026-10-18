#!/usr/bin/env python3
"""Build pinned llama/mtmd and the small C ABI wrapper with the selected Apple SDK."""
import os
from pathlib import Path
import subprocess
import shutil

ROOT = Path(__file__).resolve().parents[1]
PIN = '547208dca250ceb3e4c8615598d043f2b602a527'

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

def source():
    path = Path(os.environ.get('MEALTRACE_LLAMA_SOURCE', ROOT / '.native/llama.cpp'))
    if not (path / '.git').exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        run('git', 'clone', '--filter=blob:none', '--no-checkout', 'https://github.com/ggml-org/llama.cpp.git', path)
        run('git', '-C', path, 'checkout', '--detach', PIN)
    actual = subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != PIN:
        raise SystemExit(f'llama.cpp must be pinned to {PIN}; found {actual}')
    # Only library sources are used. The Android CLI-only no-thinking patch is irrelevant here.
    dirty = subprocess.check_output(['git', '-C', str(path), 'diff', '--name-only'], text=True).splitlines()
    if any(name != 'tools/mtmd/mtmd-cli.cpp' for name in dirty):
        raise SystemExit('Unexpected changes in the native dependency; use a clean pinned checkout.')
    return path

def main():
    sdk = os.environ.get('PLATFORM_NAME', 'iphoneos')
    if sdk not in ('iphoneos', 'iphonesimulator'):
        raise SystemExit(f'Unsupported SDK: {sdk}')
    src = source()
    out = ROOT / 'build' / sdk
    out.mkdir(parents=True, exist_ok=True)
    sdkpath = subprocess.check_output(['xcrun', '--sdk', sdk, '--show-sdk-path'], text=True).strip()
    arch = os.environ.get('CURRENT_ARCH', 'arm64')
    if arch not in ('arm64', 'x86_64'):
        arch = 'arm64'
    cmake = shutil.which('cmake') or '/opt/homebrew/bin/cmake'
    generator = 'Ninja' if shutil.which('ninja') else 'Unix Makefiles'
    run(cmake, '-S', ROOT / 'Native', '-B', out, '-G', generator,
        f'-DLLAMA_SOURCE={src}', '-DCMAKE_SYSTEM_NAME=iOS', f'-DCMAKE_OSX_SYSROOT={sdkpath}',
        f'-DCMAKE_OSX_ARCHITECTURES={arch}', '-DCMAKE_OSX_DEPLOYMENT_TARGET=17.0',
        '-DCMAKE_BUILD_TYPE=Release', '-DGGML_METAL_USE_BF16=OFF')
    run(cmake, '--build', out, '--target', 'mealvision', '-j', '6')
    archives = [out / 'libmealvision.a', out / 'llama/tools/mtmd/libmtmd.a', out / 'llama/src/libllama.a']
    archives.extend(sorted((out / 'llama/ggml/src').rglob('libggml*.a')))
    archives.extend(sorted((out / 'llama/vendor').rglob('*.a')))
    for archive in archives:
        if not archive.exists():
            raise SystemExit(f'Missing archive: {archive}')
    run('xcrun', 'libtool', '-static', '-o', out / 'libmealvision-combined.a', *archives)

if __name__ == '__main__':
    main()
