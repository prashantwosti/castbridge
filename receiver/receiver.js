/*
 * CastBridge receiver. Runs in the Samsung TV browser (or as a Tizen web app).
 *
 * Wire protocol (phone -> TV, over one WebSocket):
 *   text   {"type":"config","audio":true|false}   (re)start the decoders
 *          {"type":"stopped"}                     phone ended the session
 *   binary [u8 kind][u32 BE durationUs][payload]
 *          kind 1 = H.264 Annex-B access unit
 *          kind 2 = PCM, 48 kHz stereo signed 16-bit little-endian
 *
 * Video goes through MSE (jmuxer); audio is scheduled directly with Web Audio. Keeping
 * them separate matters: the phone's encoder sends no frames while the screen is static,
 * and a combined A/V MediaSource would stall the sound waiting for video.
 */
(function () {
  'use strict';

  // Where the QR code on the waiting screen sends people for the phone app. A fixed asset name
  // keeps this link pointing at the newest GitHub release. Swap for the Play Store link later.
  var PHONE_APP_URL = 'https://github.com/prashantwosti/castbridge/releases/latest/download/castbridge-release.apk';

  var KIND_VIDEO = 1;
  var KIND_AUDIO = 2;
  // Live-edge control (seconds). Seeking stalls a TV's hardware decoder and Tizen ignores
  // playbackRate, so a backlog is drained by stamping incoming frames shorter (CATCH_UP)
  // until the playhead is back near the newest frame. Only a huge backlog triggers a jump.
  // The Q60A's player stalls every few seconds if kept under ~0.3 s of buffer.
  var TARGET_LAG = 0.35;
  var CATCH_UP = 0.85;
  var SEEK_LAG = 1.5;
  var seeks = 0;
  var lastRestartAt = 0;
  var stalls = 0;

  var SAMPLE_RATE = 48000;
  // Audio is scheduled this far ahead of "now" (seconds), enough to ride out Wi-Fi jitter.
  // A backlog is drained by playing a few percent fast (barely audible); chunks are dropped
  // only when far behind, since every gap is an audible click.
  var AUDIO_AHEAD = 0.1;
  var AUDIO_DRAIN_RATE = 1.03;
  var AUDIO_MAX_AHEAD = 0.5;

  var video = document.getElementById('video');
  var statusEl = document.getElementById('status');
  var messageEl = document.getElementById('message');
  var statsEl = document.getElementById('stats');

  var params = parseQuery(location.search);
  // Served by the phone: stream from the same host. Installed as a TV app (file://): find
  // the phone on the LAN, see findPhone().
  var installed = !/^https?:$/.test(location.protocol);
  var fixedWsUrl = params.ws || window.CASTBRIDGE_WS || (installed ? null : 'ws://' + location.host + '/stream');
  var wsUrl = fixedWsUrl;
  var PHONE_PORT = 8080;
  var failures = 0;
  var lastMessageAt = 0;
  var LINK_TIMEOUT = 3500;   // ms without any message (the phone pings every 1 s)
  var CONNECT_TIMEOUT = 4000;
  if (params.stats) statsEl.style.display = 'block';

  var videoMux = null;
  var videoReady = false;
  var pendingVideo = [];
  var audioOn = false;
  var audioCtx = null;
  var audioNext = 0;
  var audioPeak = 0; // loudest sample since the last debug report
  var seenKeyframe = false;
  var socket = null;
  var reconnectTimer = null;
  var bytes = 0;
  var frames = 0;
  // jmuxer truncates durations to whole ms; carry the remainder so the timeline doesn't drift.
  var carry = 0;

  function parseQuery(q) {
    var out = {};
    q.replace(/^\?/, '').split('&').forEach(function (kv) {
      if (!kv) return;
      var p = kv.split('=');
      out[decodeURIComponent(p[0])] = decodeURIComponent(p[1] || '1');
    });
    return out;
  }

  function showStatus(text) {
    messageEl.textContent = text;
    statusEl.className = '';
  }

  function hideStatus() {
    statusEl.className = 'hidden';
  }

  // jmuxer drops anything fed before its MediaSource opens, which would lose the opening
  // keyframe, so frames are queued until onReady.
  function makeVideoMuxer() {
    video.removeAttribute('src');
    video.load();
    videoReady = false;
    pendingVideo = [];
    var mux = new JMuxer({
      node: video,
      mode: 'video',
      flushingTime: 0,
      maxDelay: 60000, // jmuxer's own catch-up seeks; chaseLiveEdge() handles this instead
      clearBuffer: true,
      fps: 60,
      debug: !!params.debug,
      onReady: function () {
        // Deferred: jmuxer fires this before it finishes setting up its buffers.
        setTimeout(function () {
          if (mux !== videoMux) return;
          videoReady = true;
          pendingVideo.forEach(function (f) { mux.feed(f); });
          pendingVideo = [];
        }, 0);
      },
      onError: function (e) { console.warn('jmuxer error ' + JSON.stringify(e)); }
    });
    play(video);
    return mux;
  }

  function feedVideo(frame) {
    if (videoReady) videoMux.feed(frame);
    else pendingVideo.push(frame);
  }

  function destroy(mux) {
    if (mux) {
      try { mux.destroy(); } catch (e) { /* ignore */ }
    }
  }

  // Blanks the screen so the last frame doesn't linger after the phone stops.
  function clearStream() {
    setScreenSaver(true);
    destroy(videoMux);
    videoMux = null;
    videoReady = false;
    pendingVideo = [];
    audioOn = false;
    video.removeAttribute('src');
    video.load();
  }

  function resetStream(withAudio) {
    destroy(videoMux);
    seenKeyframe = false;
    carry = 0;
    videoMux = makeVideoMuxer();
    audioOn = withAudio;
    if (withAudio) startAudio();
  }

  function startAudio() {
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    if (!audioCtx) {
      try {
        audioCtx = new Ctx({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' });
      } catch (e) {
        audioCtx = new Ctx(); // older engines: resampled by createBuffer below
      }
    }
    if (audioCtx.state === 'suspended') audioCtx.resume();
    audioNext = 0;
  }

  function videoLag() {
    var b = video.buffered;
    return b.length ? b.end(b.length - 1) - video.currentTime : 0;
  }

  function playPcm(bytes) {
    if (!audioCtx || audioCtx.state !== 'running') return;
    var samples = bytes.byteLength >> 2;
    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    var buffer = audioCtx.createBuffer(2, samples, SAMPLE_RATE);
    var left = buffer.getChannelData(0);
    var right = buffer.getChannelData(1);
    for (var i = 0; i < samples; i++) {
      left[i] = view.getInt16(i * 4, true) / 32768;
      right[i] = view.getInt16(i * 4 + 2, true) / 32768;
      if (left[i] > audioPeak) audioPeak = left[i];
    }
    var now = audioCtx.currentTime;
    var ahead = audioNext - now;
    if (ahead < 0.005) {
      audioNext = now + AUDIO_AHEAD; // underrun: restart with a fresh cushion
    } else if (ahead > AUDIO_MAX_AHEAD) {
      return; // backlog after a network burst: drop this chunk to get back to low delay
    }
    var rate = ahead > AUDIO_AHEAD + 0.05 ? AUDIO_DRAIN_RATE : 1;
    var src = audioCtx.createBufferSource();
    src.buffer = buffer;
    src.playbackRate.value = rate;
    src.connect(audioCtx.destination);
    src.start(audioNext);
    audioNext += buffer.duration / rate;
  }

  function play(node) {
    var p = node.play();
    if (p && p.catch) p.catch(function () { /* retried by chaseLiveEdge */ });
  }

  function chaseLiveEdge(node) {
    if (!node.buffered.length || node.seeking) return;
    var last = node.buffered.length - 1;
    var start = node.buffered.start(last);
    var end = node.buffered.end(last);
    var lag = end - node.currentTime;
    if (lag > SEEK_LAG) {
      // Far behind. Seeking doesn't help: the TV's decoder snaps back to the previous keyframe,
      // which can be seconds old. Restart the decoder and ask the phone for a fresh keyframe.
      if (Date.now() - lastRestartAt > 3000) {
        lastRestartAt = Date.now();
        seeks++;
        resetStream(audioOn);
        try { socket.send('keyframe'); } catch (e) { /* reconnecting anyway */ }
      }
    } else if (node.currentTime < start) {
      // Right after a reset the first frames can start past 0.
      node.currentTime = start;
      seeks++;
    }
    if (node.paused) play(node);
  }

  function hasIdr(data) {
    for (var i = 0; i + 3 < data.length; i++) {
      if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1 && (data[i + 3] & 0x1f) === 5) return true;
    }
    return false;
  }

  // jmuxer only emits a frame once it sees the start of the next one, so a static screen
  // (one lone keyframe) would never show. Two trailing access-unit delimiters end the frame
  // now; the second stays buffered and harmlessly prefixes the next frame.
  var AUD_PAIR = new Uint8Array([0, 0, 0, 1, 9, 0xf0, 0, 0, 0, 1, 9, 0xf0]);
  function withFlush(payload) {
    var out = new Uint8Array(payload.length + AUD_PAIR.length);
    out.set(payload, 0);
    out.set(AUD_PAIR, payload.length);
    return out;
  }

  function wholeMs(ms) {
    carry += ms;
    var whole = Math.floor(carry);
    carry -= whole;
    return whole;
  }

  function onBinary(buf) {
    if (!videoMux) return;
    var view = new DataView(buf);
    var kind = view.getUint8(0);
    var durationMs = view.getUint32(1) / 1000;
    var payload = new Uint8Array(buf, 5);
    bytes += buf.byteLength;
    if (kind === KIND_VIDEO) {
      // Decoding must start on a keyframe; drop everything before the first IDR.
      if (!seenKeyframe) {
        if (!hasIdr(payload)) return;
        seenKeyframe = true;
      }
      frames++;
      var scale = videoLag() > TARGET_LAG ? CATCH_UP : 1;
      feedVideo({ video: withFlush(payload), duration: wholeMs(durationMs * scale) });
      hideStatus();
      setScreenSaver(false);
    } else if (kind === KIND_AUDIO && audioOn) {
      playPcm(payload);
    }
  }

  function onText(text) {
    var msg;
    try { msg = JSON.parse(text); } catch (e) { return; }
    if (msg.type === 'config') {
      resetStream(!!msg.audio);
      showStatus('Starting stream…');
    } else if (msg.type === 'stopped') {
      clearStream();
      showStatus('Phone stopped casting');
    }
  }

  function storage(key, value) {
    try {
      if (value === undefined) return localStorage.getItem(key);
      localStorage.setItem(key, value);
    } catch (e) { /* storage unavailable */ }
    return null;
  }

  function ownIp(cb) {
    try {
      tizen.systeminfo.getPropertyValue('WIFI_NETWORK', function (w) {
        if (w && w.ipAddress && w.ipAddress !== '0.0.0.0') return cb(w.ipAddress);
        tizen.systeminfo.getPropertyValue('ETHERNET_NETWORK', function (e) { cb(e && e.ipAddress); },
          function () { cb(null); });
      }, function () { cb(null); });
    } catch (e) {
      cb(null);
    }
  }

  // Resolves with "ip:port" of a phone running CastBridge, via its /castbridge marker.
  function probe(host, timeoutMs, cb) {
    var x = new XMLHttpRequest();
    var done = false;
    function finish(ok) { if (!done) { done = true; cb(ok); } }
    x.open('GET', 'http://' + host + '/castbridge');
    x.timeout = timeoutMs;
    x.onload = function () { finish(x.status === 200 && x.responseText.indexOf('castbridge') === 0); };
    x.onerror = x.ontimeout = function () { finish(false); };
    x.send();
  }

  // Tries the last phone first, then every address on the TV's /24, 32 at a time.
  function findPhone(cb) {
    if (suspended) return;
    showStatus('Looking for your phone… Start casting in the CastBridge app.');
    var last = storage('phone');
    var scan = function () {
      ownIp(function (ip) {
        if (!ip) { setTimeout(function () { findPhone(cb); }, 3000); return; }
        var prefix = ip.split('.').slice(0, 3).join('.');
        var hosts = [];
        for (var i = 1; i < 255; i++) {
          if (prefix + '.' + i !== ip) hosts.push(prefix + '.' + i + ':' + PHONE_PORT);
        }
        var next = 0, active = 0, found = false;
        var pump = function () {
          while (!found && active < 32 && next < hosts.length) {
            (function (host) {
              active++;
              probe(host, 1500, function (ok) {
                active--;
                if (ok && !found) { found = true; storage('phone', host); cb(host); return; }
                if (!found) {
                  if (next < hosts.length) pump();
                  else if (active === 0) setTimeout(function () { findPhone(cb); }, 2000);
                }
              });
            })(hosts[next++]);
          }
        };
        pump();
      });
    };
    if (last) probe(last, 1500, function (ok) { if (ok) cb(last); else scan(); });
    else scan();
  }

  function connect() {
    clearTimeout(reconnectTimer);
    if (suspended) return;
    if (!wsUrl) {
      findPhone(function (host) { wsUrl = 'ws://' + host + '/stream'; connect(); });
      return;
    }
    showStatus(failures ? 'Reconnecting to your phone…' : 'Connecting to phone…');
    var ws = new WebSocket(wsUrl);
    socket = ws;
    ws.binaryType = 'arraybuffer';
    // A phone that left the network makes connect() hang instead of failing fast.
    var openTimer = setTimeout(function () { if (ws.readyState !== WebSocket.OPEN) ws.close(); }, CONNECT_TIMEOUT);
    ws.onopen = function () {
      clearTimeout(openTimer);
      failures = 0;
      lastMessageAt = Date.now();
      showStatus('Connected. Waiting for video…');
    };
    ws.onmessage = function (e) {
      lastMessageAt = Date.now();
      if (typeof e.data === 'string') onText(e.data);
      else onBinary(e.data);
    };
    ws.onclose = function () {
      clearTimeout(openTimer);
      clearStream();
      showStatus('Phone disconnected. Retrying…');
      // The phone may have stopped or changed address: rescan after a few failed retries.
      if (++failures >= 3 && !fixedWsUrl) { wsUrl = null; failures = 0; }
      reconnectTimer = setTimeout(connect, 2000);
    };
    ws.onerror = function () { try { ws.close(); } catch (e) { /* ignore */ } };
  }

  setInterval(function () { chaseLiveEdge(video); }, 200);

  // Link watchdog. The phone pings every second, so silence means the connection is dead even
  // though the socket still looks open (e.g. the phone switched Wi-Fi). Drop it, go back to the
  // waiting screen and reconnect; findPhone() rescans if the phone's address changed.
  setInterval(function () {
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    try { socket.send('ping'); } catch (e) { /* closing */ }
    if (Date.now() - lastMessageAt > LINK_TIMEOUT) {
      var dead = socket;
      dead.onclose = null;
      dead.close();
      socket = null;
      clearStream();
      showStatus('Connection to your phone was lost. Reconnecting…');
      if (++failures >= 3 && !fixedWsUrl) { wsUrl = null; failures = 0; }
      reconnectTimer = setTimeout(connect, 1000);
    }
  }, 1000);
  video.addEventListener('waiting', function () { stalls++; });

  // Debug builds (window.CASTBRIDGE_REPORT, set by tizen/build.sh) post diagnostics to a
  // dev machine, since the TV's own console is hard to reach.
  function report(line) {
    if (!window.CASTBRIDGE_REPORT) return;
    try {
      var x = new XMLHttpRequest();
      x.open('POST', window.CASTBRIDGE_REPORT);
      x.send(line);
    } catch (e) { /* ignore */ }
  }
  function audioDebug() {
    if (!audioCtx) return ' audio=none';
    return ' audio=' + audioCtx.state + ' sr=' + audioCtx.sampleRate + ' base=' + audioCtx.baseLatency +
      ' out=' + audioCtx.outputLatency + ' ahead=' + Math.round((audioNext - audioCtx.currentTime) * 1000) +
      'ms peak=' + audioPeak.toFixed(3);
  }

  function msState() {
    try {
      var ms = videoMux && videoMux.mediaSource;
      var track = videoMux.remuxController.tracks.video.mp4track;
      return ' ms=' + (ms ? ms.readyState + '/' + ms.sourceBuffers.length : '-') + ' codec=' + track.codec +
        ' track=' + track.width + 'x' + track.height;
    } catch (e) {
      return ' ms=? ' + e;
    }
  }

  if (window.CASTBRIDGE_REPORT) {
    window.onerror = function (msg, src, line) { report('onerror ' + msg + ' @' + src + ':' + line); };
    ['log', 'warn', 'error'].forEach(function (level) {
      var orig = console[level];
      console[level] = function () {
        report(level + ' ' + Array.prototype.join.call(arguments, ' '));
        if (orig) orig.apply(console, arguments);
      };
    });
    var codecs = ['avc1.42E01E', 'avc1.4D401F', 'avc1.640028', 'avc1.64002A', 'avc1.640033'];
    report('ua ' + navigator.userAgent + ' | MSE ' + !!window.MediaSource + ' | ' + codecs.map(function (c) {
      return c + '=' + (window.MediaSource ? MediaSource.isTypeSupported('video/mp4; codecs="' + c + '"') : '-');
    }).join(' '));
    setInterval(function () {
      var b = video.buffered;
      report('state ws=' + wsUrl + ' key=' + seenKeyframe + ' ready=' + videoReady + ' pending=' + pendingVideo.length +
        ' rs=' + video.readyState + ' ns=' + video.networkState + ' paused=' + video.paused + ' t=' + video.currentTime.toFixed(2) +
        ' buf=' + (b.length ? b.start(0).toFixed(2) + '-' + b.end(b.length - 1).toFixed(2) + '(' + b.length + ')' : 'none') +
        ' size=' + video.videoWidth + 'x' + video.videoHeight + ' err=' + (video.error ? video.error.code + ':' + video.error.message : '-') +
        msState() + ' lag=' + Math.round(videoLag() * 1000) + 'ms seeks=' + seeks + ' stalls=' + stalls + audioDebug());
      audioPeak = 0;
    }, 2000);
  }

  setInterval(function () {
    if (statsEl.style.display !== 'block') return;
    var lag = video.buffered.length ? ((video.buffered.end(video.buffered.length - 1) - video.currentTime) * 1000 | 0) : 0;
    var audioState = !audioOn ? 'off' : !audioCtx ? 'unsupported' : audioCtx.state !== 'running' ? 'blocked' :
      (Math.max(0, audioNext - audioCtx.currentTime) * 1000 | 0) + 'ms';
    statsEl.textContent = frames + ' fps  ' + (bytes * 8 / 1e6).toFixed(1) + ' Mbps  video lag ' + lag +
      'ms  audio ' + audioState + '  ' + video.videoWidth + 'x' + video.videoHeight;
    frames = 0;
    bytes = 0;
  }, 1000);

  // --- Samsung TV platform behaviour, required for store certification ---------------------

  var KEY_LEFT = 37;
  var KEY_RIGHT = 39;
  var KEY_ENTER = 13;
  var KEY_RETURN = 10009;
  var screenSaverOn = true;

  // No screensaver while the phone's picture is showing; back on when it stops.
  function setScreenSaver(on) {
    if (on === screenSaverOn) return;
    screenSaverOn = on;
    try {
      var state = webapis.appcommon.AppCommonScreenSaverState;
      webapis.appcommon.setScreenSaver(on ? state.SCREEN_SAVER_ON : state.SCREEN_SAVER_OFF);
    } catch (e) { /* not on a Samsung TV */ }
  }

  // Return on the app's (only) screen asks before exiting, per Samsung's Return key policy.
  var exitDialog = document.getElementById('exit-dialog');
  var exitButtons = [document.getElementById('exit-yes'), document.getElementById('exit-no')];
  var exitFocus = 1;

  function focusExitButton(i) {
    exitFocus = i;
    exitButtons.forEach(function (b, j) { b.className = j === i ? 'focused' : ''; });
  }
  function openExitDialog() {
    exitDialog.classList.remove('hidden');
    focusExitButton(1); // default to "No" so a stray Enter doesn't quit
  }
  function closeExitDialog() { exitDialog.classList.add('hidden'); }
  function exitApp() {
    try { tizen.application.getCurrentApplication().exit(); } catch (e) { /* not on a TV */ }
  }
  exitButtons[0].addEventListener('click', exitApp);
  exitButtons[1].addEventListener('click', closeExitDialog);

  // Any remote key press (or click) also counts as the user gesture some browsers need for audio.
  document.addEventListener('click', function () { if (audioOn) startAudio(); });
  document.addEventListener('keydown', function (e) {
    if (audioOn) startAudio();
    var dialogOpen = !exitDialog.classList.contains('hidden');
    if (e.keyCode === KEY_RETURN) {
      if (!window.tizen) return;
      e.preventDefault();
      if (dialogOpen) closeExitDialog();
      else openExitDialog();
    } else if (dialogOpen && (e.keyCode === KEY_LEFT || e.keyCode === KEY_RIGHT)) {
      focusExitButton(1 - exitFocus);
    } else if (dialogOpen && e.keyCode === KEY_ENTER) {
      if (exitFocus === 0) exitApp();
      else closeExitDialog();
    }
  });

  // Multitasking: when the user switches away, drop the stream and stop scanning; resume on return.
  var suspended = false;
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      suspended = true;
      clearTimeout(reconnectTimer);
      if (socket) {
        socket.onclose = null;
        socket.close();
        socket = null;
      }
      clearStream();
      if (audioCtx) audioCtx.suspend();
    } else {
      suspended = false;
      failures = 0;
      if (audioCtx) audioCtx.resume();
      connect();
    }
  });

  // Network loss must be announced with a popup.
  var networkPopup = document.getElementById('network-popup');
  function setNetworkDown(down) { networkPopup.classList.toggle('hidden', !down); }
  window.addEventListener('offline', function () { setNetworkDown(true); });
  window.addEventListener('online', function () { setNetworkDown(false); });
  try {
    var ns = webapis.network.NetworkState;
    webapis.network.addNetworkStateChangeListener(function (value) {
      if (value === ns.GATEWAY_DISCONNECTED) setNetworkDown(true);
      else if (value === ns.GATEWAY_CONNECTED) setNetworkDown(false);
    });
    setNetworkDown(!webapis.network.isConnectedToGateway());
  } catch (e) {
    setNetworkDown(!navigator.onLine);
  }

  // QR code on the waiting screen pointing at the phone app download.
  try {
    var qr = qrcode(0, 'M');
    qr.addData(PHONE_APP_URL);
    qr.make();
    document.getElementById('qr').innerHTML = qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
  } catch (e) {
    document.getElementById('getapp').style.display = 'none';
  }

  // Version comes from tizen/config.xml when installed as the TV app.
  try {
    document.getElementById('version').textContent =
      ' v' + tizen.application.getCurrentApplication().appInfo.version;
  } catch (e) { /* in a browser: no version */ }

  connect();
})();
