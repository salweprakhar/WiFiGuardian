import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

from router_collector import RouterMonitor, read_source
from traffic_feed import api_handler


def outbound(ip='192.168.1.20', host='8.8.8.8', up=100, down=500,
             protocol='tcp', port=443, sport=50001):
    number = 6 if protocol == 'tcp' else 17
    return (f'ipv4 2 {protocol} {number} 120 ESTABLISHED src={ip} dst={host} '
            f'sport={sport} dport={port} packets=2 bytes={up} '
            f'src={host} dst=203.0.113.1 sport={port} dport={sport} '
            f'packets=4 bytes={down} [ASSURED] mark=0 use=1')


class TestRouterMonitor(unittest.TestCase):
    def setUp(self):
        self.monitor = RouterMonitor(['192.168.1.0/24', 'fd00::/64'], ['192.168.1.1'])

    def device(self):
        return self.monitor.snapshot()['devices'][0]

    def test_outbound_nat_identity_direction_and_destination(self):
        self.monitor.update(outbound())
        device = self.device()
        self.assertEqual(device['ip'], '192.168.1.20')
        self.assertEqual((device['upload_bytes'], device['download_bytes']), (100, 500))
        connection = device['connections'][0]
        self.assertEqual((connection['host'], connection['port'], connection['protocol']), ('8.8.8.8', 443, 'TCP'))
        self.assertIn('port hint', connection['service'])

    def test_repeated_samples_do_not_double_count_and_closed_flows_keep_totals(self):
        self.monitor.update(outbound())
        self.monitor.update(outbound())
        self.monitor.update(outbound(up=125, down=750))
        self.assertEqual((self.device()['upload_bytes'], self.device()['download_bytes']), (125, 750))
        self.monitor.update('')
        self.assertEqual(self.device()['download_bytes'], 750)
        self.assertEqual(self.device()['active_connections'], 0)
        self.assertEqual(self.device()['connections'], [])
        self.monitor.update(outbound(up=10, down=20))
        self.assertEqual(self.device()['download_bytes'], 770)

    def test_reset_or_reused_tuple_counts_as_new_flow(self):
        self.monitor.update(outbound())
        self.monitor.update(outbound(up=5, down=600))
        self.assertEqual((self.device()['upload_bytes'], self.device()['download_bytes']), (105, 1100))

    def test_inbound_dnat_uses_internal_client_and_reverses_bytes(self):
        self.monitor.update('tcp 6 80 ESTABLISHED src=8.8.4.4 dst=203.0.113.1 '
            'sport=51515 dport=8443 packets=4 bytes=1000 '
            'src=192.168.1.30 dst=8.8.4.4 sport=443 dport=51515 packets=2 bytes=90')
        device = self.device()
        self.assertEqual(device['ip'], '192.168.1.30')
        self.assertEqual((device['upload_bytes'], device['download_bytes']), (90, 1000))
        self.assertEqual(device['connections'][0]['host'], '8.8.4.4')
        self.assertEqual(device['connections'][0]['port'], 51515)

    def test_ipv6_udp_and_icmp(self):
        self.monitor.update('ipv6 10 udp 17 80 src=fd00::20 dst=2606:4700:4700::1111 '
            'sport=53000 dport=443 packets=4 bytes=200 '
            'src=2606:4700:4700::1111 dst=fd00::20 sport=443 dport=53000 packets=8 bytes=900')
        self.assertEqual(self.device()['ip'], 'fd00::20')
        self.assertIn('QUIC', self.device()['connections'][0]['service'])
        self.monitor.update('ipv4 2 icmp 1 30 src=192.168.1.20 dst=8.8.8.8 type=8 code=0 id=7 packets=1 bytes=84 '
            'src=8.8.8.8 dst=203.0.113.1 type=0 code=0 id=7 packets=1 bytes=84')
        icmp = next(d for d in self.monitor.snapshot()['devices'] if d['ip'] == '192.168.1.20')
        self.assertEqual(icmp['connections'][0]['port'], 0)

    def test_ignores_router_lan_and_unrelated_connections(self):
        self.monitor.update('\n'.join([
            outbound(ip='192.168.1.1'), outbound(ip='10.0.0.2'),
            outbound(host='192.168.1.21'), outbound(),
        ]))
        self.assertEqual(len(self.monitor.snapshot()['devices']), 1)
        self.assertEqual(self.device()['upload_bytes'], 100)

    def test_multi_device_and_connection_display_limit(self):
        self.monitor.update('\n'.join([outbound(sport=50000 + i) for i in range(15)] +
                                      [outbound(ip='192.168.1.21', down=600)]))
        devices = {d['ip']: d for d in self.monitor.snapshot()['devices']}
        self.assertEqual(devices['192.168.1.20']['download_bytes'], 7500)
        self.assertEqual(devices['192.168.1.20']['active_connections'], 15)
        self.assertEqual(len(devices['192.168.1.20']['connections']), 12)
        self.assertEqual(devices['192.168.1.21']['download_bytes'], 600)

    def test_missing_accounting_is_reported_instead_of_fake_zero_usage(self):
        no_counts = outbound().replace('bytes=100 ', '').replace('bytes=500 ', '')
        with self.assertRaisesRegex(RuntimeError, 'nf_conntrack_acct'):
            self.monitor.update(no_counts)
        self.monitor.update(outbound(ip='192.168.1.21') + '\n' + no_counts)
        self.assertTrue(any('1 connections have no byte counters' in warning for warning in self.monitor.snapshot()['warnings']))

    def test_invalid_snapshot_is_atomic(self):
        self.monitor.update(outbound())
        with self.assertRaisesRegex(RuntimeError, 'Invalid conntrack'):
            self.monitor.update(outbound(up=300) + '\nnot a conntrack table')
        self.assertEqual(self.device()['upload_bytes'], 100)

    def test_initial_failure_stale_data_and_recovery(self):
        with self.assertRaisesRegex(RuntimeError, 'first router sample'):
            self.monitor.snapshot()
        self.monitor.update(outbound())
        self.monitor.fail('SSH disconnected')
        with self.assertRaisesRegex(RuntimeError, 'SSH disconnected'):
            self.monitor.snapshot()
        self.monitor.update(outbound(up=200))
        self.assertEqual(self.device()['upload_bytes'], 200)
        self.monitor.last_success -= 100
        with self.assertRaisesRegex(RuntimeError, 'stale'):
            self.monitor.snapshot()

    def test_reads_real_snapshot_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'conntrack'
            path.write_text(outbound(), encoding='utf-8')
            self.monitor.update(read_source(SimpleNamespace(conntrack_file=path)))
        self.assertEqual(self.device()['download_bytes'], 500)


class TestRouterFeed(unittest.TestCase):
    def test_http_auth_contract_unavailable_and_recovery(self):
        monitor = RouterMonitor(['192.168.1.0/24'])
        server = ThreadingHTTPServer(('127.0.0.1', 0), api_handler('test-token-123456789', monitor.snapshot))
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        def get(token='test-token-123456789', path='/traffic'):
            request = urllib.request.Request(f'http://127.0.0.1:{server.server_port}{path}',
                                             headers={'Authorization': 'Bearer ' + token})
            return urllib.request.urlopen(request, timeout=3)
        try:
            for token, path, status in [('bad', '/traffic', 401), ('test-token-123456789', '/missing', 404),
                                        ('test-token-123456789', '/traffic', 503)]:
                with self.assertRaises(urllib.error.HTTPError) as error:
                    get(token, path)
                self.assertEqual(error.exception.code, status)
                error.exception.close()
            monitor.update(outbound())
            with get() as response:
                payload = json.load(response)
                self.assertEqual(response.headers['Cache-Control'], 'no-store')
                self.assertEqual(payload['schema_version'], 2)
                self.assertEqual(payload['source_mode'], 'router')
                self.assertTrue(payload['session_id'])
                self.assertGreater(payload['observed_at_ms'], 0)
                self.assertEqual(payload['devices'][0]['connections'][0]['host'], '8.8.8.8')
            monitor.fail('Router offline')
            with self.assertRaises(urllib.error.HTTPError) as error:
                get()
            self.assertEqual(error.exception.code, 503)
            self.assertEqual(json.load(error.exception)['error'], 'Router offline')
            error.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=3)


if __name__ == '__main__':
    unittest.main(verbosity=2)
