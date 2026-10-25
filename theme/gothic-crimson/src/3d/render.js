// Renders the 3D scenes to JPEGs in the gothic-crimson folder with headless Chromium (WebGL via SwiftShader).
// Run from this folder: npm install && node render.js [part-of-file-name]
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

// Output file (relative to the gothic-crimson folder) -> scene page and size.
const SCENES = {
  'theme-park/quick-panel.jpg': { page: 'bands.html?scene=panel', width: 1440, height: 3120 },
  'theme-park/keyboard.jpg': { page: 'bands.html?scene=keys', width: 1440, height: 1080 },
  'wallpapers/gothic-crimson-void.jpg': { page: 'void.html', width: 1440, height: 3120 },
};
const TYPES = { '.html': 'text/html', '.js': 'text/javascript' };

// ES modules don't load from file:// URLs, so serve this folder locally.
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
  const base = `http://127.0.0.1:${server.address().port}`;
  const root = path.join(__dirname, '..', '..');
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  try {
    const only = process.argv[2];
    for (const [name, { page, width, height }] of Object.entries(SCENES)) {
      if (only && !name.includes(only)) continue;
      const tab = await browser.newPage({ viewport: { width, height } });
      tab.on('pageerror', (e) => console.error(`${page}: ${e.message}`));
      tab.on('console', (m) => m.type() === 'error' && console.error(`${page}: ${m.text()}`));
      await tab.goto(`${base}/${page}`);
      await tab.waitForFunction(() => window.__done === true, null, { timeout: 600000 });
      const canvas = await tab.$('canvas');
      await canvas.screenshot({ path: path.join(root, name), type: 'jpeg', quality: 93 });
      await tab.close();
      console.log(`wrote ${name}`);
    }
  } finally {
    await browser.close();
    server.close();
  }
});
