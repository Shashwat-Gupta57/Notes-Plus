// Zip files in and out with the browser's own (de)compression, no library: what import reads (a
// Google Takeout zip, a zipped folder of Markdown) and what export writes. Plain zip only: no zip64
// (archives over 4 GB) and no encryption.

const enc = new TextEncoder(), dec = new TextDecoder();

async function inflate(bytes) {
  const out = new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw')));
  return new Uint8Array(await out.arrayBuffer());
}

async function deflate(bytes) {
  const out = new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw')));
  return new Uint8Array(await out.arrayBuffer());
}

/** Every file in a zip: [{ path, bytes }]. Folders are left out. */
export async function readZip(buffer) {
  const data = new Uint8Array(buffer), view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  let end = -1;
  for (let i = data.length - 22; i >= Math.max(0, data.length - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50) { end = i; break; }
  }
  if (end < 0) throw new Error("That isn't a zip file");
  const count = view.getUint16(end + 10, true);
  let at = view.getUint32(end + 16, true);
  if (at === 0xffffffff || count === 0xffff) throw new Error('That zip is too big to open here (over 4 GB)');
  const files = [];
  for (let k = 0; k < count; k++) {
    if (view.getUint32(at, true) !== 0x02014b50) throw new Error('That zip is damaged');
    const method = view.getUint16(at + 10, true), size = view.getUint32(at + 20, true);
    const nameLen = view.getUint16(at + 28, true), extraLen = view.getUint16(at + 30, true), commentLen = view.getUint16(at + 32, true);
    const local = view.getUint32(at + 42, true);
    const path = dec.decode(data.subarray(at + 46, at + 46 + nameLen));
    at += 46 + nameLen + extraLen + commentLen;
    if (path.endsWith('/')) continue;
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    const raw = data.subarray(start, start + size);
    if (method === 0) files.push({ path, bytes: raw.slice() });
    else if (method === 8) files.push({ path, bytes: await inflate(raw) });
    // Other methods (rare): skipped.
  }
  return files;
}

let table = null;
function crc32(bytes) {
  if (!table) {
    table = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = table[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** A zip of [{ path, bytes }]: text compressed, pictures and sound stored as they are. */
export async function writeZip(files) {
  const parts = [], central = [];
  const now = new Date();
  const time = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1);
  const date = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();
  let offset = 0;
  for (const f of files) {
    const name = enc.encode(f.path), raw = f.bytes instanceof Uint8Array ? f.bytes : new Uint8Array(f.bytes);
    const packed = /\.(png|jpe?g|gif|webp|heic|mp4|m4a|aac|mp3|wav|ogg|webm|zip)$/i.test(f.path);
    const body = packed ? raw : await deflate(raw);
    const crc = crc32(raw), method = packed ? 0 : 8;
    const head = new Uint8Array(30 + name.length), h = new DataView(head.buffer);
    h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true); h.setUint16(8, method, true);
    h.setUint16(10, time, true); h.setUint16(12, date, true); h.setUint32(14, crc, true);
    h.setUint32(18, body.length, true); h.setUint32(22, raw.length, true); h.setUint16(26, name.length, true);
    head.set(name, 30);
    parts.push(head, body);
    const c = new Uint8Array(46 + name.length), v = new DataView(c.buffer);
    v.setUint32(0, 0x02014b50, true); v.setUint16(4, 20, true); v.setUint16(6, 20, true); v.setUint16(8, 0x0800, true);
    v.setUint16(10, method, true); v.setUint16(12, time, true); v.setUint16(14, date, true); v.setUint32(16, crc, true);
    v.setUint32(20, body.length, true); v.setUint32(24, raw.length, true); v.setUint16(28, name.length, true);
    v.setUint32(42, offset, true);
    c.set(name, 46);
    central.push(c);
    offset += head.length + body.length;
  }
  const size = central.reduce((s, c) => s + c.length, 0);
  const end = new Uint8Array(22), e = new DataView(end.buffer);
  e.setUint32(0, 0x06054b50, true); e.setUint16(8, files.length, true); e.setUint16(10, files.length, true);
  e.setUint32(12, size, true); e.setUint32(16, offset, true);
  return new Blob([...parts, ...central, end], { type: 'application/zip' });
}
