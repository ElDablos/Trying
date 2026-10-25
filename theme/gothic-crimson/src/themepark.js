// Background images for Good Lock's Theme Park: the quick panel and the keyboard.
// Rendered to ../theme-park/*.jpg by: node theme/gothic-crimson/src/themepark.js
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const { defs, lancet, roseWindow } = require('./wallpapers');

function svg(w, h, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
<defs>${defs}</defs>${body}
<rect width="${w}" height="${h}" filter="url(#grain)" opacity="0.14"/></svg>`;
}

// Quick panel: kept dark in the middle where the tiles and notifications sit, with a faint rose window
// glowing at the top and a gothic arch framing the edges.
function quickPanel() {
  const w = 1440;
  const h = 3120;
  const body = `
    <linearGradient id="qp" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#1A0509"/><stop offset="0.35" stop-color="#0A0607"/><stop offset="1" stop-color="#050304"/>
    </linearGradient>
    <rect width="${w}" height="${h}" fill="url(#qp)"/>
    <circle cx="720" cy="380" r="700" fill="#7A0A14" opacity="0.35" filter="url(#blur90)"/>
    <g fill="none" stroke="#B3001B" stroke-width="4" opacity="0.28">${roseWindow(720, 380, 520)}</g>
    <g fill="none" stroke="#7A0A14" stroke-width="4" opacity="0.7">
      <path d="${lancet(720, h + 20, 1340, 1300, 2600)}"/>
      <path d="${lancet(720, h + 20, 1290, 1320, 2540)}" stroke-width="2" opacity="0.6"/>
    </g>
    <ellipse cx="720" cy="${h}" rx="900" ry="260" fill="#4A0610" opacity="0.45" filter="url(#blur90)"/>`;
  return { w, h, svg: svg(w, h, body) };
}

// Keyboard: near-black with a row of faint lancet windows and a crimson hairline along the top.
function keyboard() {
  const w = 1440;
  const h = 1080;
  let arches = '';
  for (let i = 0; i < 9; i++) {
    const cx = 80 + i * 160;
    // Spring line about one width below the point, so the arcs meet in a proper gothic point.
    arches += `<path d="${lancet(cx, h + 10, 110, h + 10 - 520 + 100, 520)}"/>`;
  }
  const body = `
    <linearGradient id="kb" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#140A0D"/><stop offset="1" stop-color="#050304"/>
    </linearGradient>
    <rect width="${w}" height="${h}" fill="url(#kb)"/>
    <ellipse cx="720" cy="${h}" rx="820" ry="300" fill="#7A0A14" opacity="0.35" filter="url(#blur90)"/>
    <g fill="#B3001B" opacity="0.08">${arches}</g>
    <g fill="none" stroke="#B3001B" stroke-width="2" opacity="0.35">${arches}</g>
    <rect width="${w}" height="3" fill="#E01E37" opacity="0.8"/>`;
  return { w, h, svg: svg(w, h, body) };
}

(async () => {
  const outDir = path.join(__dirname, '..', 'theme-park');
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch();
  for (const [name, build] of Object.entries({ 'quick-panel': quickPanel, keyboard })) {
    const { w, h, svg: content } = build();
    const page = await browser.newPage({ viewport: { width: w, height: h } });
    await page.setContent(`<html><body style="margin:0;background:#000">${content}</body></html>`);
    await page.screenshot({ path: path.join(outDir, `${name}.jpg`), type: 'jpeg', quality: 95, clip: { x: 0, y: 0, width: w, height: h } });
    await page.close();
    console.log(`wrote ${name}.jpg`);
  }
  await browser.close();
})();
