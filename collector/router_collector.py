#!/usr/bin/env python3
"""Read a Linux/OpenWrt gateway's conntrack table and serve WiFi Guardian's feed.

Read-only sampling via an existing SSH key/known host, or a local conntrack file.
Does not change router settings, redirect traffic, or decrypt packets.
"""
import argparse
import ipaddress
import re
import secrets
import subprocess
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path

from traffic_feed import api_handler, service_hint

MAX_SOURCE_BYTES = 32 * 1024 * 1024
COVERAGE = 'Sampled connections crossing the configured Linux/OpenWrt gateway'
WARNINGS = [
    'Sampling can miss short connections and final bytes. Flow offloading can undercount.',
    'Tracked entries may be idle. Same-LAN traffic and devices using another gateway are not visible.',
    'IP addresses and port hints do not identify apps, domains, or encrypted content.',
]


def parse_conntrack(text, networks, excluded):
    """Return normalized internet flows, including reply tuples for DNAT/IPv6.

    Only LAN-to-outside and outside-to-LAN flows qualify. Counter direction is
    always from the LAN client's perspective. Entire snapshots validate before
    any cumulative counters are changed.
    """
    flows = {}
    missing = 0
    def in_lan(ip):
        return any(ip.version == network.version and ip in network for network in networks)

    for line in text.splitlines():
        if not line.strip():
            continue
        fields = line.split()
        offset = 2 if fields[0] in {'ipv4', 'ipv6'} else 0
        try:
            protocol = fields[offset].lower()
            int(fields[offset + 1])  # protocol number
            int(fields[offset + 2])  # expiry
            pairs = re.findall(r'\b(src|dst|sport|dport|bytes|id|zone)=([^\s]+)', line)
            values = {}
            for key, value in pairs:
                values.setdefault(key, []).append(value)
            sources = [ipaddress.ip_address(v) for v in values['src']]
            destinations = [ipaddress.ip_address(v) for v in values['dst']]
            if len(sources) != 2 or len(destinations) != 2:
                raise ValueError('Expected original and reply tuples')
            # Original src is pre-SNAT; reply src is the destination after DNAT.
            outbound = in_lan(sources[0])
            inbound = in_lan(sources[1])
            if outbound == inbound:
                continue  # Neither side is a client, or both sides are local.
            client = sources[0] if outbound else sources[1]
            remote = destinations[0] if outbound else sources[0]
            if client in excluded or not remote.is_global:
                continue
            ports = values.get('dport' if outbound else 'sport', ['0', '0'])
            port = int(ports[0])
            if not 0 <= port <= 65535:
                raise ValueError('Invalid port')
            byte_values = values.get('bytes', [])
            if len(byte_values) != 2:
                missing += 1
                continue
            original, reply = map(int, byte_values)
            if min(original, reply) < 0:
                raise ValueError('Negative byte counter')
            # Include both tuples, ICMP identifiers and conntrack zone/ID when present.
            key = (protocol, tuple((k, v) for k, v in pairs if k != 'bytes'))
            flows[key] = {
                'ip': str(client), 'host': str(remote), 'port': port,
                'protocol': protocol.upper(), 'service': service_hint(protocol, port),
                'upload_bytes': original if outbound else reply,
                'download_bytes': reply if outbound else original,
            }
        except (ValueError, KeyError, IndexError) as exc:
            raise RuntimeError('Invalid conntrack snapshot; check the router reader and output format') from exc
    if missing and not flows:
        raise RuntimeError('Router connections have no byte counters. Enable nf_conntrack_acct on the router, then create new connections.')
    return flows, missing


class RouterMonitor:
    def __init__(self, networks, excluded=(), interval=3):
        self.networks = [ipaddress.ip_network(n, strict=False) for n in networks]
        self.excluded = {ipaddress.ip_address(ip) for ip in excluded}
        self.interval = interval
        self.lock = threading.Lock()
        self.previous = {}
        self.totals = {}
        self.session_id = secrets.token_hex(16)
        self.payload = None
        self.last_success = 0
        self.error = 'Waiting for the first router sample'

    def update(self, text):
        flows, missing = parse_conntrack(text, self.networks, self.excluded)
        now = int(time.time() * 1000)
        if len(set(self.totals) | {f['ip'] for f in flows.values()}) > 512:
            raise RuntimeError('More than 512 device addresses; narrow --lan to the monitored subnet')
        rows = {}
        for key, flow in flows.items():
            ip = flow['ip']
            totals = self.totals.setdefault(ip, {'upload_bytes': 0, 'download_bytes': 0})
            previous = self.previous.get(key, {})
            reset = any(flow[c] < previous.get(c, 0) for c in ('upload_bytes', 'download_bytes'))
            for counter in ('upload_bytes', 'download_bytes'):
                old = previous.get(counter, 0)
                value = flow[counter]
                totals[counter] += value if reset else value - old
            rows.setdefault(ip, []).append(dict(flow, active=True, last_seen_ms=now))
        self.previous = flows
        devices = []
        for ip, totals in self.totals.items():
            connections = sorted(rows.get(ip, []),
                                 key=lambda f: f['upload_bytes'] + f['download_bytes'], reverse=True)
            devices.append(dict(totals, id=ip, ip=ip, name=ip, type='other',
                source='Router conntrack (sampled)', active_connections=len(connections),
                connections_total=len(connections), connections=connections[:12]))
        warnings = list(WARNINGS)
        if missing:
            warnings.append(f'{missing} connections have no byte counters and are excluded. Enable router accounting for new connections.')
        with self.lock:
            self.payload = {
                'schema_version': 2, 'session_id': self.session_id, 'source_mode': 'router',
                'observed_at_ms': now, 'coverage': COVERAGE, 'warnings': warnings,
                'devices': devices,
            }
            self.last_success = time.monotonic()
            self.error = ''

    def fail(self, error):
        with self.lock:
            self.error = str(error)

    def snapshot(self):
        with self.lock:
            if self.error:
                raise RuntimeError(self.error)
            if time.monotonic() - self.last_success > max(15, self.interval * 3):
                raise RuntimeError('Router sample is stale; check the router connection')
            return self.payload


def read_source(options):
    if options.conntrack_file:
        with Path(options.conntrack_file).open('rb') as source:
            data = source.read(MAX_SOURCE_BYTES + 1)
    else:
        command = ('cat /proc/net/nf_conntrack' if options.reader == 'proc' else
                   'conntrack -L -f ipv4 -o extended && conntrack -L -f ipv6 -o extended')
        args = ['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'ConnectTimeout=5', '-p', str(options.ssh_port)]
        if options.identity:
            args += ['-i', options.identity]
        args += [options.ssh, command]
        # A temporary file bounds memory even when the router returns excessive output.
        import tempfile
        with tempfile.TemporaryFile() as output:
            result = subprocess.run(args, stdout=output, stderr=subprocess.DEVNULL, timeout=12, check=False)
            if result.returncode:
                raise RuntimeError('Router SSH read failed. Check the key, verified host, permissions and --reader setting.')
            output.seek(0)
            data = output.read(MAX_SOURCE_BYTES + 1)
    if len(data) > MAX_SOURCE_BYTES:
        raise RuntimeError('Router snapshot exceeds 32 MiB')
    return data.decode('utf-8')


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--ssh', metavar='USER@ROUTER', help='Existing key-based SSH access to an authorized gateway')
    source.add_argument('--conntrack-file', help='Local /proc/net/nf_conntrack, or a saved snapshot for testing')
    parser.add_argument('--reader', choices=['proc', 'conntrack'], default='proc')
    parser.add_argument('--ssh-port', type=int, default=22)
    parser.add_argument('--identity', help='SSH private-key path (key is never sent to Android)')
    parser.add_argument('--lan', action='append', required=True, help='Client IPv4/IPv6 CIDR; repeat for more subnets')
    parser.add_argument('--exclude', action='append', default=[], help='Exclude router/infrastructure IP; repeat as needed')
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--api-port', type=int, default=8088)
    parser.add_argument('--interval', type=float, default=3)
    parser.add_argument('--token', required=True)
    options = parser.parse_args(argv)
    try:
        ipaddress.IPv4Address(options.bind)
        for subnet in options.lan:
            network = ipaddress.ip_network(subnet, strict=False)
            if network.prefixlen == 0:
                raise ValueError('Use actual client subnets, not a default route')
        for address in options.exclude:
            ipaddress.ip_address(address)
    except ValueError as exc:
        parser.error(str(exc))
    if options.ssh and not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.:-]*', options.ssh):
        parser.error('--ssh must be USER@HOST (no spaces or shell syntax)')
    if not 1 <= options.interval <= 60:
        parser.error('--interval must be between 1 and 60 seconds')
    if not all(1 <= p <= 65535 for p in (options.api_port, options.ssh_port)):
        parser.error('Invalid port')
    if len(options.token) < 16:
        parser.error('--token must be at least 16 characters')
    return options


def start(options):
    monitor = RouterMonitor(options.lan, options.exclude, options.interval)
    stop = threading.Event()
    def poll():
        while not stop.is_set():
            try:
                monitor.update(read_source(options))
            except (OSError, RuntimeError, UnicodeError, subprocess.TimeoutExpired) as exc:
                monitor.fail(exc)
            stop.wait(options.interval)
    api = ThreadingHTTPServer((options.bind, options.api_port), api_handler(options.token, monitor.snapshot))
    worker = threading.Thread(target=poll, daemon=True)
    worker.start()
    print(f'Router traffic feed: http://{options.bind}:{options.api_port}/traffic')
    print('Read-only sampled gateway traffic. No traffic redirection or decryption.')
    try:
        api.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        api.server_close()
        worker.join(timeout=13)


if __name__ == '__main__':
    start(parse_args())
