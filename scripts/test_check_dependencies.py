import unittest
from check_dependencies import query


class DependencyCheckTest(unittest.TestCase):
    def test_only_coordinates_are_sent_and_advisories_fail_closed(self):
        packages = [{"name": "fixture:library", "version": "1.0"}]
        def transport(payload):
            self.assertEqual({"queries": [{"package": {"name": "fixture:library", "ecosystem": "Maven"}, "version": "1.0"}]}, payload)
            return {"results": [{"vulns": [{"id": "SYNTHETIC-1"}]}]}
        self.assertEqual(["SYNTHETIC-1"], query(packages, transport)["affected"][0]["advisories"])
        self.assertEqual([], query(packages, lambda _: {"results": [{}]})["affected"])
        with self.assertRaises(ValueError):
            query(packages, lambda _: {"results": []})
        with self.assertRaises(ValueError):
            query(packages, lambda _: {"results": [{"error": "unavailable"}]})
        with self.assertRaises(ValueError):
            query([], lambda _: {"results": []})


if __name__ == "__main__":
    unittest.main()
