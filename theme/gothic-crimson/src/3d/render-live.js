// Renders live.html frame by frame and encodes a seamless looping MP4 for the Crimson Live wallpaper app.
// Run from this folder: npm install && node render-live.js [--preview]
//   --preview renders a few still frames to the scratch folder instead of the whole video.
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');

const W = 1080, H = 2340, FPS = 30, LOOP = 12;
const preview = process.argv.includes('--preview');
const OUT = path.join(__dirname, '..', '..', '..', '..', 'live', 'src', 'main', 'res', 'raw', 'crimson_live.mp4');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript' };

const server = http.createServer((req, res) => {
  const file = path.join(__dirname, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(__dirname) || !fs.existsSync(file)) {
    res.writeHead(404).end();
    return;
  }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

server.listen(0, async () => {
  const frames = fs.mkdtempSync(path.join(os.tmpdir(), 'crimson-live-'));
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  try {
    const page = await browser.newPage({ viewport: { width: W, height: H } });
    page.on('pageerror', (e) => console.error(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/live.html?w=${W}&h=${H}`);
    await page.waitForFunction(() => window.__ready === true, null, { timeout: 120000 });
    const canvas = await page.$('canvas');
    const total = preview ? 4 : FPS * LOOP;
    for (let i = 0; i < total; i++) {
      const t = preview ? (i * LOOP) / 4 : i / FPS;
      await page.evaluate((time) => window.renderFrame(time), t);
      await canvas.screenshot({ path: path.join(frames, `f${String(i).padStart(4, '0')}.png`) });
      if (i % 30 === 0) console.log(`frame ${i}/${total}`);
    }
  } finally {
    await browser.close();
    server.close();
  }
  if (preview) {
    console.log(`preview frames in ${frames}`);
    return;
  }
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  execFileSync('ffmpeg', [
    '-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', path.join(frames, 'f%04d.png'),
    '-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-crf', '20', '-preset', 'slow',
    '-movflags', '+faststart', OUT,
  ], { stdio: 'inherit' });
  fs.rmSync(frames, { recursive: true, force: true });
  console.log(`wrote ${OUT}`);
});
