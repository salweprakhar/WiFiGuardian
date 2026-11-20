# WiFi Guardian v0.6 — opt-in traffic lab

This contains the existing WiFi Guardian Android app (in `android/`), with a **Select a device to monitor** filter, and a tested Python HTTP CONNECT tunnel counter collector (in `collector/`).

## What it does and doesn't do
- Measures actual byte counts in HTTPS CONNECT tunnels **from a device explicitly configured to use your proxy**. Identifies that device by your chosen label, ID and source IP.
- Existing WiFi Guardian app fetches `/traffic` and displays live upload/download speeds and totals. App updates about every 3 seconds. Set its filter to the device ID or IP.
- Works without router admin on your private lab Wi-Fi. **Does not observe non-participating devices, read encrypted messages, list apps, or force another device's traffic through the proxy.** Apps that ignore system proxy settings won't be measured.
- Not a universal MITM attack and not a public proxy. Use only on a network and devices you are authorized to test.

## 1 — Mac: start collector
With macOS Python 3 installed, find the Mac's Wi-Fi IP using System Settings → Wi-Fi → Details (example `192.168.1.10`), and your **test device's current Wi-Fi IP** (example `192.168.1.106`). The IP may change. Run:

```bash
cd collector
python3 -c 'import secrets; print(secrets.token_urlsafe(24))'
python3 collector.py \
 --bind 192.168.1.10 \
 --client-ip 192.168.1.106 \
 --device-id my-test-phone \
 --device-name 'My Test Phone' \
 --device-type phone \
 --token YOUR_RANDOM_TOKEN
```

Use the generated token, not the placeholder. No third-party Python packages needed. Ports 8888 (proxy) and 8088 (feed) must be reachable from your test devices on the same trusted Wi-Fi. Do not forward these ports to the internet. Keep the token private; HTTP on the LAN is not encrypted. Don't use public Wi-Fi.

## 2 — Test device: opt into proxy
On a phone or computer that **you own or have explicit authorization to monitor**, connect to the same trusted Wi-Fi. In that device's Wi-Fi proxy settings, choose **Manual** proxy and specify:
- Proxy server: Mac Wi-Fi IP (example `192.168.1.10`)
- Proxy port: `8888`

Browse an HTTPS website on that test device. Some apps will bypass an HTTP proxy; their traffic will not appear. Unset the proxy after testing. **Don't install TLS interception certificates**, and do not enter sensitive information while testing with a proxy.

## 3 — Redmi: view actual traffic
Install the APK built from `android/` using GitHub Actions. In WiFi Guardian:
1. Tap **Configure traffic data source**.
2. Endpoint: `http://192.168.1.10:8088/traffic` (replace with your Mac's LAN IP).
3. Bearer token: use the exact token passed to `collector.py`.
4. Tap **Start live monitoring**.
5. Tap **Select a device to monitor** and enter `my-test-phone` (or the test device IP).

The first poll shows cumulative values; the second and later polls produce bytes/second. Download/upload are from the test device's perspective.

**If the Redmi itself is your only Android phone:** You can use your Mac as the explicitly configured test client and another machine as the collector, or run collector and a locally configured test client on the Mac, but the APK still requires a reachable collector at a LAN IP. The same device cannot be both a transparent all-network monitor and an ordinary Wi-Fi client.

## Tests run for this package
Run `cd collector && python3 test_collector.py` to repeat Python integration tests. Those tests start local echo and proxy servers, tunnel bytes, assert real counters and ID fields, reject unapproved ports, and require authentication for `/traffic`.

**Not tested:** Real Android APK compilation, Android 11 execution, Wi-Fi client discovery on the Redmi, macOS firewall behavior, or end-to-end traffic on a physical phone. The collector is a narrow prototype, not a hardened production proxy; no authentication is needed to access the proxy *only* because it strictly allows one client IP on your trusted lab network. That source-IP control is not strong authentication and is unsuitable for untrusted networks.

## GitHub Actions
Copy the **contents** of `android/` to the root of your WiFiGuardian Git repository, including `.github/workflows/build-apk.yml`. The Actions build produces a debug APK artifact if the Android Gradle build succeeds. The collector must be run separately on your Mac; it is not part of the APK.
