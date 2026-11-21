# WiFi Guardian v0.8 — device traffic monitoring and Termux tools

## Termux network tools

WiFi Guardian can open the separate Termux terminal and launch two fixed checks
against the current Wi-Fi gateway: a four-packet ping and a TCP check of 20 common
ports with Nmap. Results appear in Termux. WiFi Guardian does not install Termux,
download packages, accept arbitrary shell commands, or read the terminal output.

Install Termux from its [official F-Droid or GitHub release](https://github.com/termux/termux-app#installation),
open it once, then run `pkg update && pkg install nmap`. In Termux set
`allow-external-apps=true` in `~/.termux/termux.properties` and restart it.
In Android Settings, grant WiFi Guardian the additional permission **Run commands
in Termux environment**. Open **Termux network tools** in WiFi Guardian for the
setup instructions and diagnostic buttons. The integration uses Termux's documented
[RUN_COMMAND intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
Only run checks on networks you administer or are authorized to test. Installing
Termux does not reveal other devices' traffic on arbitrary Wi-Fi networks.

This contains the WiFi Guardian Android app (`android/`), an opt-in Python HTTPS
CONNECT proxy and a Linux/OpenWrt router collector (`collector/`). Both collectors
use Python's standard library and expose the same authenticated traffic feed.

**Connecting a phone to any Wi-Fi does not grant access to other devices' traffic.**
Discovery is best-effort on ordinary networks. Traffic monitoring requires either
admin access to a compatible gateway, or proxy setup on each participating device.
There is no universal router-independent mode for monitoring every Wi-Fi client.

The app shows per-device upload/download totals and rates, connection destinations,
ports, transport protocols and coverage notes. Port labels are hints, not reliable
app identification. Encrypted messages, page contents and URL paths are not exposed.

## Router mode: monitor devices using a compatible gateway

This mode requires a Linux/OpenWrt **gateway that routes the devices' internet
traffic**, readable conntrack entries with byte accounting, and existing SSH key
access. A stock ISP router may not provide these facilities. A standalone access
point or an ordinary laptop on the Wi-Fi cannot supply the same visibility.

1. On your authorized router, check `sysctl net.netfilter.nf_conntrack_acct`.
   If disabled, enable it with `sysctl -w net.netfilter.nf_conntrack_acct=1`.
   This is a runtime setting; persistence depends on the router. Existing flows
   can lack counters until new connections are opened. The collector does not
   change router settings itself.
2. Verify key-based SSH access and the router's host key using your SSH client.
   The collector requires an already trusted host and does not accept unknown
   keys or prompt for passwords. The SSH account must be allowed to read the
   connection table.
3. On a computer connected to your trusted LAN, generate a token with
   `python -c "import secrets; print(secrets.token_urlsafe(24))"`. Replace the
   placeholders below with your computer IP, router SSH address, LAN subnet,
   router LAN IP and token (one command, usable on Windows/macOS/Linux):

   ```text
   python collector/router_collector.py --ssh root@192.168.1.1 --lan 192.168.1.0/24 --exclude 192.168.1.1 --bind 192.168.1.10 --token YOUR_RANDOM_TOKEN
   ```

   Add `--identity PATH_TO_KEY` if needed. Repeat `--lan` for other routed client
   subnets, including IPv6, and `--exclude` for infrastructure addresses. Excluding
   the router prevents its own WAN connections appearing as a client. IPv4 and
   IPv6 addresses are reported separately; names/MACs are not inferred.
4. If `/proc/net/nf_conntrack` is unavailable, install the router's `conntrack`
   utility and use `--reader conntrack`. This reads both IPv4 and IPv6 tables.
   For a collector running directly on a suitable Linux gateway, use
   `--conntrack-file /proc/net/nf_conntrack` instead of `--ssh`.
5. In the Android app, expand **Live traffic**, choose **Set up traffic source**,
   enter `http://192.168.1.10:8088/traffic` and the token, then start monitoring.
   Allow port 8088 on the computer's trusted-LAN firewall if necessary.

The collector polls about every three seconds and retains observed totals in
memory until restart, including existing flow counters at first observation.
It can miss short-lived connections and final bytes. Flow/hardware offloading can
also bypass accounting; review your router's offload settings before relying on
the numbers. This is **sampled visibility**, not billing-grade total usage. It
does not see traffic switched directly between LAN devices, traffic through other
gateways, or a VPN's inner destinations. A tracked connection can be idle. It
reports remote IPs, not DNS hostnames. Only connections to globally routable
addresses are included. Source failures produce an error, not a live display of
stale counters.

Reference: [Linux flow accounting](https://docs.kernel.org/networking/nf_conntrack-sysctl.html)
and [conntrack read commands](https://www.netfilter.org/projects/conntrack-tools/conntrack-manpage.html).
The feed contract is in [collector/FEED.md](collector/FEED.md).

## Proxy mode: configure participating devices

## What it does and doesn't do
- The Android **Scan nearby network devices** action is best-effort local discovery: it checks TCP responses, listens for compatible UPnP/SSDP responses, and includes resolved IPv4 neighbors from Android's ARP cache. It supports local IPv4 subnets with up to 1,022 host addresses.
- The scan shows this phone's manufacturer/model and attempts reverse-DNS hostnames for discovered devices; it also displays a device's MAC address when Android exposes it in the neighbor cache. Other devices do not reliably publish their model/name, so their hostname or MAC may be unavailable or randomized.
- The main screen prioritizes Wi-Fi discovery. Optional live traffic, router CSV imports, history, and export are grouped under expandable sections.
- There is no router-independent Android API for enumerating every associated Wi-Fi client. Device isolation, firewalls, stale neighbor entries, or router behavior can hide devices or make results incomplete; a complete list requires a supported router integration.
- Proxy mode counts HTTPS CONNECT tunnel bytes only, from devices you explicitly configure to use the proxy. It reports requested destination hostnames/IPs, ports and connection counts for each configured IP and label.
- WiFi Guardian fetches the collector's `/traffic` feed and displays upload/download totals, rates and connection details. It does not derive traffic counts from its device scan; devices that do not use the proxy need the compatible router collector described above or another router integration.
- Works without router admin on your private lab Wi-Fi. **Does not observe non-participating devices, read encrypted messages, list apps, or force another device's traffic through the proxy.** Apps that ignore system proxy settings won't be measured.
- Not a universal MITM attack and not a public proxy. Use only on a network and devices you are authorized to test.

## 1 — Mac: start collector
Install Python 3, find the Mac's Wi-Fi IP (example `192.168.1.10`) and the current Wi-Fi IP for each test device. A DHCP reservation helps keep device addresses stable. Generate a random token and start the collector:

```bash
cd collector
TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
printf 'Enter this token in WiFi Guardian: %s\n' "$TOKEN"
python3 collector.py \
 --bind 192.168.1.10 \
 --client 192.168.1.106,my-test-phone,'My Test Phone',phone \
 --client 192.168.1.107,my-laptop,'My Laptop',computer \
 --token "$TOKEN"
```

Add one `--client IP,ID,NAME,TYPE` entry for each device that has explicitly opted in. Repeat the flag as needed; supported types are `phone`, `computer`, `tv`, and `other`. Do not use commas in a device name. The older single-device `--client-ip` options remain supported. No third-party Python packages are needed. Ports 8888 (proxy) and 8088 (feed) must be reachable on your trusted Wi-Fi. Do not forward them to the internet; do not use public Wi-Fi. The feed uses an auth token, but HTTP on the LAN is not encrypted.

## 2 — Each test device: explicitly opt into the proxy
On each phone or computer that **you own or have explicit authorization to monitor**, connect to the same trusted Wi-Fi. Open that device's Wi-Fi network settings, choose **Manual** proxy, and set:
- Proxy server: Mac Wi-Fi IP (example `192.168.1.10`)
- Proxy port: `8888`

Browse an HTTPS website on the device. Its current IP must match the corresponding `--client` entry. Some apps bypass Wi-Fi proxy settings and will not be counted. This collector counts only HTTPS CONNECT tunnel bytes, not all network traffic. Unset the proxy after testing. **Do not install TLS interception certificates** or enter sensitive information while testing.

## 3 — Redmi: view the counters
Install the APK built from `android/` using GitHub Actions. In WiFi Guardian, expand **Live traffic**:
1. Tap **Set up traffic source**.
2. Enter `http://192.168.1.10:8088/traffic`, replacing the example with your Mac's Wi-Fi IP.
3. Enter the exact token printed in step 1. The token stays in app memory only, so enter it again after restarting the app.
4. Save, then tap **Start monitoring**.
5. To see one device only, tap **Choose a device** and enter its ID (for example `my-test-phone`) or IP.

The first poll shows cumulative bytes; later polls calculate bytes per second. Download/upload are from each test device's perspective. If the feed says HTTP 401, check the token. If it times out or refuses the connection, check that the collector is running, both devices use the same Wi-Fi, the endpoint IP is correct, the Mac firewall permits ports 8088 and 8888, and the test device's IP matches its `--client` entry.

**Important:** this proxy method requires each device to opt in and counts only traffic that honors the proxy. To measure all traffic from clients without configuring their proxy, WiFi Guardian needs a supported router traffic-counter/API integration. A generic Android app cannot passively read every Wi-Fi client's traffic.

**If the Redmi itself is a device you want to monitor:** configure its Wi-Fi proxy to use the Mac collector and include the Redmi's current IP as a `--client`. The app's feed connection goes directly to the collector; browser/app traffic is counted only if that app honors the Wi-Fi proxy.

## Tests run for this package
Run `python -B -m unittest discover -s collector -v` from the repository root.
The 17 tests cover real local proxy tunnels and authenticated HTTP feeds, destination
details, bounded history, router IPv4/IPv6 and NAT direction, repeated samples,
counter resets, missing accounting, stale data and recovery. Router inputs are
fixtures; these tests do not establish compatibility with your physical router.

**Not tested:** Real Android APK compilation, Android 11 execution, Wi-Fi client discovery on the Redmi, macOS firewall behavior, or end-to-end traffic on a physical phone. The collector is a narrow prototype, not a hardened production proxy; it restricts proxy access to explicitly listed source IPs, but source-IP checks are not strong authentication and are unsuitable for untrusted networks.

## GitHub Actions
Keep this repository's layout: the workflow at `.github/workflows/build-apk.yml`
builds the project in `android/` and runs collector tests. The collector runs
separately on a computer or compatible gateway; it is not part of the APK.

### Installing or updating the APK
The Redmi Note 8 Pro supports this app's minimum Android version (API 26). If Android reports **App not installed** while updating an existing copy, the most likely issue is a signing-certificate mismatch: GitHub-hosted runners generate a different default debug key for each build. Uninstall the existing WiFi Guardian copy, then install the newly downloaded APK. Uninstalling removes that app's local data.

To install updates without uninstalling each time, configure a stable signing key for GitHub Actions:
1. Generate a keystore that you will keep and reuse (do not commit it):
   ```bash
   keytool -genkeypair -v -keystore wifiguardian-signing.jks \
     -alias wifiguardian -keyalg RSA -keysize 2048 -validity 10000
   ```
2. In the repository's **Settings → Secrets and variables → Actions**, add secrets named `WIFIGUARDIAN_KEYSTORE_BASE64`, `WIFIGUARDIAN_STORE_PASSWORD`, `WIFIGUARDIAN_KEY_ALIAS`, and `WIFIGUARDIAN_KEY_PASSWORD`. Set the first to the single-line Base64 encoding of the keystore file (on macOS, `base64 < wifiguardian-signing.jks | tr -d '\n'`).
3. Rebuild and download the new APK artifact. Uninstall any copy signed with the previous runner-specific debug key once; subsequent APKs signed with this same keystore can update it.

If a fresh install still fails, verify that the downloaded artifact is `app-debug.apk` and allow the app used to open it (for example, Files or the browser) to install unknown apps in Android settings. A successful CI build confirms compilation, not that Android accepted the APK on-device.
