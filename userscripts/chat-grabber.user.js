// ==UserScript==
// @name         Chat Grabber - all chats + files to one ZIP
// @namespace    webgrab
// @version      1.0
// @description  Saves your chats (text + attached and generated files) into ONE zip, one folder per chat.
// @match        https://claude.ai/*
// @match        https://chatgpt.com/*
// @match        https://chat.openai.com/*
// @grant        none
// @run-at       document-end
// ==/UserScript==
(function () {
  'use strict';
  if (window.__chatGrab) return;
  window.__chatGrab = 1;

  var HOST = location.hostname;
  var SITE = /claude\.ai$/.test(HOST) ? 'claude' : 'chatgpt';
  var SITE_NAME = SITE === 'claude' ? 'Claude' : 'ChatGPT';
  var enc = new TextEncoder();
  var cancelled = false;
  var busy = false;
  // Older WebGrab builds cannot stream a file to Downloads; they hold it in memory, so we save several smaller ZIPs.
  var LOW_MEM = !(window.WG && typeof WG.fileOpen === 'function');
  var SPLIT = window.__chatGrabSplit || 25000000;

  // ------------------------------------------------------------------ helpers
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function safeName(s, fallback) {
    s = String(s == null ? '' : s).replace(/[\\\/:*?"<>|\u0000-\u001f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/[. ]+$/, '');
    if (!s) s = fallback || 'Untitled';
    return s.length > 80 ? s.slice(0, 80).trim() : s;
  }

  var MIME_EXT = {
    'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp', 'image/svg+xml': 'svg',
    'application/pdf': 'pdf', 'text/plain': 'txt', 'text/markdown': 'md', 'text/csv': 'csv', 'text/html': 'html',
    'application/json': 'json', 'application/zip': 'zip',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'pptx',
    'audio/mpeg': 'mp3', 'audio/wav': 'wav', 'video/mp4': 'mp4'
  };

  function withExt(name, mime) {
    if (/\.[A-Za-z0-9]{1,6}$/.test(name)) return name;
    var e = MIME_EXT[String(mime || '').split(';')[0]];
    return e ? name + '.' + e : name;
  }

  async function req(url, opts, tries) {
    tries = tries || 3;
    for (var i = 0; i < tries; i++) {
      var r = await fetch(url, Object.assign({ credentials: 'include' }, opts || {}));
      if (r.status === 429 || r.status >= 500) { await sleep(1500 * (i + 1)); continue; }
      if (!r.ok) throw new Error(r.status + ' ' + String(url).split('?')[0]);
      return r;
    }
    throw new Error('Too many retries: ' + String(url).split('?')[0]);
  }

  function fmtTime(t) {
    if (!t) return '';
    var d = typeof t === 'number' ? new Date(t * 1000) : new Date(t);
    return isNaN(d.getTime()) ? '' : d.toISOString().replace('T', ' ').slice(0, 16);
  }

  // ------------------------------------------------------------------ ZIP writer (stored, UTF-8 names)
  var CRC_T = (function () {
    var t = new Uint32Array(256);
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(u8) {
    var c = 0xFFFFFFFF;
    for (var i = 0; i < u8.length; i++) c = CRC_T[(c ^ u8[i]) & 255] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  function dosDT(d) {
    var t = ((d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1)) & 0xFFFF;
    var dt = (((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate()) & 0xFFFF;
    return [t, dt];
  }

  function Zip(sink) { this.sink = sink; this.off = 0; this.cd = []; }

  Zip.prototype._w = async function (u8) {
    await this.sink.write(u8);
    this.off += u8.length;
    if (this.off > 4000000000) throw new Error('ZIP is over 4 GB - use "This chat" for big chats');
  };

  Zip.prototype.add = async function (name, u8) {
    var nb = enc.encode(name);
    var crc = crc32(u8);
    var dt = dosDT(new Date());
    var h = new DataView(new ArrayBuffer(30));
    h.setUint32(0, 0x04034b50, true);
    h.setUint16(4, 20, true);
    h.setUint16(6, 0x0800, true);
    h.setUint16(8, 0, true);
    h.setUint16(10, dt[0], true);
    h.setUint16(12, dt[1], true);
    h.setUint32(14, crc, true);
    h.setUint32(18, u8.length, true);
    h.setUint32(22, u8.length, true);
    h.setUint16(26, nb.length, true);
    h.setUint16(28, 0, true);
    var start = this.off;
    await this._w(new Uint8Array(h.buffer));
    await this._w(nb);
    if (u8.length) await this._w(u8);
    this.cd.push({ nb: nb, crc: crc, size: u8.length, off: start, dt: dt });
    if (this.cd.length >= 65000) throw new Error('Too many files for one ZIP');
  };

  Zip.prototype.finish = async function () {
    var cdStart = this.off, cdSize = 0, i;
    for (i = 0; i < this.cd.length; i++) {
      var e = this.cd[i];
      var c = new DataView(new ArrayBuffer(46));
      c.setUint32(0, 0x02014b50, true);
      c.setUint16(4, 20, true);
      c.setUint16(6, 20, true);
      c.setUint16(8, 0x0800, true);
      c.setUint16(10, 0, true);
      c.setUint16(12, e.dt[0], true);
      c.setUint16(14, e.dt[1], true);
      c.setUint32(16, e.crc, true);
      c.setUint32(20, e.size, true);
      c.setUint32(24, e.size, true);
      c.setUint16(28, e.nb.length, true);
      c.setUint16(30, 0, true);
      c.setUint16(32, 0, true);
      c.setUint16(34, 0, true);
      c.setUint16(36, 0, true);
      c.setUint32(38, 0, true);
      c.setUint32(42, e.off, true);
      await this._w(new Uint8Array(c.buffer));
      await this._w(e.nb);
      cdSize += 46 + e.nb.length;
    }
    var end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true);
    end.setUint16(4, 0, true);
    end.setUint16(6, 0, true);
    end.setUint16(8, this.cd.length, true);
    end.setUint16(10, this.cd.length, true);
    end.setUint32(12, cdSize, true);
    end.setUint32(16, cdStart, true);
    end.setUint16(20, 0, true);
    await this._w(new Uint8Array(end.buffer));
  };

  // ------------------------------------------------------------------ output (streams to Downloads via WebGrab)
  function toB64(u8) {
    return new Promise(function (res, rej) {
      var fr = new FileReader();
      fr.onload = function () { var s = fr.result; res(s.substring(s.indexOf(',') + 1)); };
      fr.onerror = function () { rej(fr.error); };
      fr.readAsDataURL(new Blob([u8]));
    });
  }

  function makeSink(fileName) {
    var CH = 393216; // multiple of 3 so every piece is valid base64 on its own
    var native = !!(window.WG && typeof WG.fileOpen === 'function');
    var parts = [];
    if (native) WG.fileOpen(fileName, 'application/zip');
    return {
      native: native,
      size: 0,
      write: async function (u8) {
        this.size += u8.length;
        if (!native) { parts.push(u8); return; }
        for (var i = 0; i < u8.length; i += CH) {
          WG.fileWrite(await toB64(u8.subarray(i, Math.min(i + CH, u8.length))));
        }
      },
      close: async function () {
        if (native) { WG.fileClose(); return; }
        // older WebGrab build: keep everything in memory and use the blob saver
        var total = 0, k;
        for (k = 0; k < parts.length; k++) total += parts[k].length;
        var all = new Uint8Array(total), p = 0;
        for (k = 0; k < parts.length; k++) { all.set(parts[k], p); p += parts[k].length; }
        WG.blobStart(fileName, 'application/zip');
        for (var i = 0; i < all.length; i += CH) WG.blobChunk(await toB64(all.subarray(i, Math.min(i + CH, all.length))));
        WG.blobDone();
      }
    };
  }

  // ------------------------------------------------------------------ site adapters
  // Both use the same logged-in session you already have in this page.
  var claude = {
    org: null,
    init: async function () {
      var orgs = await (await req('/api/organizations')).json();
      var o = null, i;
      for (i = 0; i < orgs.length; i++) {
        if ((orgs[i].capabilities || []).indexOf('chat') >= 0) { o = orgs[i]; break; }
      }
      o = o || orgs[0];
      if (!o) throw new Error('No Claude organization found - are you logged in?');
      this.org = o.uuid;
    },
    list: async function () {
      var a = await (await req('/api/organizations/' + this.org + '/chat_conversations')).json();
      return a.map(function (c) { return { id: c.uuid, title: c.name, created: c.created_at, updated: c.updated_at }; });
    },
    currentId: function () {
      var m = location.pathname.match(/\/chat\/([0-9a-f-]{36})/);
      return m && m[1];
    },
    get: async function (id) {
      return await (await req('/api/organizations/' + this.org + '/chat_conversations/' + id +
        '?tree=True&rendering_mode=messages&render_all_tools=true')).json();
    },
    normalize: function (conv, c) {
      var self = this;
      var msgs = [];
      (conv.chat_messages || []).forEach(function (m) {
        var role = m.sender === 'human' ? 'user' : 'assistant';
        var text = '';
        var files = [];
        if (m.content && m.content.length) {
          m.content.forEach(function (b) {
            if (b.type === 'text' && b.text) {
              text += (text ? '\n\n' : '') + b.text;
            } else if (b.type === 'tool_use' && b.name === 'artifacts' && b.input && b.input.content) {
              var title = b.input.title || b.input.id || 'artifact';
              var ext = b.input.language || (b.input.type === 'text/html' ? 'html' : b.input.type === 'image/svg+xml' ? 'svg' : 'txt');
              ext = String(ext).replace(/[^a-z0-9]/gi, '').toLowerCase() || 'txt';
              if (ext === 'javascript') ext = 'js';
              if (ext === 'python') ext = 'py';
              text += (text ? '\n\n' : '') + '[Artifact: ' + title + ']';
              files.push({ name: 'artifact - ' + safeName(title, 'artifact') + '.' + ext, inline: b.input.content });
            }
          });
        }
        if (!text) text = m.text || '';
        var seen = {};
        (m.files || []).concat(m.files_v2 || []).forEach(function (f) {
          if (!f || seen[f.file_uuid]) return;
          seen[f.file_uuid] = 1;
          var urls = [
            f.document_asset && f.document_asset.url,
            f.preview_asset && f.preview_asset.url,
            f.preview_url,
            '/api/' + self.org + '/files/' + f.file_uuid + '/preview'
          ].filter(Boolean);
          files.push({
            name: f.file_name || ('file-' + String(f.file_uuid).slice(0, 8)),
            load: async function () {
              for (var i = 0; i < urls.length; i++) {
                try { return await (await req(urls[i], null, 1)).blob(); } catch (e) { /* try next */ }
              }
              return null;
            }
          });
        });
        (m.attachments || []).forEach(function (a) {
          if (a && a.extracted_content != null) files.push({ name: a.file_name || 'attachment.txt', inline: a.extracted_content });
        });
        msgs.push({ role: role, time: m.created_at, text: text, files: files });
      });
      return { title: conv.name || c.title, messages: msgs };
    }
  };

  var gpt = {
    token: null,
    hdr: function () { return { headers: { Authorization: 'Bearer ' + this.token } }; },
    init: async function () {
      var j = await (await req('/api/auth/session')).json();
      this.token = j && j.accessToken;
      if (!this.token) throw new Error('Not logged in to ChatGPT');
    },
    list: async function () {
      var out = [], off = 0, total = 1;
      while (off < total) {
        var j = await (await req('/backend-api/conversations?offset=' + off + '&limit=50&order=updated', this.hdr())).json();
        total = j.total || 0;
        var items = j.items || [];
        if (!items.length) break;
        items.forEach(function (c) { out.push({ id: c.id, title: c.title, created: c.create_time, updated: c.update_time }); });
        off += items.length;
        await sleep(200);
      }
      return out;
    },
    currentId: function () {
      var m = location.pathname.match(/\/c\/([0-9a-f-]{36})/);
      return m && m[1];
    },
    get: async function (id) {
      return await (await req('/backend-api/conversation/' + id, this.hdr())).json();
    },
    fileBlob: async function (fid, convId) {
      var urls = [
        '/backend-api/files/' + fid + '/download',
        '/backend-api/files/download/' + fid + '?conversation_id=' + convId + '&inline=false'
      ];
      for (var i = 0; i < urls.length; i++) {
        try {
          var j = await (await req(urls[i], this.hdr(), 1)).json();
          if (j && j.download_url) {
            var rb = await fetch(j.download_url);
            if (rb.ok) return await rb.blob();
          }
        } catch (e) { /* try next */ }
      }
      return null;
    },
    normalize: function (conv, c) {
      var self = this;
      var chain = [];
      var node = conv.current_node;
      var map = conv.mapping || {};
      while (node && map[node]) { chain.push(map[node]); node = map[node].parent; }
      chain.reverse();
      var msgs = [];
      chain.forEach(function (n) {
        var m = n.message;
        if (!m || !m.author) return;
        var role = m.author.role;
        if (role === 'system') return;
        if (m.metadata && m.metadata.is_visually_hidden_from_conversation) return;
        var content = m.content || {};
        var parts = content.parts || [];
        var text = '';
        var files = [];
        parts.forEach(function (p) {
          if (typeof p === 'string') {
            text += (text ? '\n' : '') + p;
          } else if (p && p.asset_pointer) {
            var fid = String(p.asset_pointer).replace(/^.*:\/\//, '');
            files.push({
              name: 'image-' + fid.slice(0, 12) + '.png',
              load: function () { return self.fileBlob(fid, c.id); }
            });
          }
        });
        if (content.content_type === 'code' && content.text) {
          text = '```' + (content.language && content.language !== 'unknown' ? content.language : '') + '\n' + content.text + '\n```';
        }
        ((m.metadata && m.metadata.attachments) || []).forEach(function (a) {
          if (!a || !a.id) return;
          files.push({ name: a.name || a.id, load: function () { return self.fileBlob(a.id, c.id); } });
        });
        if (role === 'tool' && !files.length) return;
        if (!text && !files.length) return;
        msgs.push({ role: role === 'user' ? 'user' : 'assistant', time: m.create_time, text: text, files: files });
      });
      return { title: conv.title || c.title, messages: msgs };
    }
  };

  var adapter = SITE === 'claude' ? claude : gpt;

  // ------------------------------------------------------------------ building the zip
  function toMarkdown(title, msgs) {
    var o = ['# ' + title, '', '_Exported ' + new Date().toISOString().slice(0, 16).replace('T', ' ') + ' from ' + SITE_NAME + '_', ''];
    msgs.forEach(function (m) {
      o.push('## ' + (m.role === 'user' ? 'You' : 'Assistant') + (m.time ? ' - ' + fmtTime(m.time) : ''));
      o.push('');
      o.push(m.text || '');
      var saved = m.files.filter(function (f) { return f.saved; });
      if (saved.length) {
        o.push('');
        o.push('Files: ' + saved.map(function (f) { return '[' + f.saved + '](files/' + encodeURI(f.saved) + ')'; }).join(', '));
      }
      o.push('');
    });
    return o.join('\n');
  }

  function uniqueIn(used, name) {
    var base = name, k = 2;
    while (used[name.toLowerCase()]) {
      var dot = base.lastIndexOf('.');
      name = dot > 0 ? base.slice(0, dot) + ' (' + k + ')' + base.slice(dot) : base + ' (' + k + ')';
      k++;
    }
    used[name.toLowerCase()] = 1;
    return name;
  }

  async function saveChat(zip, root, c, usedFolders, log) {
    var conv = await adapter.get(c.id);
    var norm = adapter.normalize(conv, c);
    var folderName = uniqueIn(usedFolders, safeName(norm.title, 'Untitled chat'));
    var folder = root + '/' + folderName;
    var usedFiles = {};
    for (var i = 0; i < norm.messages.length; i++) {
      var files = norm.messages[i].files;
      for (var j = 0; j < files.length; j++) {
        if (cancelled) break;
        var f = files[j];
        try {
          var data, mime = '';
          if (f.inline != null) {
            data = enc.encode(String(f.inline));
          } else {
            var blob = await f.load();
            if (!blob) { log.push('[' + folderName + '] could not download: ' + f.name); continue; }
            if (LOW_MEM && blob.size > 30000000) {
              log.push('[' + folderName + '] ' + f.name + ' is ' + Math.round(blob.size / 1048576) + ' MB - too big for this WebGrab build. Install the updated WebGrab to save big files.');
              continue;
            }
            mime = blob.type;
            data = new Uint8Array(await blob.arrayBuffer());
          }
          var nm = uniqueIn(usedFiles, withExt(safeName(f.name, 'file'), mime));
          await zip.add(folder + '/files/' + nm, data);
          f.saved = nm;
        } catch (e) {
          log.push('[' + folderName + '] ' + f.name + ': ' + e.message);
        }
      }
    }
    await zip.add(folder + '/chat.md', enc.encode(toMarkdown(norm.title || folderName, norm.messages)));
    await zip.add(folder + '/chat.json', enc.encode(JSON.stringify(conv, null, 1)));
  }

  // Fallback when the site's internal API is not available: save what is visible on screen.
  async function saveVisible(zip, root, log) {
    var main = document.querySelector('main') || document.body;
    var title = safeName(document.title, 'Chat');
    var folder = root + '/' + title;
    await zip.add(folder + '/chat.txt', enc.encode(main.innerText || ''));
    var used = {};
    var els = main.querySelectorAll('img[src], a[download], a[href$=".pdf"], a[href$=".zip"], a[href$=".csv"], a[href$=".xlsx"], a[href$=".docx"]');
    for (var i = 0; i < els.length && !cancelled; i++) {
      var u = els[i].src || els[i].href;
      if (!u || !/^(https?:|blob:|data:)/.test(u)) continue;
      try {
        var b = await (await req(u, null, 1)).blob();
        if (b.size < 2000 && /^image\//.test(b.type)) continue; // skip tiny icons
        var nm = uniqueIn(used, withExt(safeName((u.split('?')[0].split('/').pop() || 'file'), 'file'), b.type));
        await zip.add(folder + '/files/' + nm, new Uint8Array(await b.arrayBuffer()));
      } catch (e) { log.push('visible file ' + u.slice(0, 80) + ': ' + e.message); }
    }
  }

  // ------------------------------------------------------------------ run
  async function run(all) {
    if (busy) return;
    busy = true;
    cancelled = false;
    var sink = null, zip = null, log = [];
    try {
      var d = new Date();
      var p2 = function (n) { return (n < 10 ? '0' : '') + n; };
      var stamp = d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate()) + '_' + p2(d.getHours()) + p2(d.getMinutes());
      var root = SITE_NAME + '_chats_' + stamp;
      var chats = null, apiOk = true;
      setStatus('Connecting...');
      try {
        await adapter.init();
        if (all) {
          setStatus('Listing all chats...');
          chats = await adapter.list();
        } else {
          var id = adapter.currentId();
          if (!id) throw new Error('Open a chat first, or tap All chats');
          chats = [{ id: id, title: document.title }];
        }
      } catch (e) {
        if (all) throw e;
        apiOk = false;
        log.push('API not available (' + e.message + '); saved the visible page instead.');
      }
      if (apiOk && !chats.length) throw new Error('No chats found');
      var part = 1;
      var zipName = function () { return LOW_MEM ? root + '_part' + part + '.zip' : root + '.zip'; };
      sink = makeSink(zipName());
      zip = new Zip(sink);
      if (!apiOk) {
        setStatus('Saving what is on screen...');
        await saveVisible(zip, root, log);
      } else {
        var used = {};
        for (var i = 0; i < chats.length; i++) {
          if (cancelled) break;
          setStatus('Chat ' + (i + 1) + ' / ' + chats.length + ': ' + safeName(chats[i].title, 'Untitled'));
          try { await saveChat(zip, root, chats[i], used, log); }
          catch (e) { log.push('[' + safeName(chats[i].title, chats[i].id) + '] ' + e.message); }
          if (LOW_MEM && sink.size > SPLIT && i < chats.length - 1) {
            await zip.finish();
            await sink.close();
            part++;
            sink = makeSink(zipName());
            zip = new Zip(sink);
          }
          await sleep(150);
        }
      }
      if (log.length) await zip.add(root + '/_notes.txt', enc.encode(log.join('\n')));
      await zip.finish();
      await sink.close();
      setStatus((cancelled ? 'Stopped - partial ZIP saved' : 'Done - saved to Downloads') +
        (LOW_MEM && part > 1 ? ' as ' + part + ' ZIP parts (update WebGrab for one ZIP)' : '') +
        (log.length ? ' (' + log.length + ' notes inside)' : ''));
    } catch (e) {
      var msg = e && e.message ? e.message : String(e);
      if (/blobChunk|blobStart|blobDone|Java exception/i.test(msg)) msg += ' - this WebGrab build is out of memory for this size. Install the updated WebGrab, or save "This chat" only.';
      setStatus('Failed: ' + msg);
    } finally {
      busy = false;
    }
  }

  // ------------------------------------------------------------------ small floating panel
  var statusEl = null;
  function setStatus(t) { if (statusEl) statusEl.textContent = t; }

  function buildUi() {
    var wrap = document.createElement('div');
    wrap.style.cssText = 'position:fixed;left:10px;bottom:90px;z-index:2147483647;font:13px sans-serif;color:#222;';
    var fab = document.createElement('button');
    fab.textContent = '\uD83D\uDCBE';
    fab.title = 'Chat Grabber';
    fab.style.cssText = 'width:46px;height:46px;border-radius:23px;border:0;background:#1e88e5;color:#fff;font-size:22px;box-shadow:0 2px 8px #0005;';
    var panel = document.createElement('div');
    panel.style.cssText = 'display:none;margin-bottom:8px;padding:10px;background:#fff;border-radius:12px;box-shadow:0 2px 12px #0006;max-width:260px;';
    function btn(label, fn, bg) {
      var b = document.createElement('button');
      b.textContent = label;
      b.style.cssText = 'display:block;width:100%;margin:4px 0;padding:10px;border:0;border-radius:8px;background:' + bg + ';color:#fff;font-size:14px;';
      b.onclick = fn;
      return b;
    }
    var head = document.createElement('div');
    head.textContent = 'Save chats + files as ONE zip (one folder per chat)';
    head.style.cssText = 'font-weight:600;margin-bottom:6px;';
    statusEl = document.createElement('div');
    statusEl.textContent = 'Ready';
    statusEl.style.cssText = 'margin-top:6px;word-break:break-word;';
    panel.appendChild(head);
    panel.appendChild(btn('\uD83D\uDCC4 This chat', function () { run(false); }, '#1e88e5'));
    panel.appendChild(btn('\uD83D\uDCE6 All chats', function () { run(true); }, '#43a047'));
    panel.appendChild(btn('Stop', function () { cancelled = true; setStatus('Stopping...'); }, '#757575'));
    panel.appendChild(statusEl);
    fab.onclick = function () { panel.style.display = panel.style.display === 'none' ? 'block' : 'none'; };
    wrap.appendChild(panel);
    wrap.appendChild(fab);
    (document.body || document.documentElement).appendChild(wrap);
  }

  buildUi();

  // hook for testing outside a browser
  if (window.__chatGrabTest) window.__chatGrabTest({ Zip: Zip, crc32: crc32, run: run, makeSink: makeSink, safeName: safeName });
})();
