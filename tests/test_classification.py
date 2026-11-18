import unittest
from unittest.mock import patch

from brain.think import classify_message


class ClassificationGuardrailTests(unittest.TestCase):
    @patch("brain.think.llm.chat")
    def test_short_acknowledgement_is_not_a_followup(self, chat):
        chat.return_value = {"category": "needs_reply", "importance": 4, "tasks": [{"title": "Reply"}]}
        result = classify_message("Thanks, got it!", "Riya", learned_rules="none")
        self.assertEqual(result["category"], "fyi")
        self.assertEqual(result["importance"], 0)
        self.assertEqual(result["tasks"], [])

    @patch("brain.think.llm.chat")
    def test_outgoing_promise_always_becomes_a_task(self, chat):
        chat.return_value = {"category": "promise", "importance": 1, "summary": "Send deck", "tasks": []}
        result = classify_message("I will send the deck tomorrow.", "Neha", "out", learned_rules="none")
        self.assertEqual(result["importance"], 3)
        self.assertEqual(result["tasks"][0]["title"], "Send deck")

    @patch("brain.think.llm.chat")
    def test_broadcast_event_does_not_create_busywork(self, chat):
        chat.return_value = {"category": "event", "importance": 2, "tasks": [{"title": "Attend meetup"}]}
        result = classify_message("The meetup is Friday at 6 PM.", "Team", learned_rules="none")
        self.assertEqual(result["tasks"], [])


if __name__ == "__main__":
    unittest.main()
