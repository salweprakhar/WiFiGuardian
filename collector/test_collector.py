import json
import socket
import socketserver
import threading
import unittest
import urllib.error
import urllib.request
from types import SimpleNamespace
from collector import Counters, build_handlers, ThreadedProxy
from http.server import ThreadingHTTPServer

TOKEN = 'local-test-secret-abcdef'
class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.sendall(self.request.recv(4096)[::-1])

class TestCollector(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.echo = ThreadedProxy(('127.0.0.1',0), EchoHandler)
        cls.echo_thread = threading.Thread(target=cls.echo.serve_forever, daemon=True)
        cls.echo_thread.start()
        cls.config = SimpleNamespace(bind='127.0.0.1', client_ip='127.0.0.1',
            token=TOKEN, device_id='test-phone', device_name='My test phone', device_type='phone',
            allow_private_targets=True, ports={cls.echo.server_address[1]})
        cls.counters = Counters()
        proxy_handler, api_handler = build_handlers(cls.config, cls.counters)
        cls.proxy = ThreadedProxy(('127.0.0.1',0), proxy_handler)
        cls.api = ThreadingHTTPServer(('127.0.0.1',0), api_handler)
        cls.t1 = threading.Thread(target=cls.proxy.serve_forever, daemon=True)
        cls.t2 = threading.Thread(target=cls.api.serve_forever, daemon=True)
        cls.t1.start(); cls.t2.start()

    @classmethod
    def tearDownClass(cls):
        for server in (cls.echo, cls.proxy, cls.api): server.shutdown(); server.server_close()

    def request_api(self, token=TOKEN):
        request = urllib.request.Request('http://127.0.0.1:%d/traffic' % self.api.server_address[1],
                                         headers={'Authorization':'Bearer '+token})
        return urllib.request.urlopen(request, timeout=5)

    def test_requires_token(self):
        with self.assertRaises(urllib.error.HTTPError) as result:
            self.request_api('wrong-token-12345678')
        self.assertEqual(result.exception.code, 401)

    def test_rejects_wrong_destination_port(self):
        with socket.create_connection(self.proxy.server_address,timeout=3) as client:
            client.sendall(b'CONNECT example.com:80 HTTP/1.1\r\nHost: example.com\r\n\r\n')
            self.assertIn(b'502 Bad Gateway', client.recv(1024))

    def test_real_tunnel_bytes_and_device_identity(self):
        with socket.create_connection(self.proxy.server_address,timeout=5) as client:
            request = ('CONNECT 127.0.0.1:%d HTTP/1.1\r\nHost: localhost\r\n\r\n' % self.echo.server_address[1]).encode()
            client.sendall(request)
            self.assertIn(b'200 Connection Established', client.recv(1024))
            client.sendall(b'hello!')
            self.assertEqual(client.recv(6), b'!olleh')
        with self.request_api() as response:
            data = json.load(response)['devices'][0]
        self.assertEqual(data['id'], 'test-phone')
        self.assertEqual(data['type'], 'phone')
        self.assertGreaterEqual(data['upload_bytes'], 6)
        self.assertGreaterEqual(data['download_bytes'], 6)
        self.assertIn('proxy', data['source'].lower())

if __name__ == '__main__': unittest.main(verbosity=2)
