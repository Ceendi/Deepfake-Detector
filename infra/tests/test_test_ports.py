"""Keep Windows Docker probes outside the host's configured outbound port range."""

import sys
import unittest
from unittest.mock import patch

import test_discovery as discovery


class TestPortReservationTest(unittest.TestCase):
    def test_windows_reads_dynamic_range_and_releases_the_reserved_socket(self):
        output = 'Start Port : 49152\nNumber of Ports : 16384\n'
        with patch.object(sys, 'platform', 'win32'), \
                patch.object(discovery, 'command', return_value=output) as command, \
                patch.object(discovery.secrets, 'randbelow', side_effect=[40000, 2000]), \
                patch.object(discovery.socket, 'socket') as socket:
            self.assertEqual([12000], discovery.available_test_ports(1))
        command.assert_called_once_with('netsh', 'interface', 'ipv4', 'show', 'dynamicport', 'tcp')
        socket.return_value.bind.assert_called_once_with(('0.0.0.0', 12000))
        socket.return_value.close.assert_called_once()


if __name__ == '__main__':
    unittest.main(verbosity=2)
