import unittest

from brain.think import phone_app_request
from brain.server import SCREEN_PROMPT, Screen, _goal_complete, _goal_navigation


class PhoneAutomationRoutingTests(unittest.TestCase):
    def test_routes_known_app_commands(self):
        cases = {
            "Open Settings and show me the battery usage screen": "settings",
            "please launch WhatsApp Business and find Rahul": "whatsapp_business",
            "Go to Maps, search for Cubbon Park": "maps",
            "Use Chrome to open example.com": "chrome",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(phone_app_request(text), expected)

    def test_routes_explicit_custom_app(self):
        self.assertEqual(phone_app_request("Open the Myntra app and find black shoes"), "myntra")

    def test_does_not_hijack_normal_conversation(self):
        self.assertIsNone(phone_app_request("How do I open a stuck jar?"))
        self.assertIsNone(phone_app_request("Tell me about Android Settings"))

    def test_screen_agent_cannot_stop_at_app_home(self):
        self.assertIn("Opening the named app is only the first step", SCREEN_PROMPT)
        self.assertIn("destination is visibly the current screen", SCREEN_PROMPT)

    def test_requested_screen_navigation_is_deterministic(self):
        home = Screen(
            goal="Open Settings and show me the battery usage screen",
            nodes=["0: Settings", "1: Network & internet", "2: Battery", "3: Charged"],
        )
        self.assertEqual(_goal_navigation(home)["index"], 2)
        battery = Screen(
            goal=home.goal,
            history=["tap Battery"],
            nodes=["0: Battery", "1: Battery usage", "2: Battery Saver"],
        )
        self.assertEqual(_goal_navigation(battery)["index"], 1)
        battery.history.append("tap Battery usage")
        self.assertIsNone(_goal_navigation(battery))
        self.assertTrue(_goal_complete(battery))


if __name__ == "__main__":
    unittest.main()
