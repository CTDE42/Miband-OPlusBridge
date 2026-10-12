#!/usr/bin/env python3
"""Every runtimeReuseFiles target in compat/upstream.json has to exist in this tree."""
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
data = json.loads((root / "compat" / "upstream.json").read_text())
missing = []
for item in data["runtimeReuseFiles"]:
    target = root / item["target"]
    if not target.is_file():
        missing.append(item["target"])
if missing:
    print("upstream.json targets missing:", file=sys.stderr)
    for path in missing:
        print("  " + path, file=sys.stderr)
    sys.exit(1)
print(f"{len(data['runtimeReuseFiles'])} upstream targets exist")
