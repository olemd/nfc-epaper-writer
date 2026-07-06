# IsoDep displays, 4‑colour support, and Google Pixel compatibility

This document describes the changes made to support newer WaveShare NFC
e‑paper displays (which the bundled WaveShare JAR cannot drive) and to make
flashing work reliably on Google Pixel phones — hardware that WaveShare's own
apps disclaim or don't support.

## TL;DR of what changed

- **Fixed a Pixel-only bug** that made the app reject the display outright before
  any flash was attempted.
- **Added an IsoDep (ISO 14443‑4) flashing path** for two newer 4‑colour
  (black / white / red / yellow) displays that speak an APDU‑based protocol
  instead of the older NfcA command set:
  - a **296×128** panel (tag UID `BMXR`), and
  - a **1.54″ 200×200** panel (a different controller/command set again).
- **Added Floyd–Steinberg dithering** to the 4‑colour palette, with a UI toggle.
- **Added a 1.54″ screen‑size option** that renders on a square 200×200 canvas.
- **Disabled the WYSIWYG/graphic editor** pending a bug fix.

Older NfcA displays continue to use the original bundled WaveShare JAR path,
unchanged.

## Background: three display generations

| Generation | Tech | How it's flashed |
|---|---|---|
| Original (UID `WSDZ10m`) | NfcA | Bundled WaveShare `NFC.jar` (unchanged) |
| 296×128 4‑colour (`BMXR`) | IsoDep | New `IsoDepFlasher` — `F0`/`00`-prefixed APDUs |
| 1.54″ 200×200 4‑colour | IsoDep | New `IsoDepFlasher` — `74`-prefixed APDUs |

A tag is routed by capability: if it exposes `IsoDep`, it goes to
`IsoDepFlasher`; otherwise the legacy NfcA path is used. Within `IsoDepFlasher`,
the two IsoDep panels are told apart by their response to a descriptor read
(the 296×128 panel answers with an `0xA0` descriptor block; the 1.54″ panel
returns `0x6D00`, "instruction not supported", and is handled by the SSD1681‑style
path).

## The Pixel problem, and what actually fixed it

WaveShare's documentation and apps state that flashing "does not work on Google
Pixel." In practice we found this was **not** a fundamental RF/hardware
limitation of the Pixel. Two separate things were being conflated:

1. **The app's own tag rejection (the real blocker in this project).**
   The original code checked `tag.techList[0] == "android.nfc.tech.NfcA"`. The
   ordering of `Tag.getTechList()` is not a documented contract, and on the
   Pixel 7 Pro the newer displays enumerate `IsoDep` first, so this check
   rejected a perfectly valid tag before anything else happened. The fix is to
   check for `NfcA` *anywhere* in the list (`techList.contains(...)`), and — for
   the newer panels — to route by `IsoDep` capability instead.

2. **Cutting NFC power mid‑refresh (a self‑inflicted Pixel symptom).**
   These panels are passively powered by the phone's NFC field. A colour refresh
   takes ~10–15 seconds. Our first 1.54″ implementation stopped polling as soon
   as the busy flag cleared, closed the `IsoDep` connection, and thereby dropped
   the field **while the panel was still refreshing** — leaving it blank. The
   official app avoids this by continuing to poll (which keeps the field alive)
   for a minimum duration. Matching that behaviour — a fixed settle delay plus a
   minimum poll count before returning — made the refresh complete.

Notably, once the correct protocol was used, the **descriptor read and the full
image write both succeeded on the Pixel on the first try**, and the official
WaveShare app itself was later observed flashing successfully on the same Pixel.
The lesson: the "Pixel doesn't work" reputation here was mostly protocol/timing
handling, not the radio.

## The IsoDep protocols

### 296×128 panel (`BMXR`)

- Select the NDEF Type‑4 application, then read a self‑describing descriptor
  (`00D1000000` → an `0xA0` TLV block) that reports geometry.
- The framebuffer is **portrait 128×296** at **2 bits/pixel** (a single 9472‑byte
  buffer, 32 bytes/row). The physical panel is landscape, so the authored
  (landscape) image is rotated 90° before encoding. Getting this dimension wrong
  was the single biggest source of confusion: packing 74‑byte rows (as if the
  buffer were 296 wide) instead of 32‑byte rows produced a diagonal shear that
  looked like a vertical seam down the middle.
- Pixels are packed 4 per byte (`p0<<6 | p1<<4 | p2<<2 | p3`), then written with
  the uncompressed write command (`F0 D2 …`). The panel also supports an
  LZO‑compressed command; the uncompressed path is used to avoid porting the
  compressor and still completes in a few seconds.
- Refresh is a two‑phase handshake (`F0 D4 05 … 00`, a `0x6986`/`0x68C6`
  kick‑off retry as `F0 D4 85 …`, then polling `F0 DE 00 00 01`).

### 1.54″ 200×200 panel

- A different command set entirely: SSD1681‑style controller commands bridged
  over `74`-prefixed APDUs (`74 99 …` = controller command, `74 9A …` =
  controller data, `74 9B …` = read busy), after an auth step with a fixed key.
- The image is a **single 2‑bit RAM buffer** (200×200 → 10000 bytes, 50
  bytes/row), same packing and palette as the 296×128 panel. No rotation (it's
  square).
- Refresh is triggered via config registers, then a ~10 s settle and a busy‑poll
  whose polarity is inverted relative to the other panel (busy while the status
  byte reads `0`). The poll keeps the NFC field alive through the refresh.

Both protocols were recovered by decompiling WaveShare's official apps and, for
the 1.54″ panel, by instrumenting the vendor app to log its live NFC traffic
(the exact byte sequence was read straight from the reference implementation
rather than guessed).

## Colour encoding and dithering

All 4‑colour panels use the same 2‑bit palette: `0` = black, `1` = white,
`2` = yellow, `3` = red.

Images are mapped to this palette with **Floyd–Steinberg error diffusion**
(7/16 right, 3/16 below‑left, 5/16 below, 1/16 below‑right), which preserves
tonal range in photographs. Content that is already palette‑exact (e.g.
black‑on‑white text) has zero quantisation error and is therefore left crisp.

Dithering can be toggled off from the main screen ("Dither photos"); when off,
each pixel snaps to its nearest palette colour. The setting is persisted.

## UI changes

- **Screen‑size list**: `1.54″` was added and moved to the top of the picker.
  The picker order is now decoupled from the WaveShare NfcA SDK enum (a separate
  `ScreenSizeToWsEnum` map), so the list can be reordered without breaking the
  legacy displays' size mapping. Selecting `1.54″` renders on a **200×200**
  square canvas, avoiding non‑uniform scaling at flash time.
- **Dither toggle**: a switch on the main screen, defaulting to on.
- **Graphic editor**: the WYSIWYG/JSPaint button is hidden pending a bug fix.

## Build notes

The project targets a 2021‑era toolchain (Gradle 6.5, AGP 4.1.3), which requires
**JDK 11** and Android **platform 30 / build‑tools 30.0.3**. Build with:

```bash
JAVA_HOME=<jdk-11> ANDROID_HOME=<sdk> ./gradlew assembleDebug
```
