// Renders the store images from HTML sources at 1920x1080 using a local Chromium-based browser.
//   node store/render.js            (from the repo root; needs tools/test-sender deps installed)
// BROWSER=/path/to/chrome overrides the browser (default: Chrome, then Brave on macOS).
const fs = require('fs');
const path = require('path');
const puppeteer = require('../tools/test-sender/node_modules/puppeteer-core');

const candidates = [
  process.env.BROWSER,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean);
const executablePath = candidates.find((p) => fs.existsSync(p));

// [source html, output file, transparent background, extra wait ms, script to run first]
const RECEIVER = '../receiver/index.html';
// The TV app shows "· v<version>" from its Tizen app info; a browser has none, so fill it in.
const VERSION = fs.readFileSync(path.join(__dirname, '../tizen/config.xml'), 'utf8').match(/version="([\d.]+)" viewmodes/)[1];
const withVersion = (v) => { document.getElementById('version').textContent = ' v' + v; };
const jobs = [
  ['src/logo.html', 'logo.png', true, 0],
  ['src/background.html', 'background.jpg', false, 0],
  [RECEIVER, 'screenshot-1.jpg', false, 1500],
  ['src/screenshot-2.html', 'screenshot-2.jpg', false, 300],
  ['src/screenshot-3.html', 'screenshot-3.jpg', false, 300],
  [RECEIVER, 'screenshot-4.jpg', false, 1500, () => {
    document.getElementById('exit-dialog').classList.remove('hidden');
    document.getElementById('exit-no').className = 'focused';
  }],
];

(async () => {
  if (!executablePath) throw new Error('No Chrome/Chromium/Brave found; set BROWSER=');
  const browser = await puppeteer.launch({ executablePath, headless: true, args: ['--hide-scrollbars'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  for (const [src, out, transparent, wait, script] of jobs) {
    const file = path.join(__dirname, src);
    if (!fs.existsSync(file)) { console.log('skip', src); continue; }
    await page.goto('file://' + file, { waitUntil: 'load' });
    if (wait) await new Promise((r) => setTimeout(r, wait));
    if (src === RECEIVER) await page.evaluate(withVersion, VERSION);
    if (script) await page.evaluate(script);
    const type = out.endsWith('.png') ? 'png' : 'jpeg';
    await page.screenshot({
      path: path.join(__dirname, out), type, omitBackground: transparent,
      ...(type === 'jpeg' ? { quality: 88 } : {}),
    });
    console.log('wrote', out, fs.statSync(path.join(__dirname, out)).size, 'bytes');
  }
  // TV app icon (512x423), packaged with the app and uploaded to the store.
  await page.setViewport({ width: 512, height: 423 });
  await page.goto('file://' + path.join(__dirname, '../tizen/icon.svg'), { waitUntil: 'load' });
  await page.screenshot({ path: path.join(__dirname, '../tizen/icon.png'), type: 'png' });
  console.log('wrote ../tizen/icon.png');
  await page.setViewport({ width: 1920, height: 1080 });

  // App UI Description document for Samsung's testers (kept locally, not in git).
  const uiDescription = path.join(__dirname, 'src/ui-description.html');
  if (fs.existsSync(uiDescription)) {
    await page.goto('file://' + uiDescription, { waitUntil: 'load' });
    await page.pdf({ path: path.join(__dirname, 'ui-description.pdf'), format: 'A4', printBackground: true });
    console.log('wrote ui-description.pdf', fs.statSync(path.join(__dirname, 'ui-description.pdf')).size, 'bytes');
  }
  await browser.close();
})().catch((e) => { console.error(e.message); process.exit(1); });
