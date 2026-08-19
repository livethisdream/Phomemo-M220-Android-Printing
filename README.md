# Homebox Labeler

An Android share target that prints Homebox item QR labels to a Phomemo M220.

It is **not** a Homebox client. Homebox stays in Chrome. This app does one
thing: accept a shared URL, render a label, push it to the printer.

## Install

Every push builds an APK and replaces a rolling `dev` release, so this link
always serves the newest build:

**https://github.com/livethisdream/Phomemo-M220-Android-Printing/releases/download/dev/homebox-labeler.apk**

Open it on the phone and tap through the installer. No GitHub account, no zip
to unpack. Android will ask once for permission to install from whatever app
you opened the link in.

For updates without checking manually, add the **repository** URL - not the
APK link - to [Obtainium](https://github.com/ImranR98/Obtainium):

```
https://github.com/livethisdream/Phomemo-M220-Android-Printing
```

Then turn on **Include prereleases** in that app's settings, since the rolling
build is published as one.

Obtainium decides whether a build is new by comparing versions, and the release
tag never changes here, so the version has to come from the APK itself: CI
stamps each build as `0.1.<run number>`. If Obtainium still reports no update,
switch its version detection for this app to use the APK version or the release
date rather than the tag.

The build is debug-signed, which is what makes it installable at all without a
release keystore. It also means Android treats it as a different app from any
release-signed build, so switching between them needs an uninstall first.

## Flow

1. Open any Homebox item page in Chrome on your phone.
2. Share → **Print Label**.
3. Preview appears, tap Print.

Chrome hands over the page URL as `EXTRA_TEXT` and the page title as
`EXTRA_SUBJECT`, so the item name lands on the label without any API call.
The asset ID is pulled out of the URL path (`/a/000-001`).

## Why this shape

| Decision | Reason |
| --- | --- |
| Native app, not a PWA | Web Bluetooth shows a device picker on **every** print and requires HTTPS. A native app picks the printer once and remembers the address. |
| BLE GATT | Not by choice. The M220 advertises BLE only to Android, so a Classic bond never sticks — see below. |
| Render on-device | Removes the separate label service entirely. The app declares no `INTERNET` permission at all. |
| No dithering | Dithering is for photographs. On a QR code it destroys module edges. Everything drawn is already pure black/white, so a hard threshold is sharper. |

## Transport: BLE, not Classic SPP

This started out as Bluetooth Classic (RFCOMM/SPP), reasoning that
`vivier/phomemo-tools` drives the M220 over rfcomm on Linux. That does not
carry over to Android. The symptom is that the printer **will not stay paired**
in Settings → Bluetooth: a Classic bond against a device exposing no Classic
services has nothing to hold onto.

The diagnostic is that a Web Bluetooth page prints to this unit successfully.
Web Bluetooth cannot speak Classic SPP — the spec only exposes GATT — so
anything a browser can drive is reachable over BLE by definition.

A happy consequence: **GATT needs no bond.** There is no pairing step to lose.
The app scans, you pick the printer once, and it remembers the address.

The command bytes did not change. `PhomemoM220` and `LabelRenderer` never knew
which transport was carrying them.

## Permissions

On Android 12+ this is `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`, still with **no
location permission** — `neverForLocation` on the scan declaration is what
buys that, by promising scan results are not used to infer position.

Below Android 12 there is no such escape hatch: a BLE scan returns zero results
without `ACCESS_FINE_LOCATION`, however unrelated to location the intent is. It
is declared `maxSdkVersion="30"` so Android 12+ never sees it.

Note this is weaker than the original Classic design, which needed no scan at
all. Scanning is not optional once there is no bond to read.

## Setup

1. `./gradlew assembleDebug`, install the APK.
2. Power on the M220. **Do not pair it in Settings → Bluetooth** — it will not
   stay paired, and the app does not look at bonded devices.
3. Open the app, tap **Find printer**, pick yours from the list. Set your label
   stock size while you are there.

## Label geometry

Sizes are in millimetres and convert at exactly **8 dots/mm** (203.2 dpi).

- The M220 print head is 72 mm / 576 dots. That is the *maximum*, not your
  label width.
- Common stock is 50×30 mm (default here) or 40×30 mm.
- Raster width is rounded down to a whole byte, so widths land on multiples of
  1 mm cleanly.

## Tuning

If QR codes scan unreliably, in this order:

1. Raise **density** (1–15, default 8).
2. Lower **speed** (`Prefs.speed`, 1–5) — slower gives crisper module edges.
3. Shorten the encoded URL. A hostname like `https://homebox.internal.example.com/a/000-001`
   forces a denser QR than `http://hb.lan/a/000-001`. A short internal
   hostname redirecting to the real instance is the cheapest win available.

Error correction is set to **M** deliberately. `L` is fragile on thermal
stock; `H` inflates the module count until each module is too small to resolve
at 8 dots/mm.

## Media type

Defaults to `LABEL_WITH_GAPS` (`0x0a`), correct for die-cut rolls. Switch
`Prefs.mediaType` to `MEDIA_CONTINUOUS` (`0x0b`) for continuous stock or
`MEDIA_LABEL_WITH_MARKS` (`0x26`) for black-mark stock.

## Things I could not verify

These need a real M220 in hand:

- ~~**SPP availability.**~~ Resolved: it is BLE only. See the transport section.
- **Which characteristic takes the job.** `BleTransport` discovers this at
  runtime rather than hardcoding a UUID, trying known ones first and then any
  writable characteristic. This is deliberate — these printers are split across
  at least two vendor service families, and guessing wrong produces a connect
  that succeeds and then silently prints nothing. If yours picks the wrong one,
  that is where to look.
- **Chunk size and pacing.** Now `MTU - 3` after negotiating up from the 23-byte
  default, with 8 ms between unacknowledged writes. Derived rather than
  measured, so a long label is the thing to test.
- **Footer behaviour.** The two footer commands should feed to the next gap.
  Whether that lands correctly on your stock is worth checking on a scrap roll
  before you print fifty.
- **Advertised name.** Deliberately *not* used to filter. The name a Phomemo
  advertises frequently differs from the one on its own screen — an M110S shows
  up as `Q199E…` — so the picker lists everything found and merely sorts likely
  printers first.

## Licensing

The command sequences in `PhomemoM220.kt` come from
[`vivier/phomemo-tools`](https://github.com/vivier/phomemo-tools), which is
GPL-3.0. If you publish this app, check whether that lineage obliges you to
release under GPL-3.0 as well. For personal use on your own phone it does not
matter.
