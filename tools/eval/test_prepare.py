import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from prepare import select


class SelectionTests(unittest.TestCase):
    def test_stratified_selection_is_reproducible_and_order_independent(self):
        rows = [{'id': f'{group}-{i}', 'category': group} for group in ['a', 'b', 'c'] for i in range(10)]
        chosen = select(rows, 9, 123)
        self.assertEqual(chosen, select(list(reversed(rows)), 9, 123))
        self.assertEqual(len({r['id'] for r in chosen}), 9)
        self.assertEqual([sum(r['category'] == c for r in chosen) for c in ['a', 'b', 'c']], [3, 3, 3])

    def test_invalid_sample_sizes_fail_instead_of_omitting_strata(self):
        rows = [{'id': '1', 'category': 'a'}, {'id': '2', 'category': 'b'}]
        for size in [0, 1, 3]:
            with self.assertRaises(ValueError):
                select(rows, size, 123)


if __name__ == '__main__':
    unittest.main()
