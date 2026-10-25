// Renders live.html frame by frame and encodes the seamless looping video for the Crimson Live wallpaper app.
// Each frame is rendered as three layers, packed side by side into one 3240x2340 video:
//   [ background: wall + rose window | foreground: bands, neon, embers on black | mask: the bands in white ]
// The app stacks them on the phone and shifts each by a different amount as the phone tilts.
// Run from this folder: npm install && node render-live.js [--preview]
//   --preview renders the layers of a few frames to a temp folder instead of the whole video.
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
      for (const layer of ['bg', 'fg', 'mask']) {
        await page.evaluate(([time, l]) => window.renderFrame(time, l), [t, layer]);
        await canvas.screenshot({ path: path.join(frames, `${layer}${String(i).padStart(4, '0')}.png`) });
      }
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
  const input = (layer) => ['-framerate', String(FPS), '-i', path.join(frames, `${layer}%04d.png`)];
  execFileSync('ffmpeg', [
    '-y', '-loglevel', 'error', ...input('bg'), ...input('fg'), ...input('mask'),
    '-filter_complex', '[0][1][2]hstack=inputs=3,format=yuv420p',
    '-c:v', 'libx264', '-profile:v', 'high', '-crf', '21', '-preset', 'slow',
    '-movflags', '+faststart', OUT,
  ], { stdio: 'inherit' });
  fs.rmSync(frames, { recursive: true, force: true });
  console.log(`wrote ${OUT}`);
});
