#!/usr/bin/env node
/**
 * Emulated ESC/POS network printer: listens on PORT (default 9100), stores every job under OUT_DIR as
 * job-<n>.bin and prints a decoded summary (init, code page, cut, raster image, text preview in CP437).
 *
 *   PORT=9105 OUT_DIR=./escpos-jobs node tools/perf/escpos-capture.mjs
 *
 * Point the API at it with the settings pos.printerHost=127.0.0.1 and pos.printerPort=<PORT>.
 */
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

const PORT = Number(process.env.PORT ?? 9100);
const OUT = process.env.OUT_DIR ?? './escpos-jobs';
fs.mkdirSync(OUT, { recursive: true });
let n = 0;

// CP437 upper half (0x80-0xFF) for the text preview.
const CP437 = 'ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ';

function summarize(buf) {
  const has = (...seq) => buf.indexOf(Buffer.from(seq)) >= 0;
  let text = '';
  for (const b of buf) {
    if (b === 0x0a) text += '\n';
    else if (b >= 0x20 && b < 0x7f) text += String.fromCharCode(b);
    else if (b >= 0x80) text += CP437[b - 0x80];
  }
  return {
    bytes: buf.length,
    init: has(0x1b, 0x40), // ESC @
    codePage: has(0x1b, 0x74), // ESC t n
    cut: has(0x1d, 0x56), // GS V
    raster: has(0x1d, 0x76, 0x30), // GS v 0
    drawerKick: has(0x1b, 0x70), // ESC p
    preview: text.replace(/[^\S\n]+/g, ' ').split('\n').map((l) => l.trim()).filter(Boolean).slice(0, 12),
  };
}

net.createServer((sock) => {
  const chunks = [];
  sock.on('data', (c) => chunks.push(c));
  sock.on('end', () => {
    const buf = Buffer.concat(chunks);
    const file = path.join(OUT, `job-${++n}.bin`);
    fs.writeFileSync(file, buf);
    console.log(JSON.stringify({ job: n, file, ...summarize(buf) }));
  });
  sock.on('error', () => {});
}).listen(PORT, () => console.log(`escpos capture listening on ${PORT}`));
