// Talks to the Samsung TV remote-control API (same calls the Android app makes).
//   node tv.js <tv-ip> apps                 list installed apps
//   node tv.js <tv-ip> open <url> [appId]   open a URL in the TV browser
// First use pops an "Allow" prompt on the TV; the token it grants is cached in .tv-token.
const fs = require('fs');
const path = require('path');
const WS = require('ws');

const [ip, cmd, arg, appIdArg] = process.argv.slice(2);
const tokenFile = path.join(__dirname, '.tv-token');
const token = fs.existsSync(tokenFile) ? fs.readFileSync(tokenFile, 'utf8').trim() : '';
const name = Buffer.from('CastBridge').toString('base64');
const url = `wss://${ip}:8002/api/v2/channels/samsung.remote.control?name=${name}${token ? `&token=${token}` : ''}`;

const ws = new WS(url, { rejectUnauthorized: false });
const emit = (event, data) => ws.send(JSON.stringify({ method: 'ms.channel.emit', params: { event, to: 'host', data } }));

ws.on('message', (raw) => {
  const msg = JSON.parse(raw);
  if (msg.event === 'ms.channel.connect') {
    if (msg.data && msg.data.token) fs.writeFileSync(tokenFile, msg.data.token);
    if (cmd === 'apps') emit('ed.installedApp.get');
    if (cmd === 'open') {
      emit('ed.apps.launch', { appId: appIdArg || 'org.tizen.browser', action_type: process.env.ACTION || 'NATIVE_LAUNCH', metaTag: arg });
      setTimeout(() => process.exit(0), 1500);
    }
  } else if (msg.event === 'ed.installedApp.get') {
    for (const a of msg.data.data) console.log(a.appId.padEnd(28), a.name);
    process.exit(0);
  } else {
    console.log(msg.event, JSON.stringify(msg.data || ''));
  }
});
ws.on('error', (e) => { console.error('error:', e.message); process.exit(1); });
setTimeout(() => { console.error('timeout (accept the prompt on the TV?)'); process.exit(1); }, 45000);
