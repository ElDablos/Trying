// Renders every icon in icons.js to a PNG and writes the icon pack's appfilter.xml and drawable.xml.
// Run from this folder: npm install && node generate.js
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const simpleIcons = require('simple-icons');
const icons = require('./icons');

const SIZE = 256;
const RES = path.join(__dirname, '..', 'src', 'main', 'res');
const ASSETS = path.join(__dirname, '..', 'src', 'main', 'assets');
const OUT = path.join(RES, 'drawable-nodpi');

// Multi-colour logos, as CSS backgrounds painted through the logo's shape.
const GRADIENTS = {
  google: 'conic-gradient(from -45deg, #EA4335 0 90deg, #4285F4 90deg 180deg, #34A853 180deg 270deg, #FBBC05 270deg 360deg)',
  chrome: 'conic-gradient(from -60deg, #EA4335 0 120deg, #FBBC05 120deg 240deg, #34A853 240deg 360deg)',
  play: 'linear-gradient(135deg, #00D7FE, #00F076 35%, #FFD500 70%, #FF8A00)',
  instagram: 'radial-gradient(circle at 30% 107%, #FDF497 0%, #FDF497 5%, #FD5949 45%, #D6249F 60%, #285AEB 90%)',
  messenger: 'radial-gradient(circle at 20% 100%, #0099FF, #A033FF 60%, #FF5280 90%, #FF7061)',
  gemini: 'linear-gradient(135deg, #4796E3, #9177C7, #CA6673)',
  copilot: 'linear-gradient(135deg, #2AA0F5, #7C5CFF, #E95FA8)',
  slack: 'conic-gradient(from 0deg, #36C5F0 0 90deg, #2EB67D 90deg 180deg, #ECB22E 180deg 270deg, #E01E5A 270deg 360deg)',
};

function readSvg(glyph) {
  const [set, name] = glyph.split(':');
  const file = set === 'si'
    ? require.resolve(`simple-icons/icons/${name}.svg`)
    : require.resolve(`@mdi/svg/svg/${name}.svg`);
  return { set, name, svg: fs.readFileSync(file, 'utf8') };
}

function brandHex(slug) {
  const icon = Object.values(simpleIcons).find((i) => i && i.slug === slug);
  return icon ? `#${icon.hex}` : '#FFFFFF';
}

/** True for reds close enough to the crimson tile to disappear on it. */
function isReddish(hex) {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => v / 255);
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return false;
  const s = l > 0.5 ? (max - min) / (2 - max - min) : (max - min) / (max + min);
  let h;
  if (max === r) h = ((g - b) / (max - min)) % 6;
  else if (max === g) h = (b - r) / (max - min) + 2;
  else h = (r - g) / (max - min) + 4;
  h = (h * 60 + 360) % 360;
  return (h >= 330 || h <= 18) && s > 0.45 && l > 0.25 && l < 0.75;
}

function fillFor(icon, set, name) {
  const color = icon.color ?? (set === 'si' ? brandHex(name) : '#FFFFFF');
  if (GRADIENTS[color]) return GRADIENTS[color];
  return isReddish(color) ? '#FFFFFF' : color;
}

// Dark blood-red glass: a deep tile the wallpaper shows faintly through, with a soft highlight at the
// top, a thin crimson edge and a darker rim.
const TILE = `
  position:absolute; inset:6px; border-radius:62px;
  background: radial-gradient(120% 90% at 30% 10%, rgba(140,20,38,0.66) 0%, rgba(98,6,22,0.64) 45%, rgba(46,0,8,0.72) 100%);
  box-shadow: inset 0 0 0 2px rgba(224,30,55,0.35), inset 0 0 0 5px rgba(0,0,0,0.22), inset 0 3px 0 rgba(255,255,255,0.12);`;

function iconHtml(svg, fill) {
  // Single quotes, since this sits inside a double-quoted style attribute; encodeURIComponent leaves ' alone.
  const mask = `url('data:image/svg+xml;utf8,${encodeURIComponent(svg).replace(/'/g, '%27')}')`;
  return `<div style="position:relative;width:${SIZE}px;height:${SIZE}px">
  <div style="${TILE}"></div>
  <div style="position:absolute;left:62px;top:62px;width:132px;height:132px;filter:drop-shadow(0 4px 6px rgba(40,0,6,0.55))">
    <div style="width:100%;height:100%;background:${fill};-webkit-mask:${mask} center/contain no-repeat;mask:${mask} center/contain no-repeat"></div>
  </div>
</div>`;
}

const EXTRAS = {
  // For launchers that frame other apps' icons: the same tile behind, nothing over.
  iconback: `<div style="position:relative;width:${SIZE}px;height:${SIZE}px"><div style="${TILE}"></div></div>`,
};

function xmlEscape(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

(async () => {
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  fs.mkdirSync(path.join(RES, 'xml'), { recursive: true });
  fs.mkdirSync(ASSETS, { recursive: true });

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: SIZE, height: SIZE } });
  async function render(name, html) {
    await page.setContent(`<html><body style="margin:0;background:transparent">${html}</body></html>`);
    // The logo is a CSS mask from a data URL, which decodes asynchronously.
    await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
    await page.screenshot({ path: path.join(OUT, `${name}.png`), omitBackground: true, clip: { x: 0, y: 0, width: SIZE, height: SIZE } });
  }
  for (const icon of icons) {
    const { set, name, svg } = readSvg(icon.glyph);
    await render(icon.id, iconHtml(svg, fillFor(icon, set, name)));
  }
  for (const [name, html] of Object.entries(EXTRAS)) await render(name, html);
  await browser.close();

  const items = icons.flatMap((icon) => icon.components.map((c) =>
    `    <item component="ComponentInfo{${xmlEscape(c)}}" drawable="${icon.id}" />`));
  const appfilter = `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate.js from tools/icons.js. Don't edit by hand. -->
<resources>
    <iconback img1="iconback" />
    <scale factor="0.62" />
${items.join('\n')}
</resources>
`;
  // Launchers read the appfilter from either place, so ship both.
  fs.writeFileSync(path.join(RES, 'xml', 'appfilter.xml'), appfilter);
  fs.writeFileSync(path.join(ASSETS, 'appfilter.xml'), appfilter);

  const drawables = `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate.js. Lists every icon for launchers' icon pickers. -->
<resources>
    <version>1</version>
    <category title="Crimson" />
${icons.map((i) => `    <item drawable="${i.id}" />`).join('\n')}
</resources>
`;
  fs.writeFileSync(path.join(RES, 'xml', 'drawable.xml'), drawables);
  fs.writeFileSync(path.join(ASSETS, 'drawable.xml'), drawables);
  console.log(`Rendered ${icons.length} icons, ${items.length} appfilter entries`);
})();
