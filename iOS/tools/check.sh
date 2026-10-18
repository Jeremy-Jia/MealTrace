#!/bin/zsh
set -euo pipefail
cd "${0:A:h}/.."
mkdir -p build/check
swiftc -swift-version 5 Sources/Core/Domain.swift Tests/main.swift -o build/check/domain-tests
build/check/domain-tests Resources
python3 -m py_compile tools/build_native.py
plutil -lint Info.plist
