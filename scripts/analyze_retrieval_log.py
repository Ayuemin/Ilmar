"""Audit exported JSONL without writing document text/arguments to the report.

Following-error usage is adjacency, NOT established causal overhead. Older
metadata-only exports cannot disclose tool arguments/types removed at export.
"""
import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json


def analyze(events):
    groups = Counter()
    failures = Counter()
    request_ids = set()
    totals = Counter()
    cost = Decimal(0)
    cost_known = 0
    missing_error_details = 0
    finishes = [e for e in events if e.get('event') == 'MODEL_FINISH']
    model_events = finishes or [e for e in events if e.get('event') == 'MODEL_RESPONSE']
    for event in model_events:
        d = event.get('details', {})
        rid = d.get('request_id')
        if rid and rid in request_ids:
            continue
        if rid:
            request_ids.add(rid)
        if d.get('provider_started') is False:
            totals['model_not_sent'] += 1
            continue
        totals['model_calls'] += 1
        totals['model_successes' if d.get('status', 'completed') == 'completed' else 'model_failures'] += 1
        u = d.get('usage') or d
        for dest, keys in [('input_tokens', ('input', 'input_tokens', 'prompt_tokens')), ('output_tokens', ('output', 'output_tokens', 'completion_tokens')), ('cached_tokens', ('cached', 'cached_tokens'))]:
            value = next((u.get(k) for k in keys if u.get(k) is not None), None)
            if value is not None:
                totals[dest] += int(value)
            else:
                totals[dest + '_unknown_requests'] += 1
        amount = u.get('cost_usd')
        if amount is not None:
            cost += Decimal(str(amount)); cost_known += 1
    for event in events:
        if event.get('event') != 'TOOL_ROUND_ERROR':
            continue
        d = event.get('details', {})
        groups[str(d.get('run_id', 'unknown'))] += 1
        messages = d.get('messages')
        if isinstance(messages, list):
            calls = {c.get('id'): c.get('function', {}) for m in messages for c in m.get('tool_calls', [])}
            for m in messages:
                if m.get('role') != 'tool':
                    continue
                try:
                    output = json.loads(m.get('content', '{}'))
                except (ValueError, TypeError):
                    continue
                if not isinstance(output, dict) or 'error' not in output:
                    continue
                call = calls.get(m.get('tool_call_id'), {})
                raw = call.get('arguments', '')
                try:
                    raw = json.dumps(json.loads(raw), sort_keys=True, separators=(',', ':'), ensure_ascii=False)
                except (ValueError, TypeError):
                    pass
                fingerprint = hashlib.sha256(str(raw).encode()).hexdigest()
                # Allow only code-shaped errors; never print freeform provider/document messages.
                code = str(output['error'])
                if not (code.isascii() and code.replace('_', '').isalpha() and code.isupper() and len(code) <= 64):
                    code = 'UNCLASSIFIED_ERROR'
                failures[(call.get('name', 'unknown'), code, fingerprint)] += 1
        elif d.get('tool_failures'):
            for f in d['tool_failures'].values():
                failures[(f.get('tool', 'unknown'), f.get('error_code', 'unknown'), f.get('argument_fingerprint', 'unknown'))] += 1
        else:
            missing_error_details += 1
    runs = {}
    for event in model_events:
        d = event.get('details', {})
        run = d.get('run_id', 'unknown')
        runs.setdefault(run, []).append(event)
    run_reports = {}
    for run, run_events in runs.items():
        sub = analyze_models_only(run_events)
        sub['tool_round_error_events'] = groups[run]
        run_reports[run] = sub
    steps = {e['step_id']: e for e in events if e.get('event') == 'TASK_STEP'}
    execution_counts = {}
    for e in events:
        if e.get('event') != 'TASK_EXECUTION_LINK' or e.get('step_id') not in steps:
            continue
        step = steps[e['step_id']]
        run = e.get('run_id', 'unknown')
        label = step.get('tool_name', 'unknown') + ':' + step.get('status', 'unknown')
        execution_counts.setdefault(run, Counter())[label] += 1
    return {'runs': run_reports, 'execution_links_by_final_step_status': {k: dict(v) for k, v in execution_counts.items()},
            'tool_round_error_events': sum(groups.values()), 'errors_by_run': dict(groups),
            'events_without_tool_details': missing_error_details,
            'tool_failures': [{'tool': k[0], 'code': k[1], 'argument_sha256': k[2], 'count': v} for k, v in failures.items()],
            'provider_totals': dict(totals), 'known_provider_cost_usd': str(cost) if cost_known else None,
            'cost_known_requests': cost_known,
            'limits': 'Counts are observed events, not proof of root cause or causal extra costs. Metadata-only logs cannot recover removed arguments/results. No estimates substituted for provider usage.'}


def analyze_models_only(events):
    seen = set()
    totals = Counter()
    cost = Decimal(0)
    known = 0
    for e in events:
        d = e.get('details', {})
        rid = d.get('request_id')
        if rid and rid in seen:
            continue
        seen.add(rid)
        if d.get('provider_started') is False:
            totals['not_sent'] += 1
            continue
        totals['model_calls'] += 1
        u = d.get('usage') or d
        for key in ['input_tokens', 'output_tokens', 'cached_tokens']:
            if u.get(key) is not None:
                totals[key] += int(u[key])
            else:
                totals[key + '_unknown_requests'] += 1
        if u.get('cost_usd') is not None:
            cost += Decimal(str(u['cost_usd'])); known += 1
    return {**dict(totals), 'known_provider_cost_usd': str(cost) if known else None, 'cost_known_requests': known}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('jsonl')
    args = p.parse_args()
    with open(args.jsonl, encoding='utf-8') as source:
        events = [json.loads(line) for line in source if line.strip()]
    print(json.dumps(analyze(events), ensure_ascii=False, indent=2))
