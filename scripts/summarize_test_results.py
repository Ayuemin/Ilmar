"""Report actual JUnit XML counts without printing fixtures, prompts or failure bodies."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

files = sorted(Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
if not files:
    raise SystemExit("No JUnit XML results were produced")
summary = {"suites": len(files), "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for path in files:
    root = ET.parse(path).getroot()
    for key in ("tests", "failures", "errors", "skipped"):
        summary[key] += int(root.get(key, "0"))
print(json.dumps({"junit_summary": summary}))
raise SystemExit(1 if summary["failures"] or summary["errors"] else 0)
