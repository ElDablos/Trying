// Renders every wallpaper in wallpapers.js to ../wallpapers/*.jpg.
// Run: node theme/gothic-crimson/src/render.js
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const { W, H, wallpapers } = require('./wallpapers');

(async () => {
  const outDir = path.join(__dirname, '..', 'wallpapers');
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  for (const [name, build] of Object.entries(wallpapers)) {
    await page.setContent(`<html><body style="margin:0;background:#000">${build()}</body></html>`);
    await page.screenshot({ path: path.join(outDir, `${name}.jpg`), type: 'jpeg', quality: 95, clip: { x: 0, y: 0, width: W, height: H } });
    console.log(`wrote ${name}.jpg`);
  }
  await browser.close();
})();
