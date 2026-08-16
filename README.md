# Homebox Labeler

An Android share target that prints Homebox item QR labels to a Phomemo M220.

It is **not** a Homebox client. Homebox stays in Chrome. This app does one
thing: accept a shared URL, render a label, push it to the printer.

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
| Native app, not a PWA | Web Bluetooth shows a device picker on every print and requires HTTPS. A bonded Classic device does neither. |
| Bluetooth Classic SPP, not BLE | `vivier/phomemo-tools` drives the M220 over rfcomm. A plain socket avoids GATT discovery, MTU chunking and flow control. |
| Render on-device | Removes the separate label service entirely. The app declares no `INTERNET` permission at all. |
| No dithering | Dithering is for photographs. On a QR code it destroys module edges. Everything drawn is already pure black/white, so a hard threshold is sharper. |

## Permissions

On Android 12+, connecting to an already-bonded device needs only
`BLUETOOTH_CONNECT` — **no location permission**, which was the whole point of
not using the vendor app. `BLUETOOTH_SCAN` is declared `neverForLocation` and
is only there for the optional printer-discovery path; delete it if you always
pair through system Settings.

## Setup

1. Power on the M220, pair it in **Settings → Bluetooth**. Do this before first
   launch — the app looks at bonded devices, it does not scan.
2. `./gradlew assembleDebug`, install the APK.
3. Open the app directly once to set your label stock size.

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

- **SPP availability.** The protocol is documented from USB captures and the
  driver connects over rfcomm on Linux, but I have not confirmed this specific
  unit advertises SPP to Android rather than BLE only. If `connect()` throws,
  that is the first thing to check — replace `SppTransport` with a GATT
  implementation and chunk writes to `MTU - 3`. Nothing else changes.
- **Chunk size and inter-write delay.** 512 bytes / 20 ms is a conservative
  starting guess, not a measured value.
- **Footer behaviour.** The two footer commands should feed to the next gap.
  Whether that lands correctly on your stock is worth checking on a scrap roll
  before you print fifty.
- **Device name prefixes.** `SppTransport.KNOWN_PREFIXES` is a guess at how the
  M220 advertises itself. Check the name in Bluetooth settings and adjust.

## Licensing

The command sequences in `PhomemoM220.kt` come from
[`vivier/phomemo-tools`](https://github.com/vivier/phomemo-tools), which is
GPL-3.0. If you publish this app, check whether that lineage obliges you to
release under GPL-3.0 as well. For personal use on your own phone it does not
matter.
