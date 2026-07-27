from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from example_tools.server import HttpExampleRunner


class HttpExampleRunnerTest(unittest.TestCase):
    def test_await_port_ignores_empty_and_partial_file_contents(self) -> None:
        process = Mock()
        process.poll.return_value = None
        port_file = Mock()
        port_file.is_file.return_value = True
        port_file.read_text.side_effect = ["", "4", "43123", "43123"]

        with patch("example_tools.server.time.sleep"):
            port = HttpExampleRunner._await_port(process, port_file, Mock())

        self.assertEqual(43123, port)
        self.assertEqual(4, port_file.read_text.call_count)
