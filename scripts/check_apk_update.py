"""Inspect signed APKs without installing them. A report is not phone acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def inspect(apk, tools):
    verified = subprocess.run(
        [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, text=True, capture_output=True,
    ).stdout
    signers = sorted(set(s.lower().replace(":", "") for s in re.findall(
        r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)", verified)))
    if not signers:
        raise ValueError("No verified APK signer certificate")
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)],
                             check=True, text=True, capture_output=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    if not package:
        raise ValueError("APK package/version metadata missing")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"package": package[1], "version_code": int(package[2]), "version_name": package[3],
            "signer_sha256": signers, "apk_sha256": digest, "signature_verified": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    candidate, reference = inspect(args.candidate, args.build_tools), inspect(args.reference, args.build_tools)
    checks = {"same_package": candidate["package"] == reference["package"],
              "version_increases": candidate["version_code"] > reference["version_code"],
              "same_signer": candidate["signer_sha256"] == reference["signer_sha256"]}
    compatible = all(checks.values())
    report = {"candidate": candidate, "reference": reference, "checks": checks,
              "update_compatible": compatible,
              "reference_run": "https://github.com/Ayuemin/Ilmar/actions/runs/39479866305",
              "installation": "eligible for in-place phone testing" if compatible else
                  "BLOCKED: do not uninstall or clear app data to bypass signer/version mismatch",
              "phone_data_preservation_verified": False}
    rendered = json.dumps(report, indent=2, ensure_ascii=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write("## APK update compatibility\n\n" + report["installation"] +
                          "\n\n```json\n" + rendered + "\n```\n")
    # A valid build can have an incompatible signer. Publish that fact explicitly;
    # never report installation/data preservation as tested or attempt a reinstall.


if __name__ == "__main__":
    main()
