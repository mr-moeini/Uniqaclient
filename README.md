# Uniqa Client

Android app (iOS-style UI) with two forwarders. Every push to `main` builds a debug APK with GitHub Actions
(Actions -> latest run -> Artifacts -> `Uniqaclient-debug-apk`).

## SMS Forwarder
Each rule has a **sender** (phone number or sender ID such as a bank name; pick from contacts or type), an optional **keyword** and an optional **regex**, and one **action**:

1. **Forward SMS to a number** - re-sends the text as `[sender] text`.
2. **Send to an API server** - HTTP request with the SMS text. The text always starts with:
   ```
   Devicename : <device name>
   Udid: <udid>
   <sms text>
   ```
   - Methods: POST, PUT, PATCH, GET
   - Body: plain text, JSON, form, or a custom template (`{{device_name}} {{udid}} {{sender}} {{message}} {{text}} {{timestamp}}`, add `_json` to JSON-escape)
   - Authentication: none, Basic, Bearer token, API key (header or query), OAuth 2.0 client credentials
   - Extra headers, timeout, optional "ignore SSL errors", "Send Test Request" button
   - Failed requests are retried automatically (network required, exponential backoff).

**Regex**: if set, only the first match is forwarded - group 1 if the pattern has a capture group, otherwise the whole match. If it does not match, the message is not forwarded.

## Measurement Forwarder
Rules that collect device measurements on a schedule and forward them (SMS to a number, or the same API options as above, with the `Devicename` / `Udid` lines first):

- `cpu`, `ram`, `disk` (space), `ip`, `internet` (connection status), `ping <host>` (one or more hosts), `ussd <code>` (the USSD code is run on the device; an optional regex picks part of the reply)
- Output: one `name: value` line per measurement, e.g. `cpu: 12.5%`
- Schedule: send every N minutes (minimum 1), optionally only between two dates (Jalali calendar when the phone language is Persian)
- Content regex, "Preview Measurement" and "Send Now" buttons
- Android may delay timers while the phone sleeps (Doze); exclude the app from battery optimization for reliable short intervals.
- Whole-device CPU is blocked by newer Android versions; the load average is sent instead.

## Device Identity / Backup / About
Device name and UDID sent to the API (default UDID = Android ID; type the adb serial if it must equal Appium's `udid`). Backup/Restore writes one JSON file with all rules (it contains API credentials - keep it safe).

Developed by : MR Moeini - https://www.linkedin.com/in/mohammad-reza-moeini-83088987
