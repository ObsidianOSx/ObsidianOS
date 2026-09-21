// Browser pieces the flashing library needs, for running it under Node. Node's own Blob recurses once
// per chunk when read and overflows on gigabyte images, so a Blob made of pieces stands in for it, the
// way browsers do it: appending keeps references instead of copying, and slicing finds its first piece
// by binary search, so writing a large file in small chunks stays linear.
class BufferBlob {
  constructor(parts = [], opts = {}) {
    this._parts = [];
    for (const p of parts) {
      if (p instanceof BufferBlob) { for (const q of p._parts) this._parts.push(q); }
      else if (p instanceof ArrayBuffer) this._parts.push(new Uint8Array(p));
      else if (ArrayBuffer.isView(p)) this._parts.push(new Uint8Array(p.buffer, p.byteOffset, p.byteLength));
      else this._parts.push(new TextEncoder().encode(String(p)));
    }
    this._index();
    this.type = opts.type || "";
  }
  _index() {
    this._starts = new Array(this._parts.length);
    let n = 0;
    for (let i = 0; i < this._parts.length; i++) { this._starts[i] = n; n += this._parts[i].length; }
    this._size = n;
  }
  get size() { return this._size; }
  slice(start = 0, end = this._size, type = "") {
    const n = this._size;
    const s = start < 0 ? Math.max(n + start, 0) : Math.min(start, n);
    const e = Math.max(s, end < 0 ? Math.max(n + end, 0) : Math.min(end, n));
    let lo = 0, hi = this._parts.length - 1, first = this._parts.length;
    while (lo <= hi) {                       // first piece whose end is past s
      const mid = (lo + hi) >> 1;
      if (this._starts[mid] + this._parts[mid].length > s) { first = mid; hi = mid - 1; } else lo = mid + 1;
    }
    const out = [];
    for (let i = first; i < this._parts.length && this._starts[i] < e; i++) {
      const b = this._parts[i], bs = this._starts[i];
      out.push(b.subarray(Math.max(s - bs, 0), Math.min(e - bs, b.length)));
    }
    const r = Object.create(BufferBlob.prototype);
    r._parts = out; r._index(); r.type = type;
    return r;
  }
  _bytes() {
    const u = new Uint8Array(this._size);
    let o = 0;
    for (const b of this._parts) { u.set(b, o); o += b.length; }
    return u;
  }
  async arrayBuffer() { return this._bytes().buffer; }
  async text() { return new TextDecoder().decode(this._bytes()); }
}
globalThis.Blob = BufferBlob;
globalThis.FileReader = class {
  _ok(r) { this.result = r; queueMicrotask(() => this.onload?.({ target: this })); }
  readAsArrayBuffer(blob) { this._ok(blob._bytes().buffer); }
  readAsText(blob) { this._ok(new TextDecoder().decode(blob._bytes())); }
};
globalThis.ProgressEvent = class ProgressEvent {};
globalThis.window = { requestAnimationFrame: (cb) => setTimeout(cb, 0) };
// zip.js builds its web worker from a Blob URL at load time; workers are switched off here, so it is never used.
URL.createObjectURL = () => "blob:node-stub";
