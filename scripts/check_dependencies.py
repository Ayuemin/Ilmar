"""Check resolved public Maven coordinates against OSV. Sends package/version metadata only."""
import argparse
import json
from pathlib import Path
from urllib.request import Request, urlopen


def query(packages, transport=None):
    if not packages or any(not p.get("name") or not p.get("version") for p in packages):
        raise ValueError("A nonempty resolved dependency inventory is required")
    payload = {"queries": [{"package": {"name": p["name"], "ecosystem": "Maven"}, "version": p["version"]} for p in packages]}
    if transport is None:
        def transport(value):
            request = Request("https://api.osv.dev/v1/querybatch", data=json.dumps(value).encode(),
                              headers={"Content-Type": "application/json", "User-Agent": "Ilmar-dependency-audit"})
            with urlopen(request, timeout=60) as response:
                return json.load(response)
    results = transport(payload).get("results")
    if not isinstance(results, list) or len(results) != len(packages):
        raise ValueError("OSV returned an incomplete result; no clean-scan claim is possible")
    for result in results:
        if not isinstance(result, dict) or "error" in result or not isinstance(result.get("vulns", []), list):
            raise ValueError("OSV query failed; no clean-scan claim is possible")
        if any(not isinstance(v, dict) or not isinstance(v.get("id"), str) for v in result.get("vulns", [])):
            raise ValueError("Malformed OSV advisory")
    affected = [{**p, "advisories": [v["id"] for v in result.get("vulns", [])]} for p, result in zip(packages, results) if result.get("vulns")]
    return {"source": "https://osv.dev", "packages_checked": len(packages), "affected": affected,
            "scope": "resolved debug runtime and unit-test Maven artifacts; not a full SBOM or device acceptance"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    packages = json.loads(args.inventory.read_text())
    report = query(packages)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    return 1 if report["affected"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
