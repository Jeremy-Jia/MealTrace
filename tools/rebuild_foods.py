#!/usr/bin/env python3
"""Compatibility entry point for the shared, pinned Android/iOS food catalog generator."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name('build_food_catalog.py')), run_name='__main__')
