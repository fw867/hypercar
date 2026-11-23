import runpy
import unittest
from pathlib import Path

tool = runpy.run_path(str(Path(__file__).with_name("compare-carplay-syn.py")))


class SynTraceTest(unittest.TestCase):
    def test_closed_port_in_later_marker_is_old(self):
        lines = [
            "CTRL-CLICK: marker=1 epochMs=100000 elapsedMs=1000",
            "CTRL-CLICK: marker=1 deltaMs=0 epochMs=100000 elapsedMs=1000 event=click-state currentPort=37611",
            "CTRL-CLICK: marker=2 epochMs=105000 elapsedMs=6000",
            "CTRL-CLICK: marker=2 deltaMs=1000 epochMs=106000 elapsedMs=7000 event=listener-close closedPort=37611",
        ]
        events = tool["endpoint_events"](lines, 1, 100000)
        rows = ["107\t192.0.2.1\t\t192.0.2.2\t\t37611"]
        car, phone = map(tool["ip_address"], ("192.0.2.1", "192.0.2.2"))
        result, = tool["compare"](rows, 100000, events, {car}, {phone}, [])
        self.assertIn("old-port", result)

    def test_duplicate_number_requires_epoch_and_isolates_nearby_processes(self):
        lines = []
        for at, port in [(100000, 37611), (100500, 45678)]:
            lines.extend([f"CTRL-CLICK: marker=1 epochMs={at} elapsedMs=100",
                          f"CTRL-CLICK: marker=1 deltaMs=0 epochMs={at} elapsedMs=100 event=click-state currentPort={port}"])
        with self.assertRaisesRegex(ValueError, "100000, 100500"):
            tool["select_anchor"](lines, 1)
        for at, port in [(100000, 37611), (100500, 45678)]:
            self.assertEqual(tool["select_anchor"](lines, 1, at), (at, 1))
            self.assertEqual(tool["endpoint_events"](lines, 1, at), [(at, "click-state", port)])


if __name__ == "__main__":
    unittest.main()
