/*
 * AURALIS YouTube.js HTTP shims.
 *
 * Evaluated AFTER polyfills.js and BEFORE the youtubei.js bundle, because the bundle's web platform
 * captures globalThis.fetch / Request / Response / Headers at evaluation time.
 *
 * `fetch` is bridged to Kotlin through the `__nativeFetch` async binding: the engine performs a real
 * OkHttp request on an IO thread and hands back { status, statusText, url, headers, body }. Bodies on
 * this read-only path are UTF-8 text (JSON API replies, the player's base.js), so a text round-trip
 * is sufficient and keeps the bridge allocation-light.
 */
(function () {
  'use strict';
  var g = globalThis;

  function Headers(init) {
    this._m = {};
    if (init instanceof Headers) {
      var self = this;
      init.forEach(function (v, k) { self._m[k] = v; });
    } else if (init && typeof init.forEach === 'function' && !Array.isArray(init)) {
      var s2 = this;
      init.forEach(function (v, k) { s2._m[String(k).toLowerCase()] = String(v); });
    } else if (Array.isArray(init)) {
      for (var i = 0; i < init.length; i++) this.append(init[i][0], init[i][1]);
    } else if (init && typeof init === 'object') {
      var keys = Object.keys(init);
      for (var j = 0; j < keys.length; j++) this._m[keys[j].toLowerCase()] = String(init[keys[j]]);
    }
  }
  Headers.prototype.get = function (k) { var v = this._m[String(k).toLowerCase()]; return v === undefined ? null : v; };
  Headers.prototype.has = function (k) { return Object.prototype.hasOwnProperty.call(this._m, String(k).toLowerCase()); };
  Headers.prototype.set = function (k, v) { this._m[String(k).toLowerCase()] = String(v); };
  Headers.prototype.append = function (k, v) {
    var kk = String(k).toLowerCase();
    this._m[kk] = this._m[kk] === undefined ? String(v) : this._m[kk] + ', ' + String(v);
  };
  Headers.prototype['delete'] = function (k) { delete this._m[String(k).toLowerCase()]; };
  Headers.prototype.forEach = function (cb, thisArg) {
    var keys = Object.keys(this._m);
    for (var i = 0; i < keys.length; i++) cb.call(thisArg, this._m[keys[i]], keys[i], this);
  };
  Headers.prototype.keys = function () { var keys = Object.keys(this._m), i = 0; return { next: function () { return i < keys.length ? { value: keys[i++], done: false } : { value: undefined, done: true }; } }; };
  Headers.prototype.values = function () { var keys = Object.keys(this._m), i = 0, m = this._m; return { next: function () { return i < keys.length ? { value: m[keys[i++]], done: false } : { value: undefined, done: true }; } }; };
  Headers.prototype.entries = function () { var keys = Object.keys(this._m), i = 0, m = this._m; return { next: function () { if (i >= keys.length) return { value: undefined, done: true }; var k = keys[i++]; return { value: [k, m[k]], done: false }; } }; };

  function makeBodyStream(text) {
    var sent = false;
    return {
      getReader: function () {
        return {
          read: function () {
            if (sent) return Promise.resolve({ value: undefined, done: true });
            sent = true;
            return Promise.resolve({ value: new g.TextEncoder().encode(text), done: false });
          },
          releaseLock: function () {},
          cancel: function () { sent = true; return Promise.resolve(); },
        };
      },
      cancel: function () { sent = true; return Promise.resolve(); },
      [Symbol.asyncIterator]: function () {
        var reader = this.getReader();
        return { next: function () { return reader.read(); } };
      },
    };
  }

  function Response(bodyText, init) {
    init = init || {};
    this._body = bodyText == null ? '' : String(bodyText);
    this.status = init.status == null ? 200 : init.status;
    this.statusText = init.statusText == null ? '' : init.statusText;
    this.url = init.url == null ? '' : init.url;
    this.redirected = !!init.redirected;
    this.ok = this.status >= 200 && this.status < 300;
    this.type = init.type || 'basic';
    this.headers = init.headers instanceof Headers ? init.headers : new Headers(init.headers);
  }
  Response.prototype.text = function () { return Promise.resolve(this._body); };
  Response.prototype.json = function () { return Promise.resolve(JSON.parse(this._body)); };
  Response.prototype.arrayBuffer = function () { return Promise.resolve(new g.TextEncoder().encode(this._body).buffer); };
  Response.prototype.clone = function () {
    return new Response(this._body, { status: this.status, statusText: this.statusText, url: this.url, redirected: this.redirected, headers: this.headers });
  };
  Object.defineProperty(Response.prototype, 'body', { get: function () { return makeBodyStream(this._body); } });
  Response.error = function () { return new Response('', { status: 0, statusText: '' }); };
  Response.redirect = function (url, status) { return new Response('', { status: status || 302, url: url }); };

  function Request(input, init) {
    init = init || {};
    if (typeof input === 'string') { this.url = input; }
    else if (input && input.url) { this.url = input.url; }
    else { this.url = String(input); }
    this.method = String(init.method || (input && input.method) || 'GET').toUpperCase();
    this.headers = init.headers instanceof Headers ? init.headers
      : new Headers(init.headers || (input && input.headers));
    this.body = init.body != null ? init.body : (input && input.body);
    this.redirect = init.redirect || 'follow';
    this.credentials = init.credentials || 'same-origin';
    this.mode = init.mode;
    this.signal = init.signal;
    this.referrer = init.referrer || 'about:client';
  }
  Request.prototype.clone = function () { return new Request(this.url, this); };

  function AbortController() { this.signal = new AbortSignal(); }
  function AbortSignal() { this.aborted = false; this.reason = undefined; this._listeners = []; }
  AbortSignal.prototype.addEventListener = function (type, fn) { if (type === 'abort') this._listeners.push(fn); };
  AbortSignal.prototype.removeEventListener = function () {};
  AbortSignal.prototype.throwIfAborted = function () { if (this.aborted) throw (this.reason || new Error('Aborted')); };
  AbortSignal.abort = function (reason) { var s = new AbortSignal(); s.aborted = true; s.reason = reason; return s; };
  AbortSignal.timeout = function () { return new AbortSignal(); };

  function serialiseBody(body) {
    if (body == null) return null;
    if (typeof body === 'string') return body;
    if (body instanceof g.Uint8Array) return new g.TextDecoder().decode(body);
    if (body.buffer) return new g.TextDecoder().decode(new g.Uint8Array(body.buffer, body.byteOffset || 0, body.byteLength || body.length || 0));
    if (body instanceof g.URLSearchParams) return body.toString();
    if (typeof body === 'object' && typeof body.text === 'function') {
      // Blob/File/FormData: a synchronous read is impossible here, so stringify a best-effort form.
      try { return String(body); } catch (e) { return ''; }
    }
    return String(body);
  }

  g.Headers = g.Headers || Headers;
  g.Request = g.Request || Request;
  g.Response = g.Response || Response;
  g.AbortController = g.AbortController || AbortController;
  g.AbortSignal = g.AbortSignal || AbortSignal;

  g.fetch = function (input, init) {
    var req = new Request(input, init);
    var headerObj = {};
    if (req.headers && req.headers.forEach) req.headers.forEach(function (v, k) { headerObj[k] = v; });
    var payload = JSON.stringify({
      url: req.url,
      method: req.method || 'GET',
      headers: headerObj,
      body: serialiseBody(req.body),
    });
    return g.__nativeFetch(payload).then(function (json) {
      var parsed = JSON.parse(json);
      if (parsed.error) throw new Error(parsed.error);
      return new Response(parsed.body, {
        status: parsed.status,
        statusText: parsed.statusText,
        url: parsed.url || req.url,
        headers: parsed.headers,
      });
    });
  };

  g.__httpShimsReady = true;
})();
