// Draws the app icon -- three rising bars on indigo -- as the PNGs the web app manifest and iOS
// need, with no image library: a tiny anti-aliased rasterizer and a hand-written PNG encoder.
// The same geometry is in public/favicon.svg; change both together.
//
//   node scripts/generate-icons.mjs
//
// Writes into public/. The output is committed, so this only needs re-running after a change.
import { writeFileSync } from "node:fs";
import { deflateSync } from "node:zlib";

const INDIGO = [0x4f, 0x46, 0xe5];
const WHITE = [0xff, 0xff, 0xff];

// In units of the icon's width. The bars sit well inside the maskable safe zone (a circle of
// radius 0.4 around the centre), so a launcher's circle or squircle crop never clips them.
const BARS = [
  { x: 0.32, height: 0.18 },
  { x: 0.455, height: 0.28 },
  { x: 0.59, height: 0.4 },
];
const BAR_WIDTH = 0.09;
const BASELINE = 0.7;
const BAR_RADIUS = 0.025;

function insideRoundedRect(px, py, x, y, w, h, r) {
  if (px < x || px > x + w || py < y || py > y + h) return false;
  const cx = Math.min(Math.max(px, x + r), x + w - r);
  const cy = Math.min(Math.max(py, y + r), y + h - r);
  return (px - cx) ** 2 + (py - cy) ** 2 <= r * r;
}

/** RGBA pixels; `cornerRadius` 0 fills the square edge to edge (maskable and iOS want that). */
function draw(size, cornerRadius) {
  const SAMPLES = 4;
  const pixels = Buffer.alloc(size * size * 4);
  for (let row = 0; row < size; row++) {
    for (let col = 0; col < size; col++) {
      let bg = 0;
      let bar = 0;
      for (let sy = 0; sy < SAMPLES; sy++) {
        for (let sx = 0; sx < SAMPLES; sx++) {
          const u = (col + (sx + 0.5) / SAMPLES) / size;
          const v = (row + (sy + 0.5) / SAMPLES) / size;
          if (!insideRoundedRect(u, v, 0, 0, 1, 1, cornerRadius)) continue;
          bg++;
          if (BARS.some((b) => insideRoundedRect(u, v, b.x, BASELINE - b.height, BAR_WIDTH, b.height, BAR_RADIUS))) bar++;
        }
      }
      const total = SAMPLES * SAMPLES;
      const barShare = bg === 0 ? 0 : bar / bg;
      const i = (row * size + col) * 4;
      for (let c = 0; c < 3; c++) pixels[i + c] = Math.round(INDIGO[c] * (1 - barShare) + WHITE[c] * barShare);
      pixels[i + 3] = Math.round((bg / total) * 255);
    }
  }
  return pixels;
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

function png(size, pixels) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bit depth
  header[9] = 6; // RGBA
  // Each scanline is prefixed with filter type 0 (none).
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let row = 0; row < size; row++) {
    pixels.copy(raw, row * (size * 4 + 1) + 1, row * size * 4, (row + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const out = new URL("../public/", import.meta.url);
const icons = [
  { file: "icons/icon-192.png", size: 192, corner: 0.22 },
  { file: "icons/icon-512.png", size: 512, corner: 0.22 },
  { file: "icons/icon-maskable-512.png", size: 512, corner: 0 },
  // iOS rounds the corners itself and shows any transparency as black.
  { file: "apple-touch-icon.png", size: 180, corner: 0 },
];
for (const { file, size, corner } of icons) {
  writeFileSync(new URL(file, out), png(size, draw(size, corner)));
  console.log(`wrote public/${file}`);
}
