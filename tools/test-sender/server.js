// Desktop stand-in for the Android sender: serves the receiver page and streams an
// ffmpeg test pattern using the same wire protocol, so the TV side can be tested alone.
//   node server.js [--audio] [--screen]   then open http://<this-mac-ip>:8080/ on the TV
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { WebSocketServer } = require('ws');

const PORT = 8080;
const ROOT = path.resolve(__dirname, '../../receiver');
const withAudio = process.argv.includes('--audio');
const useScreen = process.argv.includes('--screen');
const FPS = 30;

const TYPES = { '.html': 'text/html', '.js': 'application/javascript' };
const server = http.createServer((req, res) => {
  // Network probe: lets the TV report whether it can reach the phone.
  if (req.url.startsWith('/probe')) {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return fs.createReadStream(path.join(__dirname, 'probe.html')).pipe(res);
  }
  if (req.url === '/report') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => { console.log(`[tv ${req.socket.remoteAddress}] ${body}`); res.end(); });
    return;
  }
  const file = path.join(ROOT, req.url.split('?')[0] === '/' ? 'index.html' : req.url.split('?')[0]);
  if (!file.startsWith(ROOT) || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

const wss = new WebSocketServer({ server, path: '/stream' });

function packet(kind, durationUs, payload) {
  const head = Buffer.alloc(5);
  head.writeUInt8(kind, 0);
  head.writeUInt32BE(durationUs, 1);
  return Buffer.concat([head, payload]);
}

function broadcast(buf) {
  for (const c of wss.clients) if (c.readyState === 1 && c.bufferedAmount < 4e6) c.send(buf);
}

// Splits an Annex-B byte stream into access units on AUD NALs (type 9).
function auSplitter(onAU) {
  let pending = Buffer.alloc(0);
  return (chunk) => {
    pending = Buffer.concat([pending, chunk]);
    let start = -1;
    for (let i = 0; i + 4 < pending.length; i++) {
      if (pending[i] === 0 && pending[i + 1] === 0 && pending[i + 2] === 0 && pending[i + 3] === 1 &&
          (pending[i + 4] & 0x1f) === 9) {
        if (start >= 0) onAU(pending.subarray(start, i));
        start = i;
      }
    }
    if (start > 0) pending = pending.subarray(start);
  };
}

const input = useScreen
  ? ['-f', 'avfoundation', '-framerate', String(FPS), '-capture_cursor', '1', '-i', '1:none']
  : ['-re', '-f', 'lavfi', '-i', `testsrc2=size=1280x720:rate=${FPS}`];
const audioIn = withAudio ? ['-re', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000'] : [];
const args = [
  '-loglevel', 'error', ...input, ...audioIn,
  // VSIZE / VPROFILE env vars reproduce other encoder outputs (e.g. a portrait phone).
  '-map', '0:v', '-vf', `scale=${process.env.VSIZE || '-2:720'}`, '-c:v', 'libx264', '-preset', 'ultrafast',
  '-tune', 'zerolatency', '-profile:v', process.env.VPROFILE || 'baseline', '-g', String(FPS * 2), '-x264-params', 'aud=1:repeat-headers=1',
  '-f', 'h264', 'pipe:3',
];
if (withAudio) args.push('-map', '1:a', '-f', 's16le', '-ac', '2', '-ar', '48000', 'pipe:4');

const ff = spawn('ffmpeg', args, { stdio: ['ignore', 'inherit', 'inherit', 'pipe', 'pipe'] });
ff.stdio[3].on('data', auSplitter((au) => broadcast(packet(1, 1e6 / FPS, au))));
if (withAudio) {
  // 20 ms chunks of 48 kHz stereo s16le, same as the Android app.
  const CHUNK = 960 * 4;
  let pending = Buffer.alloc(0);
  ff.stdio[4].on('data', (chunk) => {
    pending = Buffer.concat([pending, chunk]);
    while (pending.length >= CHUNK) {
      broadcast(packet(2, 20000, pending.subarray(0, CHUNK)));
      pending = pending.subarray(CHUNK);
    }
  });
}

wss.on('connection', (ws) => {
  console.log('receiver connected');
  ws.send(JSON.stringify({ type: 'config', audio: withAudio }));
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(require('os').networkInterfaces()).flat()
    .filter((i) => i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log(`receiver at ${ips.map((ip) => `http://${ip}:${PORT}/`).join(' ')}`);
});
