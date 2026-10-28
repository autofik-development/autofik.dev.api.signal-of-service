#!/usr/bin/env python3
"""Coverage percentages from jacoco.csv: prints 'lines statements functions branches'."""
import csv
import sys


def pct(missed, covered):
    total = missed + covered
    return "%.2f" % (100.0 * covered / total) if total else "0.00"


totals = {}
with open(sys.argv[1], newline="", encoding="utf-8") as f:
    for row in csv.DictReader(f):
        for k in ("LINE", "INSTRUCTION", "METHOD", "BRANCH"):
            m, c = totals.get(k, (0, 0))
            totals[k] = (m + int(row[k + "_MISSED"]), c + int(row[k + "_COVERED"]))
print(*(pct(*totals[k]) for k in ("LINE", "INSTRUCTION", "METHOD", "BRANCH")))
