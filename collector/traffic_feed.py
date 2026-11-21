"""Shared, authenticated traffic-feed transport; no third-party dependencies."""
import json
import secrets
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlsplit


def service_hint(protocol, port):
    return {
        ('tcp', 443): 'HTTPS / encrypted TLS (port hint)',
        ('tcp', 8443): 'Encrypted TLS (port hint)',
        ('udp', 443): 'QUIC / HTTP/3 (port hint)',
        ('tcp', 80): 'HTTP (port hint)',
        ('tcp', 53): 'DNS (port hint)',
        ('udp', 53): 'DNS (port hint)',
        ('tcp', 853): 'Encrypted DNS (port hint)',
        ('udp', 123): 'Time sync (port hint)',
    }.get((protocol.lower(), port), 'Unclassified')


def api_handler(token, snapshot):
    class ApiHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            if urlsplit(self.path).path != '/traffic':
                self.send_error(404)
                return
            actual = self.headers.get('Authorization', '').encode('utf-8')
            if not secrets.compare_digest(actual, ('Bearer ' + token).encode('utf-8')):
                self.send_error(401)
                return
            try:
                body, status = snapshot(), 200
            except RuntimeError as exc:
                body, status = {'error': str(exc)}, 503
            blob = json.dumps(body).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(blob)))
            self.end_headers()
            self.wfile.write(blob)

        def log_message(self, fmt, *args):
            pass

    return ApiHandler
