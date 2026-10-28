#!/usr/bin/env python3
"""Totals of a surefire report directory: prints 'tests failed skipped' (failed = failures + errors)."""
import sys
import glob
import xml.etree.ElementTree as ET

tests = failed = skipped = 0
for path in glob.glob(sys.argv[1] + "/TEST-*.xml"):
    root = ET.parse(path).getroot()
    tests += int(root.get("tests", 0))
    failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))
print(tests, failed, skipped)
