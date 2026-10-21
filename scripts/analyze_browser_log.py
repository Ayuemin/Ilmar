"""Summarize exported diagnostics without double counting mirrored usage events or exposing page text."""
import argparse
from collections import Counter
from decimal import Decimal
import json


def analyze(path):
    with open(path, encoding="utf-8") as source:
        records = [json.loads(line) for line in source if line.strip()]
    responses = [r["details"] for r in records if r.get("event") == "MODEL_RESPONSE"]
    requests = [r["details"] for r in records if r.get("event") == "MODEL_REQUEST"]
    results = [r["details"] for r in records if r.get("event") == "BROWSER_RESULT"]
    errors = Counter()
    for record in records:
        if record.get("event") not in ("TOOL_ROUND", "TOOL_ROUND_ERROR"):
            continue
        messages = record.get("details", {}).get("messages", [])
        if isinstance(messages, str):
            messages = json.loads(messages)
        for message in messages:
            if message.get("role") != "tool":
                continue
            try:
                error = json.loads(message.get("content", "{}" )).get("error")
                if error:
                    errors[str(error)] += 1
            except (ValueError, AttributeError):
                pass
    cost = sum((Decimal(str(r["cost_usd"])) for r in responses if r.get("cost_usd") is not None), Decimal(0))
    return {
        "build_sha": records[0].get("sha"),
        "model_requests": len(requests), "model_responses": len(responses),
        "usage_totals": {key: sum(r.get(key) or 0 for r in responses) for key in ("input_tokens", "output_tokens", "cached_tokens", "reasoning_tokens")},
        "provider_reported_cost_usd": str(cost),
        "responses_without_reported_cost": sum(r.get("cost_usd") is None for r in responses),
        "provider_search_enabled_requests": sum(r.get("search_route") == "PROVIDER" for r in requests),
        "browser_results": len(results),
        "model_visible_result_utf8_bytes": sum(r.get("result_utf8_bytes", 0) for r in results if r.get("model_visible", True)),
        "tool_errors": dict(errors),
        "rounds": [{"round": i + 1, **{k: r.get(k) for k in ("model", "input_tokens", "output_tokens", "cached_tokens", "cost_usd", "tool_calls")}} for i, r in enumerate(responses)],
        "caveats": ["Costs are reported by provider usage, not a billing invoice.", "Search fees cannot be separated from generation costs in this export.", "Reasoning tokens may be included in output; do not add them again.", "Each MODEL_RESPONSE is counted once; mirrored BROWSER_MODEL_ROUND is excluded."]
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log")
    args = parser.parse_args()
    print(json.dumps(analyze(args.log), ensure_ascii=False, indent=2))
