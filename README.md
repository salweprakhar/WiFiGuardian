# WiFi Guardian v0.6 — opt-in traffic lab

This contains the WiFi Guardian Android app (in `android/`) and a tested, opt-in Python HTTP CONNECT proxy/traffic feed (in `collector/`).

## What it does and doesn't do
- The Android **Scan nearby network devices** action is best-effort local discovery: it checks TCP responses, listens for compatible UPnP/SSDP responses, and includes resolved IPv4 neighbors from Android's ARP cache. It supports local IPv4 subnets with up to 1,022 host addresses.
- The scan shows this phone's manufacturer/model and attempts reverse-DNS hostnames for discovered devices; it also displays a device's MAC address when Android exposes it in the neighbor cache. Other devices do not reliably publish their model/name, so their hostname or MAC may be unavailable or randomized.
- The main screen prioritizes Wi-Fi discovery. Optional live traffic, router CSV imports, history, and export are grouped under expandable sections.
- There is no router-independent Android API for enumerating every associated Wi-Fi client. Device isolation, firewalls, stale neighbor entries, or router behavior can hide devices or make results incomplete; a complete list requires a supported router integration.
- Live traffic counts HTTPS CONNECT tunnel bytes only, from devices you explicitly configure to use the proxy. The collector can report multiple opted-in devices, each identified by an IP and label.
- WiFi Guardian fetches the collector's `/traffic` feed and displays upload/download totals and rates. It does not derive traffic counts from its device scan; devices that do not use the proxy need an authorized router traffic-counter integration.
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
Run `cd collector && python3 test_collector.py` to repeat Python integration tests. Those tests start local echo and proxy servers, tunnel bytes, assert real counters and ID fields, reject unapproved ports, and require authentication for `/traffic`.

**Not tested:** Real Android APK compilation, Android 11 execution, Wi-Fi client discovery on the Redmi, macOS firewall behavior, or end-to-end traffic on a physical phone. The collector is a narrow prototype, not a hardened production proxy; it restricts proxy access to explicitly listed source IPs, but source-IP checks are not strong authentication and are unsuitable for untrusted networks.

## GitHub Actions
Copy the **contents** of `android/` to the root of your WiFiGuardian Git repository, including `.github/workflows/build-apk.yml`. The Actions build produces a debug APK artifact if the Android Gradle build succeeds. The collector must be run separately on your Mac; it is not part of the APK.

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
