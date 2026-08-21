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

The build is debug-signed with the keystore committed at `app/debug.keystore`,
which is deliberate. Gradle invents a random debug key wherever it cannot find
`~/.android/debug.keystore`, and on a fresh CI runner that is every build - so
every APK was signed by a different key and Android refused to install one over
another, reporting only "App not installed". A fixed key makes builds upgrade
in place.

A debug key is not a secret. It grants nothing beyond signing an app that
claims this package name, and anyone can generate an equivalent. It must never
be used for a store release.

If you installed a build from before this change, uninstall once - the old key
cannot be upgraded from.

## Flow

1. Open any Homebox item page in Chrome on your phone.
2. Share → **Print Label**.
3. Preview appears, tap Print.

Chrome hands over the page URL as `EXTRA_TEXT` and the page title as
`EXTRA_SUBJECT`, so the item name lands on the label without any API call.
The asset ID is pulled out of the URL path (`/a/000-001`).

## Saved designs

The editor's save button keeps a layout by name; the bookmark icon on the home
screen lists them with a thumbnail of each. Opening one puts it back on the
canvas ready to print.

A design stores **the elements and the label size together**. Millimeter
coordinates only mean something against stock of a known size - the same numbers
that center a QR on 50x30 hang it off the edge of 30x20 - so the size travels
with the design and is restored when it loads.

Printer settings deliberately do *not* travel with it. Density, feed distance
and protocol describe the machine and the roll in it, not the layout, and
folding them in would let opening an old design silently undo printer tuning.

Saving while a design is open offers **Update** and **Save copy** rather than
guessing between them, because guessing is how a library fills up with
near-duplicates.

Storage is one JSON file per design under the app's private directory, plus a
rendered thumbnail. Pictures are written separately, named by the hash of their
own bytes, so the same image used in five designs is stored once; blobs no
surviving design references are swept after each save and delete.

Element ids are not written to disk. They come from a counter that restarts with
the process, so a persisted id would collide with a live one as soon as a design
was opened alongside anything else.

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

## Measuring the stock

Width is the dimension **across the roll**, and it is the one that matters. The
image is registered to the right-hand edge of the head, so a width larger than
the stock pushes the difference off the *left* of the label. The preview cannot
show this: the preview is the label, and the error is in where the label sits
under the head.

Stock width and *printable* width are not the same number. The image is
registered to the head's right-hand edge, so if the roll sits outboard of the
head, the image starts too far left and its left edge runs off the label - on
stock that is genuinely as wide as you measured.

Settings → Label size → **Print a measuring guide** measures the difference. It
prints a scale across the full head width, numbered inwards from the head's
right edge, with a wedge marking the head's last dot:

- The largest number you can read is the **widest label this printer can fill**.
  Set that as the width.
- **Blank paper to the right of the wedge** means the roll is outboard of the
  head. Reseating it further left recovers that much width.
- **The bar cut off on the left** just means the stock is narrower than the
  head. Nothing is wrong.

**Edge test** prints the label's own outline at the current size. Every edge
that lands on the label is a dimension that is right, and every missing edge
names its own problem: a missing left or right edge is the width, a missing top
or bottom is the feed. It goes through the same padding and alignment as a real
print, so it cannot pass while printing fails.

## Registration and the feed trap

Blank-row feed cannot both reach the tear bar and stay registered.

The image is already a whole label tall, so the paper advances one label height
per print on its own. Anything added past the gap between labels overshoots, and
the next print starts that much further down - cumulatively, so the drift grows
with every label until content runs off the end. A feed distance large enough to
clear the tear bar is far larger than a die-cut gap, which makes those two goals
directly opposed.

Only **To gap** does both, because the printer measures the stock instead of
being told a number. If a print is clipped at the top or bottom while the left
and right edges are fine, this is the setting, not the label size.

The arithmetic worth knowing: padding can only move the image by
`head width - label width`. On a 72 mm head a 70 mm label has 2 mm of slack -
the image is already as far right as the head can put it - so content running
well off the *left* at that width cannot be an alignment problem. Something is
stopping the head from reaching the paper's right edge, and only measuring says
what.

A label entered wider than the head is cropped to it. An oversized raster line
does not print wide: it desynchronises the block and turns every following line
into garbage, which reads as a hardware fault rather than as a number typed into
a settings screen.

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
