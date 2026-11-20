#!/usr/bin/env python3
"""Consent-based HTTP CONNECT proxy + WiFi Guardian traffic-counter endpoint.
Only an explicitly allowlisted client IP is accepted. No TLS decryption, no MITM.
"""
import argparse
import ipaddress
import json
import secrets
import selectors
import socket
import socketserver
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

class Counters:
    def __init__(self):
        self.lock = threading.Lock()
        self.up = 0
        self.down = 0
        self.sessions = 0
    def add(self, direction, count):
        with self.lock:
            if direction == 'up': self.up += count
            else: self.down += count
    def snapshot(self):
        with self.lock:
            return self.up, self.down, self.sessions
    def session(self):
        with self.lock: self.sessions += 1

def build_handlers(config, counters):
    class ProxyHandler(socketserver.BaseRequestHandler):
        def handle(self):
            peer = self.client_address[0]
            if peer != config.client_ip:
                self.request.sendall(b'HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n')
                return
            self.request.settimeout(10)
            raw = b''
            while b'\r\n\r\n' not in raw and len(raw) < 16384:
                part = self.request.recv(4096)
                if not part: return
                raw += part
            if b'\r\n\r\n' not in raw: return
            header, remainder = raw.split(b'\r\n\r\n', 1)
            try:
                first = header.split(b'\r\n', 1)[0].decode('ascii')
                method, authority, version = first.split(' ')
                if method.upper() != 'CONNECT' or not version.startswith('HTTP/'):
                    self.request.sendall(b'HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n')
                    return
                # CONNECT requests are for explicitly configured browser HTTPS traffic.
                if authority.count(':') != 1: raise ValueError('Invalid authority')
                host, port_text = authority.rsplit(':', 1)
                port = int(port_text)
                if port not in config.ports: raise ValueError('Port denied')
                if not host or any(c in host for c in '/\\@ %'):
                    raise ValueError('Invalid hostname')
                addrinfos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
                allowed = []
                for af, socktype, proto, canon, address in addrinfos:
                    ip = ipaddress.ip_address(address[0])
                    if ip.is_global or (config.allow_private_targets and (ip.is_private or ip.is_loopback)):
                        allowed.append((af, socktype, proto, address))
                if not allowed: raise ValueError('Destination not allowed')
                last_error = None
                upstream = None
                for af, socktype, proto, address in allowed:
                    try:
                        upstream = socket.socket(af, socktype, proto)
                        upstream.settimeout(5)
                        upstream.connect(address)
                        break
                    except OSError as exc:
                        last_error = exc
                        if upstream: upstream.close()
                        upstream = None
                if upstream is None: raise OSError(str(last_error))
            except (ValueError, OSError, UnicodeDecodeError) as exc:
                self.request.sendall(b'HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n')
                return
            counters.session()
            try:
                self.request.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
                if remainder:
                    upstream.sendall(remainder)
                    counters.add('up', len(remainder))
                self.request.settimeout(None)
                upstream.settimeout(None)
                with selectors.DefaultSelector() as selector:
                    selector.register(self.request, selectors.EVENT_READ, 'up')
                    selector.register(upstream, selectors.EVENT_READ, 'down')
                    while True:
                        events = selector.select(timeout=90)
                        if not events: break
                        running = True
                        for key, mask in events:
                            source = key.fileobj
                            target = upstream if source is self.request else self.request
                            data = source.recv(65536)
                            if not data:
                                running = False
                                break
                            target.sendall(data)
                            counters.add(key.data, len(data))
                        if not running: break
            except (OSError, ConnectionError, TimeoutError):
                pass
            finally:
                upstream.close()
    class ApiHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            if urlsplit(self.path).path != '/traffic':
                self.send_error(404); return
            if not secrets.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + config.token):
                self.send_error(401); return
            up, down, sessions = counters.snapshot()
            blob = json.dumps({'devices': [{
                'id': config.device_id, 'ip': config.client_ip,
                'name': config.device_name, 'type': config.device_type,
                'download_bytes': down, 'upload_bytes': up,
                'source': 'Opt-in CONNECT proxy'
            }]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(blob)))
            self.end_headers()
            self.wfile.write(blob)
        def log_message(self, fmt, *args):
            pass
    return ProxyHandler, ApiHandler

class ThreadedProxy(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

def start(config):
    counters = Counters()
    proxy_handler, api_handler = build_handlers(config, counters)
    proxy = ThreadedProxy((config.bind, config.proxy_port), proxy_handler)
    api = ThreadingHTTPServer((config.bind, config.api_port), api_handler)
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    print('WiFi Guardian approved-device proxy')
    print(f'Proxy: {config.bind}:{proxy.server_address[1]} (client IP {config.client_ip} only)')
    print(f'Traffic feed: http://{config.bind}:{api.server_address[1]}/traffic')
    print('Counts HTTPS tunnel bytes only; no encrypted content or other Wi-Fi devices.')
    try: api.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        api.shutdown() if False else None
        proxy.shutdown(); api.server_close(); proxy.server_close()

def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', default='127.0.0.1', help='Mac LAN IPv4; default loopback')
    parser.add_argument('--client-ip', required=True, help='Explicitly authorized test device IPv4')
    parser.add_argument('--device-id', default='authorized-phone')
    parser.add_argument('--device-name', default='Test Phone')
    parser.add_argument('--device-type', choices=['phone','computer','tv','other'], default='phone')
    parser.add_argument('--proxy-port', type=int, default=8888)
    parser.add_argument('--api-port', type=int, default=8088)
    parser.add_argument('--token', required=True, help='Random token (keep private), used in Android live feed')
    parser.add_argument('--ports', default='443,8443', help='Allowed HTTPS target TCP ports')
    parser.add_argument('--allow-private-targets', action='store_true', help='Only for private test servers; never use on public Wi-Fi')
    options = parser.parse_args()
    ipaddress.ip_address(options.client_ip)
    ipaddress.ip_address(options.bind)
    if len(options.token) < 16: parser.error('--token must be at least 16 characters')
    try: options.ports = {int(p.strip()) for p in options.ports.split(',')}
    except ValueError: parser.error('Invalid ports list')
    if any(not 1 <= p <= 65535 for p in options.ports): parser.error('Invalid port')
    return options

if __name__ == '__main__':
    start(parse_args())
