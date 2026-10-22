import unittest
from analyze_retrieval_log import analyze


class RetrievalLogTest(unittest.TestCase):
    def test_redacted_errors_are_not_invented_and_finishes_are_not_double_counted(self):
        report = analyze([
            {'event': 'TOOL_ROUND_ERROR', 'details': {'run_id': 'r'}},
            {'event': 'MODEL_RESPONSE', 'details': {'request_id': 'a', 'input_tokens': 10}},
            {'event': 'MODEL_FINISH', 'details': {'request_id': 'a', 'status': 'completed', 'usage': {'input_tokens': 10, 'output_tokens': 2, 'cost_usd': '0.01'}}},
            {'event': 'MODEL_FINISH', 'details': {'request_id': 'a', 'status': 'completed', 'usage': {'input_tokens': 10}}}])
        self.assertEqual(1, report['tool_round_error_events'])
        self.assertEqual(1, report['events_without_tool_details'])
        self.assertEqual([], report['tool_failures'])
        self.assertEqual(1, report['provider_totals']['model_calls'])
        self.assertEqual(10, report['provider_totals']['input_tokens'])
        self.assertEqual('0.01', report['known_provider_cost_usd'])
        self.assertEqual(1, report['runs']['unknown']['model_calls'])

    def test_unknown_provider_usage_stays_unknown(self):
        report = analyze([{'event': 'MODEL_FINISH', 'details': {'status': 'failed', 'usage': {}}}])
        self.assertEqual(1, report['provider_totals']['input_tokens_unknown_requests'])
        self.assertNotIn('input_tokens', report['provider_totals'])
        self.assertIsNone(report['known_provider_cost_usd'])
