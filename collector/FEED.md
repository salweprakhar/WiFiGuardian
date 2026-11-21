# Traffic feed v2

`GET /traffic`, authenticated with `Authorization: Bearer <token>`. JSON responses
use `Cache-Control: no-store`. Missing or invalid tokens return 401. Unknown paths
return 404. A router source with no successful current sample returns 503 with an
`error` string; consumers must not display old counters as live after this error.

The existing `devices` array and its fields remain compatible with v1. New fields:

- `schema_version`: 2
- `session_id`: opaque collector-process ID. Reset rate baselines when this changes.
- `observed_at_ms`: source sample time as Unix milliseconds. Duplicate timestamps
  are the same sample, not a new rate measurement.
- `source_mode`: `proxy` or `router`
- `coverage`: human-readable description of what is counted
- `warnings`: array of coverage/accounting limitations
- Each device adds `active_connections`, `connections_total`, and `connections`.
  At most 12 connection rows per device are returned: open/recent first for the
  proxy, largest current flows first for the router.
  Totals include rows omitted from the display. `connections_total` is the number
  of rows available before that display limit, not an all-time session count.
- Connection fields: `host` (requested CONNECT hostname or destination IP), `port`
  (0 for protocols without ports), `protocol` (transport), `service` (port-based
  hint, not application identification), `upload_bytes`, `download_bytes`,
  `active` (open proxy tunnel or present in router table), `last_seen_ms`.

Device byte counts are cumulative within a collector session. Proxy counts are
relayed payload bytes. Router counts accumulate observed conntrack bytes, including
existing flow counters at first observation. Router sampling can miss short flows
and final bytes; router offloading can also undercount. A conntrack entry can remain
after traffic stops: “tracked” does not imply the device is transmitting now.
Router totals are not billing-grade usage or a complete device inventory.

No packet payload, URL path, TLS content, or inferred app identity is returned.
Names in proxy mode are CONNECT destinations, not a verified browsing history.
Router mode reports IPs, not DNS names. IPv4/IPv6 addresses are separate identities.
Collectors retain data in memory only. LAN HTTP is unencrypted; use a trusted LAN.
