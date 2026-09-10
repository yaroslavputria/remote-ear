#!/usr/bin/env node
// Generates the Play Store graphics from the same geometry as the app icon, so the two cannot
// drift apart: the icon in the launcher and the icon in the listing are the same drawing.
//
//   node tools/make-store-assets.js
//
// Writes store/icon-512.png (Play's required 32-bit icon) and store/feature-1024x500.png.
//
// It hand-rolls the PNG rather than pulling in an image library. That is not showing off: this
// repository's pitch is that a reader can audit every dependency, and adding one to draw four
// circles would be a poor trade. Node's own zlib does the compression.

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const OUT = path.join(__dirname, '..', 'store');

// The palette, verbatim from docs/design-spec.md.
const BG = [0x0e, 0x15, 0x12];
const RING_OUTER = [0x1f, 0x4a, 0x41];
const RING_MID = [0x3e, 0x8c, 0x7d];
const CENTRE = [0x6f, 0xd3, 0xb8];

const SAMPLES = 4; // 4x4 supersampling; circles at these sizes need the anti-aliasing

/** Coverage of one pixel by a filled disc, 0..1. */
function discCoverage(px, py, cx, cy, r) {
  let hits = 0;
  for (let sy = 0; sy < SAMPLES; sy++) {
    for (let sx = 0; sx < SAMPLES; sx++) {
      const x = px + (sx + 0.5) / SAMPLES;
      const y = py + (sy + 0.5) / SAMPLES;
      if (Math.hypot(x - cx, y - cy) <= r) hits++;
    }
  }
  return hits / (SAMPLES * SAMPLES);
}

/** Coverage of one pixel by a ring of radius r and stroke width w, 0..1. */
function ringCoverage(px, py, cx, cy, r, w) {
  let hits = 0;
  for (let sy = 0; sy < SAMPLES; sy++) {
    for (let sx = 0; sx < SAMPLES; sx++) {
      const x = px + (sx + 0.5) / SAMPLES;
      const y = py + (sy + 0.5) / SAMPLES;
      if (Math.abs(Math.hypot(x - cx, y - cy) - r) <= w / 2) hits++;
    }
  }
  return hits / (SAMPLES * SAMPLES);
}

function blend(dst, i, colour, coverage) {
  if (coverage <= 0) return;
  for (let c = 0; c < 3; c++) {
    dst[i + c] = Math.round(dst[i + c] * (1 - coverage) + colour[c] * coverage);
  }
}

/** shapes: [{kind:'disc'|'ring', cx, cy, r, w, colour}] painted in order over BG. */
function render(width, height, shapes) {
  const px = Buffer.alloc(width * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      px[i] = BG[0];
      px[i + 1] = BG[1];
      px[i + 2] = BG[2];
      px[i + 3] = 255;
    }
  }
  for (const s of shapes) {
    // Only visit the pixels the shape can touch.
    const reach = s.r + (s.w || 0) / 2 + 2;
    const x0 = Math.max(0, Math.floor(s.cx - reach));
    const x1 = Math.min(width - 1, Math.ceil(s.cx + reach));
    const y0 = Math.max(0, Math.floor(s.cy - reach));
    const y1 = Math.min(height - 1, Math.ceil(s.cy + reach));
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        const coverage = s.kind === 'disc'
          ? discCoverage(x, y, s.cx, s.cy, s.r)
          : ringCoverage(x, y, s.cx, s.cy, s.r, s.w);
        blend(px, (y * width + x) * 4, s.colour, coverage);
      }
    }
  }
  return px;
}

function crc32(buf) {
  if (typeof zlib.crc32 === 'function') return zlib.crc32(buf) >>> 0;
  let c = ~0;
  for (const byte of buf) {
    c ^= byte;
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const typed = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typed));
  return Buffer.concat([length, typed, crc]);
}

function writePng(file, width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA, i.e. the 32-bit PNG Play asks for
  // 10..12 are compression, filter and interlace, all zero.

  // Each scanline is prefixed with its filter type. Zero - "none" - keeps this readable, and
  // deflate handles flat colour well enough that a smarter filter would save little.
  const raw = Buffer.alloc(height * (1 + width * 4));
  for (let y = 0; y < height; y++) {
    const at = y * (1 + width * 4);
    raw[at] = 0;
    rgba.copy(raw, at + 1, y * width * 4, (y + 1) * width * 4);
  }

  fs.writeFileSync(file, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]));
  console.log(`${path.relative(process.cwd(), file)}  ${width}x${height}  ${fs.statSync(file).size} bytes`);
}

fs.mkdirSync(OUT, { recursive: true });

// ── The icon ─────────────────────────────────────────────────────────────────────────────────────
// Ratios from the 216 canvas in docs/design-spec.md: rings of diameter 150 and 104 at stroke 4,
// centre 58. Scaled to 512, which keeps the mark at 69% of the width - comfortably inside the
// rounding Play and launchers apply.
{
  const S = 512;
  const k = S / 216;
  writePng(path.join(OUT, 'icon-512.png'), S, S, render(S, S, [
    { kind: 'ring', cx: S / 2, cy: S / 2, r: (150 / 2) * k, w: 4 * k, colour: RING_OUTER },
    { kind: 'ring', cx: S / 2, cy: S / 2, r: (104 / 2) * k, w: 4 * k, colour: RING_MID },
    { kind: 'disc', cx: S / 2, cy: S / 2, r: (58 / 2) * k, colour: CENTRE },
  ]));
}

// ── The feature graphic ──────────────────────────────────────────────────────────────────────────
// 1024x500, and deliberately without text: Play draws the app name over this in several
// placements, and burnt-in wording would collide with it. Extra rings carry the same "sound field"
// idea the icon states in three shapes, at a size that can hold more of it.
{
  const W = 1024;
  const H = 500;
  const cx = W / 2;
  const cy = H / 2;
  writePng(path.join(OUT, 'feature-1024x500.png'), W, H, render(W, H, [
    { kind: 'ring', cx, cy, r: 232, w: 3, colour: [0x16, 0x2e, 0x29] },
    { kind: 'ring', cx, cy, r: 186, w: 4, colour: [0x1a, 0x3b, 0x34] },
    { kind: 'ring', cx, cy, r: 140, w: 5, colour: RING_OUTER },
    { kind: 'ring', cx, cy, r: 94, w: 6, colour: RING_MID },
    { kind: 'disc', cx, cy, r: 46, colour: CENTRE },
  ]));
}
