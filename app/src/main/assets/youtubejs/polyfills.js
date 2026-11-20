/*
 * AURALIS YouTube.js runtime polyfills.
 *
 * Loaded into the embedded QuickJS engine BEFORE the youtubei.js bundle, because the bundle reads
 * several browser globals at evaluation time (Platform.shim captures globalThis.fetch/Request/...).
 * QuickJS is a pure ECMAScript engine: it has no DOM, no timers, no TextEncoder/TextDecoder, no URL,
 * no crypto. These shims provide just enough of the browser surface for the keyless audio path.
 *
 * Deliberately ES5-style (var, no spread/destructuring) so it parses on the widest QuickJS range.
 */
(function () {
  'use strict';
  var g = globalThis;

  // ---- console (the youtubei.js logger writes through console.*) ------------------------------
  // QuickJS usually has no console at all; some hosts expose a partial one, so fill any missing method.
  var noop = function () {};
  if (typeof g.console === 'undefined' || !g.console) g.console = {};
  var CONSOLE_METHODS = ['log', 'info', 'warn', 'error', 'debug', 'trace', 'dir', 'group', 'groupEnd',
    'groupCollapsed', 'table', 'time', 'timeEnd', 'timeLog', 'assert', 'count', 'countReset'];
  for (var ci = 0; ci < CONSOLE_METHODS.length; ci++) {
    if (typeof g.console[CONSOLE_METHODS[ci]] !== 'function') g.console[CONSOLE_METHODS[ci]] = noop;
  }

  // ---- timers (only used for retry/backoff scheduling) ----------------------------------------
  if (typeof g.setTimeout === 'undefined') {
    g.setTimeout = function (fn) { queueMicrotask(function () { try { fn(); } catch (e) {} }); return 0; };
    g.clearTimeout = function () {};
    g.setInterval = function () { return 0; };
    g.clearInterval = function () {};
  }
  if (typeof g.queueMicrotask === 'undefined') {
    g.queueMicrotask = function (fn) { Promise.resolve().then(function () { fn(); }); };
  }
  if (typeof g.performance === 'undefined') {
    g.performance = { now: function () { return Date.now(); }, timeOrigin: 0 };
  }
  if (typeof g.navigator === 'undefined') {
    g.navigator = {
      userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Auralis/1.2',
      language: 'en-US', languages: ['en-US', 'en'], platform: 'Linux', onLine: true,
    };
  }
  if (typeof g.structuredClone === 'undefined') {
    // The JS-signature parser clones AST nodes; a JSON round-trip would drop prototypes and
    // undefined fields, so do a real recursive clone that preserves the prototype and cycles.
    g.structuredClone = function clone(value, seen) {
      if (value === null || typeof value !== 'object') return value;
      if (value instanceof Date) return new Date(value.getTime());
      if (value instanceof RegExp) return new RegExp(value.source, value.flags || '');
      if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) return value.slice(0);
      if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(value)) {
        return new value.constructor(value.buffer.slice(0), value.byteOffset, value.length);
      }
      seen = seen || [];
      for (var s = 0; s < seen.length; s++) if (seen[s][0] === value) return seen[s][1];
      if (Array.isArray(value)) {
        var arr = [];
        seen.push([value, arr]);
        for (var i = 0; i < value.length; i++) arr[i] = clone(value[i], seen);
        return arr;
      }
      var out = Object.create(Object.getPrototypeOf(value));
      seen.push([value, out]);
      var keys = Object.keys(value);
      for (var k = 0; k < keys.length; k++) out[keys[k]] = clone(value[keys[k]], seen);
      return out;
    };
  }

  // ---- TextEncoder / TextDecoder --------------------------------------------------------------
  if (typeof g.TextEncoder === 'undefined') {
    g.TextEncoder = function () {};
    g.TextEncoder.prototype.encode = function (input) {
      var str = input == null ? '' : String(input);
      var out = [];
      for (var i = 0; i < str.length; i++) {
        var c = str.charCodeAt(i);
        if (c < 0x80) {
          out.push(c);
        } else if (c < 0x800) {
          out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
        } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
          var c2 = str.charCodeAt(++i);
          var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
          out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
        } else {
          out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
        }
      }
      return new Uint8Array(out);
    };
    // encodeInto is used by stream helpers in some builds; provide a best-effort shim.
    g.TextEncoder.prototype.encodeInto = function (input, dest) {
      var bytes = this.encode(input);
      var n = Math.min(bytes.length, dest.length);
      for (var i = 0; i < n; i++) dest[i] = bytes[i];
      return { read: input.length, written: n };
    };

    g.TextDecoder = function (label, options) {
      this.encoding = (label || 'utf-8').toLowerCase();
      this.fatal = !!(options && options.fatal);
      this.ignoreBOM = !!(options && options.ignoreBOM);
    };
    g.TextDecoder.prototype.decode = function (input) {
      if (!input) return '';
      var bytes;
      if (input instanceof Uint8Array) {
        bytes = input;
      } else if (input.buffer) {
        bytes = new Uint8Array(input.buffer, input.byteOffset || 0, input.byteLength || input.length || 0);
      } else {
        bytes = new Uint8Array(input);
      }
      var out = '';
      var i = 0;
      while (i < bytes.length) {
        var b = bytes[i++];
        if (b < 0x80) {
          out += String.fromCharCode(b);
        } else if (b >= 0xc0 && b < 0xe0) {
          out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i++] & 0x3f));
        } else if (b >= 0xe0 && b < 0xf0) {
          out += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f));
        } else {
          var cp2 = ((b & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
          cp2 -= 0x10000;
          out += String.fromCharCode(0xd800 + (cp2 >> 10), 0xdc00 + (cp2 & 0x3ff));
        }
      }
      return out;
    };
  }

  // ---- base64 ---------------------------------------------------------------------------------
  if (typeof g.btoa === 'undefined') {
    var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    g.btoa = function (data) {
      var s = String(data);
      var out = '';
      for (var i = 0; i < s.length; i += 3) {
        var c1 = s.charCodeAt(i) & 0xff;
        var c2 = i + 1 < s.length ? s.charCodeAt(i + 1) & 0xff : NaN;
        var c3 = i + 2 < s.length ? s.charCodeAt(i + 2) & 0xff : NaN;
        out += B64.charAt(c1 >> 2);
        out += B64.charAt(((c1 & 3) << 4) | (isNaN(c2) ? 0 : c2 >> 4));
        out += isNaN(c2) ? '=' : B64.charAt(((c2 & 15) << 2) | (isNaN(c3) ? 0 : c3 >> 6));
        out += isNaN(c3) ? '=' : B64.charAt(c3 & 63);
      }
      return out;
    };
    g.atob = function (data) {
      var s = String(data).replace(/[^A-Za-z0-9+/=]/g, '');
      var out = '';
      for (var i = 0; i < s.length; i += 4) {
        var n = (B64.indexOf(s.charAt(i)) << 18) | (B64.indexOf(s.charAt(i + 1)) << 12) |
          ((s.charAt(i + 2) === '=' ? 0 : B64.indexOf(s.charAt(i + 2))) << 6) |
          (s.charAt(i + 3) === '=' ? 0 : B64.indexOf(s.charAt(i + 3)));
        out += String.fromCharCode((n >> 16) & 0xff);
        if (s.charAt(i + 2) !== '=') out += String.fromCharCode((n >> 8) & 0xff);
        if (s.charAt(i + 3) !== '=') out += String.fromCharCode(n & 0xff);
      }
      return out;
    };
  }

  // ---- URLSearchParams ------------------------------------------------------------------------
  if (typeof g.URLSearchParams === 'undefined') {
    var enc = function (v) { return encodeURIComponent(String(v)); };
    var dec = function (v) { try { return decodeURIComponent(String(v).replace(/\+/g, ' ')); } catch (e) { return String(v); } };
    g.URLSearchParams = function (init) {
      this._pairs = [];
      if (typeof init === 'string') {
        var s = init.charAt(0) === '?' ? init.slice(1) : init;
        if (s) {
          var parts = s.split('&');
          for (var i = 0; i < parts.length; i++) {
            if (!parts[i]) continue;
            var eq = parts[i].indexOf('=');
            var k = eq < 0 ? parts[i] : parts[i].slice(0, eq);
            var val = eq < 0 ? '' : parts[i].slice(eq + 1);
            this._pairs.push([dec(k), dec(val)]);
          }
        }
      } else if (init instanceof g.URLSearchParams) {
        this._pairs = init._pairs.slice();
      } else if (init && typeof init === 'object') {
        var keys = Object.keys(init);
        for (var j = 0; j < keys.length; j++) this._pairs.push([keys[j], String(init[keys[j]])]);
      }
    };
    g.URLSearchParams.prototype.append = function (k, v) { this._pairs.push([String(k), String(v)]); };
    g.URLSearchParams.prototype.set = function (k, v) {
      k = String(k);
      var found = false;
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === k) { this._pairs[i][1] = String(v); found = true; break; }
      }
      if (!found) this._pairs.push([k, String(v)]);
    };
    g.URLSearchParams.prototype.get = function (k) {
      k = String(k);
      for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === k) return this._pairs[i][1];
      return null;
    };
    g.URLSearchParams.prototype.getAll = function (k) {
      k = String(k);
      var out = [];
      for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === k) out.push(this._pairs[i][1]);
      return out;
    };
    g.URLSearchParams.prototype.has = function (k) { return this.get(k) !== null; };
    g.URLSearchParams.prototype['delete'] = function (k) {
      k = String(k);
      this._pairs = this._pairs.filter(function (p) { return p[0] !== k; });
    };
    g.URLSearchParams.prototype.forEach = function (cb, thisArg) {
      for (var i = 0; i < this._pairs.length; i++) cb.call(thisArg, this._pairs[i][1], this._pairs[i][0], this);
    };
    g.URLSearchParams.prototype.keys = function () {
      var self = this, i = 0;
      return { next: function () { return i < self._pairs.length ? { value: self._pairs[i++][0], done: false } : { value: undefined, done: true }; } };
    };
    g.URLSearchParams.prototype.values = function () {
      var self = this, i = 0;
      return { next: function () { return i < self._pairs.length ? { value: self._pairs[i++][1], done: false } : { value: undefined, done: true }; } };
    };
    g.URLSearchParams.prototype.entries = function () {
      var self = this, i = 0;
      return { next: function () { return i < self._pairs.length ? { value: [self._pairs[i][0], self._pairs[i++][1]], done: false } : { value: undefined, done: true }; } };
    };
    g.URLSearchParams.prototype[Symbol.iterator] = g.URLSearchParams.prototype.entries;
    g.URLSearchParams.prototype.toString = function () {
      var out = [];
      for (var i = 0; i < this._pairs.length; i++) out.push(enc(this._pairs[i][0]) + '=' + enc(this._pairs[i][1]));
      return out.join('&');
    };
  }

  // ---- URL ------------------------------------------------------------------------------------
  if (typeof g.URL === 'undefined') {
    // Group numbering matters: 1 = scheme, 2 = authority, 3 = path, 4 = query, 5 = fragment.
    var URL_RE = /^([a-zA-Z][a-zA-Z0-9+.-]*:)?(?:\/\/([^\/?#]*))?([^?#]*)(\?[^#]*)?(#.*)?$/;
    var SPECIAL = { 'http:': true, 'https:': true, 'ws:': true, 'wss:': true, 'ftp:': true };
    var DEFAULT_PORTS = { 'http:': '80', 'https:': '443', 'ws:': '80', 'wss:': '443', 'ftp:': '21' };

    var resolveHref = function (input, base) {
      input = String(input);
      if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(input)) return input;
      if (!base) return input;
      var b = new g.URL(base);
      var path = b.pathname || '/';
      if (input.indexOf('//') === 0) return b.protocol + input;
      if (input.charAt(0) === '/') return b.protocol + '//' + b.host + input;
      if (input.charAt(0) === '?') return b.protocol + '//' + b.host + path + input;
      if (input.charAt(0) === '#') return b.protocol + '//' + b.host + path + b.search + input;
      var dir = path.replace(/[^\/]*$/, '');
      return b.protocol + '//' + b.host + dir + input;
    };

    g.URL = function (input, base) {
      this._searchParams = null;
      this._path = '';
      this._search = '';
      this._hash = '';
      this._parse(resolveHref(input, base));
    };
    g.URL.prototype._parse = function (href) {
      var m = URL_RE.exec(href);
      if (!m) {
        this.protocol = ''; this.host = ''; this._path = href; this._search = ''; this._hash = '';
      } else {
        this.protocol = m[1] || '';
        this.host = m[2] || '';
        var path = m[3] || '';
        // A special-scheme URL with an authority always has a path; "/" is the canonical empty path.
        if (this.host && path === '' && SPECIAL[this.protocol]) path = '/';
        this._path = path;
        this._search = m[4] || '';
        this._hash = m[5] || '';
      }
      this._searchParams = null;
      var at = this.host.lastIndexOf('@');
      var hp = at >= 0 ? this.host.slice(at + 1) : this.host;
      var ci = hp.indexOf(':');
      this.hostname = ci >= 0 ? hp.slice(0, ci) : hp;
      this.port = ci >= 0 ? hp.slice(ci + 1) : '';
      this.username = at >= 0 ? this.host.slice(0, at) : '';
      this.password = at >= 0 ? this.host.slice(this.host.indexOf(':') + 1, at) : '';
      this.origin = this.protocol === 'http:' || this.protocol === 'https:' ? this.protocol + '//' + this.host : 'null';
    };
    Object.defineProperty(g.URL.prototype, 'pathname', {
      get: function () { return this._path; },
      set: function (v) { this._path = String(v); },
    });
    Object.defineProperty(g.URL.prototype, 'search', {
      get: function () { return this._search; },
      set: function (v) {
        v = String(v);
        this._search = v === '' ? '' : (v.charAt(0) === '?' ? v : '?' + v);
        this._searchParams = null;
      },
    });
    Object.defineProperty(g.URL.prototype, 'hash', {
      get: function () { return this._hash; },
      set: function (v) {
        v = String(v);
        this._hash = v === '' ? '' : (v.charAt(0) === '#' ? v : '#' + v);
      },
    });
    Object.defineProperty(g.URL.prototype, 'href', {
      get: function () { return this.toString(); },
      set: function (v) { this._parse(String(v)); },
    });
    Object.defineProperty(g.URL.prototype, 'searchParams', {
      get: function () {
        if (!this._searchParams) this._searchParams = new g.URLSearchParams(this._search);
        return this._searchParams;
      },
    });
    g.URL.prototype.toString = function () {
      var search = this._search;
      if (this._searchParams) {
        var s = this._searchParams.toString();
        search = s ? '?' + s : '';
      }
      this._search = search;
      return this.protocol + (this.host ? '//' + this.host : '') + (this._path || (this.host ? '/' : '')) + search + this._hash;
    };
    g.URL.prototype.toJSON = function () { return this.toString(); };
    g.URL.canParse = function (u) { try { new g.URL(u); return true; } catch (e) { return false; } };
  }

  // ---- crypto (visitor id generation on the keyless path) -------------------------------------
  if (typeof g.crypto === 'undefined') {
    g.crypto = {
      getRandomValues: function (arr) {
        for (var i = 0; i < arr.length; i++) arr[i] = Math.floor(Math.random() * 256);
        return arr;
      },
      randomUUID: function () {
        return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, function (c) {
          var n = parseInt(c, 10);
          return (n ^ (Math.floor(Math.random() * 256) & (15 >> (n / 4)))).toString(16);
        });
      },
    };
  }

  // ---- Intl (a single Intl.DateTimeFormat use in the bundle) ----------------------------------
  if (typeof g.Intl === 'undefined') g.Intl = {};
  if (typeof g.Intl.DateTimeFormat !== 'function') {
    g.Intl.DateTimeFormat = function () {
      return {
        format: function () { return ''; },
        formatToParts: function () { return []; },
        resolvedOptions: function () { return { locale: 'en-US' }; },
      };
    };
  }

  // ---- CustomEvent ----------------------------------------------------------------------------
  if (typeof g.CustomEvent === 'undefined') {
    g.CustomEvent = function (type, init) { this.type = type; this.detail = init && init.detail; };
  }

  // ---- ReadableStream (minimal: start/controller + single-or-multi chunk reader) --------------
  if (typeof g.ReadableStream === 'undefined') {
    g.ReadableStream = function (source) {
      var chunks = [];
      var closed = false;
      var waiters = [];
      var controller = {
        enqueue: function (chunk) {
          if (waiters.length) waiters.shift()({ value: chunk, done: false });
          else chunks.push(chunk);
        },
        close: function () {
          closed = true;
          while (waiters.length) waiters.shift()({ value: undefined, done: true });
        },
        error: function () { closed = true; while (waiters.length) waiters.shift()({ value: undefined, done: true }); },
        desiredSize: 1,
      };
      try { if (source && source.start) source.start(controller); } catch (e) {}
      this.getReader = function () {
        return {
          read: function () {
            if (chunks.length) return Promise.resolve({ value: chunks.shift(), done: false });
            if (closed) return Promise.resolve({ value: undefined, done: true });
            return new Promise(function (resolve) { waiters.push(resolve); });
          },
          releaseLock: function () {},
          cancel: function () { closed = true; return Promise.resolve(); },
        };
      };
      this.cancel = function () { closed = true; return Promise.resolve(); };
      this[Symbol.asyncIterator] = function () {
        var reader = this.getReader();
        return { next: function () { return reader.read(); }, return: function () { return Promise.resolve({ done: true, value: undefined }); } };
      };
    };
  }

  // ---- FormData / Blob / File (present for the platform shim; unused on the read-only path) ---
  if (typeof g.FormData === 'undefined') {
    g.FormData = function () { this._entries = []; };
    g.FormData.prototype.append = function (k, v) { this._entries.push([String(k), v]); };
    g.FormData.prototype.set = function (k, v) {
      var idx = -1;
      for (var i = 0; i < this._entries.length; i++) if (this._entries[i][0] === String(k)) { idx = i; break; }
      if (idx >= 0) this._entries[idx] = [String(k), v]; else this._entries.push([String(k), v]);
    };
    g.FormData.prototype.get = function (k) {
      for (var i = 0; i < this._entries.length; i++) if (this._entries[i][0] === String(k)) return this._entries[i][1];
      return null;
    };
    g.FormData.prototype.has = function (k) { return this.get(k) !== null; };
    g.FormData.prototype['delete'] = function (k) { this._entries = this._entries.filter(function (e) { return e[0] !== String(k); }); };
    g.FormData.prototype.forEach = function (cb) { for (var i = 0; i < this._entries.length; i++) cb(this._entries[i][1], this._entries[i][0], this); };
  }
  if (typeof g.Blob === 'undefined') {
    g.Blob = function (parts, options) {
      this._parts = parts || [];
      this.type = (options && options.type) || '';
      this.size = this._parts.join('').length;
    };
    g.Blob.prototype.text = function () { return Promise.resolve(this._parts.join('')); };
    g.Blob.prototype.arrayBuffer = function () { return Promise.resolve(new g.TextEncoder().encode(this._parts.join('')).buffer); };
    g.Blob.prototype.slice = function () { return new g.Blob(this._parts, { type: this.type }); };
  }
  if (typeof g.File === 'undefined') {
    g.File = function (parts, name, options) { g.Blob.call(this, parts, options); this.name = name; this.lastModified = Date.now(); };
    g.File.prototype = Object.create(g.Blob.prototype);
  }

  // ---- modern built-ins that a stripped-down engine may not ship ------------------------------
  // (QuickJS-ng has these, but the guards are cheap insurance against a narrower build.)
  if (typeof g.Object.hasOwn !== 'function') {
    g.Object.hasOwn = function (obj, key) { return Object.prototype.hasOwnProperty.call(obj, key); };
  }
  if (typeof g.Object.fromEntries !== 'function') {
    g.Object.fromEntries = function (entries) {
      var out = {};
      var it = entries[Symbol.iterator]();
      for (var step = it.next(); !step.done; step = it.next()) out[step.value[0]] = step.value[1];
      return out;
    };
  }
  if (typeof g.Array.prototype.at !== 'function') {
    g.Array.prototype.at = function (n) {
      n = Math.trunc(n) || 0;
      if (n < 0) n += this.length;
      return n < 0 || n >= this.length ? undefined : this[n];
    };
  }
  if (typeof g.String.prototype.at !== 'function') {
    g.String.prototype.at = function (n) {
      n = Math.trunc(n) || 0;
      if (n < 0) n += this.length;
      return n < 0 || n >= this.length ? undefined : this.charAt(n);
    };
  }
  if (typeof g.String.prototype.replaceAll !== 'function') {
    g.String.prototype.replaceAll = function (search, replacement) {
      if (search instanceof RegExp) {
        if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
        return this.replace(search, replacement);
      }
      return this.split(String(search)).join(String(replacement));
    };
  }
  if (typeof g.Array.prototype.flat !== 'function') {
    g.Array.prototype.flat = function (depth) {
      depth = depth === undefined ? 1 : Math.trunc(depth);
      var out = [];
      (function walk(arr, d) {
        for (var i = 0; i < arr.length; i++) {
          if (d > 0 && Array.isArray(arr[i])) walk(arr[i], d - 1);
          else out.push(arr[i]);
        }
      })(this, depth);
      return out;
    };
  }
  if (typeof g.Array.prototype.findLast !== 'function') {
    g.Array.prototype.findLast = function (pred, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (pred.call(thisArg, this[i], i, this)) return this[i];
      return undefined;
    };
  }
  if (typeof g.Array.prototype.findLastIndex !== 'function') {
    g.Array.prototype.findLastIndex = function (pred, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (pred.call(thisArg, this[i], i, this)) return i;
      return -1;
    };
  }
  if (typeof g.Promise.allSettled !== 'function') {
    g.Promise.allSettled = function (list) {
      return Promise.all(Array.prototype.map.call(list, function (p) {
        return Promise.resolve(p).then(
          function (value) { return { status: 'fulfilled', value: value }; },
          function (reason) { return { status: 'rejected', reason: reason }; }
        );
      }));
    };
  }
})();
