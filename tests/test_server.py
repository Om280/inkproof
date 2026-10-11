import unittest
from backend.server import answer

class IsolationTests(unittest.TestCase):
    def test_questions_do_not_fall_back_to_demo_data(self):
        for question in ('2x + 6 = 14', 'x² + 4x + 1 = 0'):
            code, result = answer('check', {'question': question, 'solution': [{'id': question}]}, mock=False)
            self.assertEqual((code, result['status']), (503, 'not_configured'))
    def test_mock_is_labeled(self):
        code, result = answer('solve', {'question': 'x=2'}, mock=True)
        self.assertEqual(result['status'], 'mock')
        self.assertIn('MOCK MODE', result['message'])
    def test_empty_and_unrecognized_ink(self):
        self.assertEqual(answer('check', {'question': 'x=2', 'solution': []}, key='fake')[0], 400)
        self.assertEqual(answer('check', {'question': 'x=2', 'solution': [{}]}, key='fake')[1]['status'], 'unclear')

if __name__ == '__main__': unittest.main()
