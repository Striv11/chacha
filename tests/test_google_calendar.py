import unittest

from brain.google_calendar import _event_row


class GoogleCalendarEventTests(unittest.TestCase):
    def test_timed_event_is_normalized_for_local_storage(self):
        row = _event_row({
            "id": "abc123",
            "summary": "Client review",
            "location": "Video call",
            "start": {"dateTime": "2026-11-20T14:30:00+05:30"},
            "end": {"dateTime": "2026-11-20T15:00:00+05:30"},
        })
        self.assertEqual(row, (
            "google:abc123", "Client review", "2026-11-20 14:30",
            "2026-11-20 15:00", "Video call", "google",
        ))

    def test_all_day_event_uses_midnight(self):
        row = _event_row({
            "id": "holiday",
            "summary": "Holiday",
            "start": {"date": "2026-11-21"},
            "end": {"date": "2026-11-22"},
        })
        self.assertEqual(row[2], "2026-11-21 00:00")
        self.assertEqual(row[3], "2026-11-22 00:00")
        self.assertEqual(row[5], "google")


if __name__ == "__main__":
    unittest.main()
