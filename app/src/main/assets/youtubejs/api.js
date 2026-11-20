/*
 * AURALIS YouTube.js provider API.
 *
 * Evaluated AFTER the youtubei.js bundle, which assigns its module namespace to globalThis.__YTJS.
 * The bundle is an ES module whose trailing `export { ... }` block the Kotlin loader rewrites into
 * that global assignment, so only the four globals below are needed here.
 *
 * Everything is async and defensive: a failure rejects the returned promise, which the Kotlin
 * provider treats as "this provider did not produce a URL" so the race simply carries on.
 */
(function () {
  'use strict';
  var g = globalThis;

  // Client ids as youtubei.js 18.3.0 names them (see Constants.CLIENTS). Verified 2026-11 against a
  // live player response: IOS hands out direct audio URLs, YTMUSIC hands out signature ciphers the
  // Player can decipher, while ANDROID/WEB/MWEB come back with URL-less formats (PO-token stripping).
  var CLIENT_ORDER = ['IOS', 'YTMUSIC', 'ANDROID', 'WEB'];
  var innertubePromise = null;

  function createInnertube() {
    return g.__YTJS.Innertube.create({
      retrieve_player: true,
      generate_session_locally: true,
      lang: 'en',
      location: 'US',
    });
  }

  function getInnertube() {
    if (!innertubePromise) {
      innertubePromise = createInnertube().catch(function (e) {
        innertubePromise = null;
        throw e;
      });
    }
    return innertubePromise;
  }

  function usable(f) {
    return !!(f && (f.url || f.signature_cipher || f.cipher));
  }

  /** True when this info carries at least one audio format we can turn into a URL. */
  function hasUsableAudio(info) {
    if (!info || !info.streaming_data) return false;
    var sd = info.streaming_data;
    var adaptive = sd.adaptive_formats || [];
    for (var i = 0; i < adaptive.length; i++) {
      if (adaptive[i].has_audio && !adaptive[i].has_video && usable(adaptive[i])) return true;
    }
    var progressive = sd.formats || [];
    for (var j = 0; j < progressive.length; j++) {
      if (progressive[j].has_audio && usable(progressive[j])) return true;
    }
    return false;
  }

  function infoFor(yt, videoId) {
    // Try the clients that historically hand out direct audio URLs first, then fall back. A client
    // that answers but strips every URL (the PO-token case) is skipped rather than accepted.
    var i = 0;
    function attempt() {
      if (i >= CLIENT_ORDER.length) return Promise.reject(new Error('No client returned usable stream info'));
      var client = CLIENT_ORDER[i++];
      return yt.getBasicInfo(videoId, { client: client }).then(function (info) {
        if (hasUsableAudio(info)) return info;
        return attempt();
      }).catch(function () { return attempt(); });
    }
    return attempt();
  }

  function pickAudio(info, targetKbps) {
    var sd = info.streaming_data || {};
    var all = (sd.adaptive_formats || []);
    var audioOnly = all.filter(function (f) {
      return f.has_audio && !f.has_video && usable(f);
    });
    if (!audioOnly.length) {
      // Some clients only expose progressive formats; keep the audio track from those.
      audioOnly = (sd.formats || []).filter(function (f) { return f.has_audio && usable(f); });
    }
    if (!audioOnly.length) return null;
    var opus = audioOnly.filter(function (f) { return /opus/i.test(f.mime_type || ''); });
    var pool = opus.length ? opus : audioOnly;
    var sorted = pool.slice().sort(function (a, b) { return (a.bitrate || 0) - (b.bitrate || 0); });
    if (!targetKbps || targetKbps <= 0) return sorted[sorted.length - 1];
    var ceiling = targetKbps * 1000;
    var atOrBelow = sorted.filter(function (f) { return (f.bitrate || 0) > 0 && (f.bitrate || 0) <= ceiling; });
    return atOrBelow.length ? atOrBelow[atOrBelow.length - 1] : sorted[0];
  }

  function mapSong(item) {
    try {
      var title = '';
      if (item.title) title = typeof item.title === 'string' ? item.title : (item.title.toString ? String(item.title) : '');
      var artist = 'Unknown artist';
      if (item.artists && item.artists.length) {
        artist = item.artists.map(function (a) { return a.name || ''; }).filter(Boolean).join(', ') || artist;
      } else if (item.author) {
        artist = item.author.name || item.author || artist;
      }
      var album = '';
      var albumId = null;
      if (item.album && item.album.name) { album = item.album.name; albumId = item.album.id || null; }
      var durationMs = 0;
      if (item.duration && item.duration.seconds) durationMs = item.duration.seconds * 1000;
      var thumb = null;
      var thumbs = item.thumbnail || item.thumbnails;
      if (thumbs && thumbs.length) thumb = thumbs[thumbs.length - 1].url;
      return {
        videoId: item.id || '',
        title: title,
        artist: artist,
        album: album,
        albumId: albumId,
        durationMs: durationMs,
        thumbnailUrl: thumb,
      };
    } catch (e) {
      return null;
    }
  }

  g.__auralis = {
    version: 'youtubei.js/18.3.0',

    warmup: function () {
      return getInnertube().then(function () { return true; });
    },

    resolveAudio: function (videoId, targetKbps) {
      var yt;
      return getInnertube().then(function (instance) {
        yt = instance;
        return infoFor(yt, videoId);
      }).then(function (info) {
        var chosen = pickAudio(info, targetKbps);
        if (!chosen) return 'null';
        return chosen.decipher(yt.session.player).then(function (url) {
          if (!url || typeof url !== 'string') return 'null';
          return JSON.stringify({ url: url, bitrate: chosen.bitrate || 0, mimeType: chosen.mime_type || '' });
        });
      });
    },

    metadata: function (videoId) {
      var yt;
      return getInnertube().then(function (instance) {
        yt = instance;
        return yt.getBasicInfo(videoId, { client: 'ANDROID' });
      }).then(function (info) {
        var b = info.basic_info || {};
        var thumb = null;
        if (b.thumbnail && b.thumbnail.length) thumb = b.thumbnail[b.thumbnail.length - 1].url;
        return JSON.stringify({
          videoId: videoId,
          title: b.title || '',
          artist: b.author || 'Unknown artist',
          album: '',
          durationMs: (b.duration || 0) * 1000,
          thumbnailUrl: thumb,
        });
      });
    },

    search: function (query) {
      var yt;
      return getInnertube().then(function (instance) {
        yt = instance;
        return yt.music.search(query, { type: 'song' });
      }).then(function (res) {
        var items = [];
        if (res && res.songs && res.songs.contents) items = res.songs.contents;
        else if (res && res.contents) items = res.contents;
        var out = [];
        for (var i = 0; i < items.length; i++) {
          var mapped = mapSong(items[i]);
          if (mapped && mapped.videoId) out.push(mapped);
        }
        return JSON.stringify(out);
      });
    },
  };

  g.__auralisApiReady = true;
})();
