// Builds the Gothic Crimson wallpapers as SVG strings.
// Rendered to JPEG by render.js. Canvas is 1440x3120, the Galaxy S26 Ultra's QHD+ resolution.

const W = 1440;
const H = 3120;

// Small seeded RNG so every render comes out identical.
function rng(seed) {
  return () => {
    seed = (seed * 1664525 + 1013904223) % 4294967296;
    return seed / 4294967296;
  };
}

const defs = `
  <filter id="grain" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch"/>
    <feColorMatrix values="0 0 0 0 0.5  0 0 0 0 0.1  0 0 0 0 0.1  0 0 0 0.55 0"/>
  </filter>
  <filter id="blur40" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="40"/></filter>
  <filter id="blur90" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="90"/></filter>
  <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
    <feGaussianBlur stdDeviation="10" result="b"/>
    <feMerge><feMergeNode in="b"/><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
  <radialGradient id="vignette" cx="50%" cy="45%" r="75%">
    <stop offset="55%" stop-color="#000" stop-opacity="0"/>
    <stop offset="100%" stop-color="#000" stop-opacity="0.85"/>
  </radialGradient>
`;

const grain = `<rect width="${W}" height="${H}" filter="url(#grain)" opacity="0.18"/>`;
const vignette = `<rect width="${W}" height="${H}" fill="url(#vignette)"/>`;

function svg(body, extraDefs = '') {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
<defs>${defs}${extraDefs}</defs>${body}${grain}${vignette}</svg>`;
}

// A gothic pointed (lancet) arch: straight sides up to `spring`, then two arcs meeting in a point.
function lancet(cx, bottom, width, spring, height) {
  const l = cx - width / 2;
  const r = cx + width / 2;
  const top = bottom - height;
  const rad = width * 0.9;
  return `M${l} ${bottom} L${l} ${spring} A${rad} ${rad} 0 0 1 ${cx} ${top} A${rad} ${rad} 0 0 1 ${r} ${spring} L${r} ${bottom} Z`;
}

function bat(x, y, s, rot) {
  return `<path transform="translate(${x} ${y}) rotate(${rot}) scale(${s})" fill="#050203"
    d="M0 0 C-6 -8 -14 -10 -22 -6 C-26 -14 -36 -16 -44 -10 C-40 -6 -38 0 -40 6 C-30 2 -20 4 -14 10
       C-10 4 -4 4 0 8 C4 4 10 4 14 10 C20 4 30 2 40 6 C38 0 40 -6 44 -10 C36 -16 26 -14 22 -6
       C14 -10 6 -8 0 0 Z M-3 -2 L-5 -9 L-1 -4 L1 -4 L5 -9 L3 -2 Z"/>`;
}

// Cathedral skyline: towers with spires and a few lit windows.
function skyline(seed) {
  const rand = rng(seed);
  let out = '';
  let windows = '';
  const towers = [
    { x: 70, w: 150, top: 2380 }, { x: 230, w: 110, top: 2560 }, { x: 330, w: 210, top: 2150 },
    { x: 560, w: 320, top: 1880 }, { x: 900, w: 210, top: 2150 }, { x: 1100, w: 110, top: 2560 },
    { x: 1220, w: 150, top: 2380 }, { x: -40, w: 120, top: 2650 }, { x: 1360, w: 120, top: 2650 },
  ];
  for (const t of towers) {
    const cx = t.x + t.w / 2;
    const spire = t.w * 2.2;
    out += `<rect x="${t.x}" y="${t.top}" width="${t.w}" height="${H - t.top}"/>`;
    out += `<path d="M${t.x - 10} ${t.top} L${cx} ${t.top - spire} L${t.x + t.w + 10} ${t.top} Z"/>`;
    // Pinnacles on the corners.
    for (const px of [t.x - 6, t.x + t.w - 18]) {
      out += `<path d="M${px} ${t.top + 4} L${px + 12} ${t.top - spire * 0.35} L${px + 24} ${t.top + 4} Z"/>`;
    }
    // Cross / finial on the tallest spire.
    if (t.w > 300) {
      out += `<rect x="${cx - 4}" y="${t.top - spire - 90}" width="8" height="95"/>`;
      out += `<rect x="${cx - 26}" y="${t.top - spire - 64}" width="52" height="8"/>`;
    }
    // Windows.
    const cols = Math.max(1, Math.floor(t.w / 60));
    const gap = t.w / cols;
    for (let row = 0; row < 7; row++) {
      for (let c = 0; c < cols; c++) {
        if (rand() > 0.32) continue;
        const wx = t.x + gap * c + gap / 2;
        const wy = t.top + 90 + row * 150;
        if (wy > H - 200) continue;
        const ww = Math.min(26, gap * 0.4);
        const a = (0.45 + rand() * 0.55).toFixed(2);
        windows += `<path d="${lancet(wx, wy + 70, ww, wy + 20, 70)}" fill="#ff2a3d" opacity="${a}"/>`;
      }
    }
    // Rose window on the central tower.
    if (t.w > 300) {
      windows += `<circle cx="${cx}" cy="${t.top + 160}" r="70" fill="#ff2a3d" opacity="0.9"/>`;
      for (let i = 0; i < 12; i++) {
        const a = (i / 12) * Math.PI * 2;
        windows += `<line x1="${cx}" y1="${t.top + 160}" x2="${cx + Math.cos(a) * 70}" y2="${t.top + 160 + Math.sin(a) * 70}" stroke="#030203" stroke-width="6"/>`;
      }
      windows += `<circle cx="${cx}" cy="${t.top + 160}" r="22" fill="#030203"/>`;
      windows += `<path d="${lancet(cx, H, 120, H - 260, 380)}" fill="#ff2a3d" opacity="0.35"/>`;
    }
  }
  return `<g fill="#030203">${out}</g><g filter="url(#glow)">${windows}</g>`;
}

function cathedral() {
  const extra = `
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#070407"/>
      <stop offset="0.45" stop-color="#1c0509"/>
      <stop offset="0.75" stop-color="#2a050b"/>
      <stop offset="1" stop-color="#050203"/>
    </linearGradient>
    <radialGradient id="moon" cx="42%" cy="38%" r="70%">
      <stop offset="0" stop-color="#ff5a5a"/>
      <stop offset="0.45" stop-color="#c0101e"/>
      <stop offset="1" stop-color="#4a0008"/>
    </radialGradient>`;
  const rand = rng(7);
  let craters = '';
  for (let i = 0; i < 14; i++) {
    const a = rand() * Math.PI * 2;
    const d = rand() * 260;
    craters += `<circle cx="${720 + Math.cos(a) * d}" cy="${1000 + Math.sin(a) * d}" r="${10 + rand() * 45}" fill="#3a0006" opacity="${(0.15 + rand() * 0.25).toFixed(2)}"/>`;
  }
  let stars = '';
  for (let i = 0; i < 120; i++) {
    stars += `<circle cx="${rand() * W}" cy="${rand() * 1700}" r="${(0.8 + rand() * 2).toFixed(1)}" fill="#ffd6d6" opacity="${(0.15 + rand() * 0.5).toFixed(2)}"/>`;
  }
  const bats = [
    bat(330, 640, 1.6, -12), bat(1080, 560, 1.2, 10), bat(1180, 1420, 2.0, 6),
    bat(250, 1300, 1.0, -20), bat(910, 1520, 0.8, 15), bat(560, 470, 0.7, -5),
  ].join('');
  const body = `
    <rect width="${W}" height="${H}" fill="url(#sky)"/>
    ${stars}
    <circle cx="720" cy="1000" r="560" fill="#b0101c" opacity="0.35" filter="url(#blur90)"/>
    <circle cx="720" cy="1000" r="330" fill="url(#moon)"/>
    <g clip-path="circle(330px at 720px 1000px)">${craters}</g>
    <ellipse cx="560" cy="1150" rx="420" ry="34" fill="#050203" opacity="0.6" filter="url(#blur40)"/>
    <ellipse cx="960" cy="1240" rx="360" ry="28" fill="#050203" opacity="0.5" filter="url(#blur40)"/>
    <ellipse cx="820" cy="860" rx="260" ry="18" fill="#050203" opacity="0.35" filter="url(#blur40)"/>
    ${bats}
    <ellipse cx="720" cy="2300" rx="1100" ry="260" fill="#7a0a14" opacity="0.35" filter="url(#blur90)"/>
    ${skyline(42)}
    <ellipse cx="720" cy="2950" rx="1200" ry="260" fill="#3a0610" opacity="0.5" filter="url(#blur90)"/>`;
  return svg(body, extra);
}

// Rose window tracery, drawn in glowing crimson lines.
function roseWindow(cx, cy, R) {
  let s = '';
  s += `<circle cx="${cx}" cy="${cy}" r="${R}" />`;
  s += `<circle cx="${cx}" cy="${cy}" r="${R * 0.94}" />`;
  s += `<circle cx="${cx}" cy="${cy}" r="${R * 0.22}" />`;
  s += `<circle cx="${cx}" cy="${cy}" r="${R * 0.12}" />`;
  const n = 12;
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2 - Math.PI / 2;
    const a2 = a + Math.PI / n;
    // Spokes.
    s += `<line x1="${cx + Math.cos(a) * R * 0.22}" y1="${cy + Math.sin(a) * R * 0.22}" x2="${cx + Math.cos(a) * R * 0.94}" y2="${cy + Math.sin(a) * R * 0.94}"/>`;
    // Petal (lancet) between spokes, pointing outward.
    const pr = R * 0.62;
    const px = cx + Math.cos(a2) * pr;
    const py = cy + Math.sin(a2) * pr;
    const deg = (a2 * 180) / Math.PI + 90;
    s += `<path transform="translate(${px} ${py}) rotate(${deg})" d="M-34 70 L-34 0 A130 130 0 0 1 0 -110 A130 130 0 0 1 34 0 L34 70 Z"/>`;
    // Outer trefoil circles.
    const oc = R * 0.86;
    s += `<circle cx="${cx + Math.cos(a2) * oc}" cy="${cy + Math.sin(a2) * oc}" r="${R * 0.055}"/>`;
    // Inner quatrefoil ring.
    s += `<circle cx="${cx + Math.cos(a) * R * 0.32}" cy="${cy + Math.sin(a) * R * 0.32}" r="${R * 0.09}"/>`;
  }
  return s;
}

function roseLock() {
  const cx = 720;
  const cy = 1980;
  const R = 560;
  const extra = `
    <linearGradient id="bgL" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#030203"/>
      <stop offset="0.55" stop-color="#140408"/>
      <stop offset="1" stop-color="#030203"/>
    </linearGradient>
    <radialGradient id="glass" cx="50%" cy="50%" r="50%">
      <stop offset="0" stop-color="#ff3344" stop-opacity="0.55"/>
      <stop offset="0.6" stop-color="#8a0612" stop-opacity="0.35"/>
      <stop offset="1" stop-color="#2a0208" stop-opacity="0.1"/>
    </radialGradient>`;
  const frame = lancet(720, H + 20, 1260, 1500, 2400);
  const inner = lancet(720, H + 20, 1200, 1530, 2340);
  const body = `
    <rect width="${W}" height="${H}" fill="url(#bgL)"/>
    <circle cx="${cx}" cy="${cy}" r="${R * 1.25}" fill="#a00a18" opacity="0.30" filter="url(#blur90)"/>
    <circle cx="${cx}" cy="${cy}" r="${R}" fill="url(#glass)"/>
    <g fill="none" stroke="#ff2a3d" stroke-width="5" filter="url(#glow)">${roseWindow(cx, cy, R)}</g>
    <g fill="none" stroke="#7a0a14" stroke-width="4" opacity="0.9">
      <path d="${frame}"/><path d="${inner}"/>
    </g>
    <g fill="#ff2a3d" filter="url(#glow)" opacity="0.8">
      <path d="M720 2690 L732 2740 L782 2752 L732 2764 L720 2814 L708 2764 L658 2752 L708 2740 Z"/>
    </g>`;
  return svg(body, extra);
}

// Near-pure-black variant, kind to OLED and battery: one thin lancet arch and a dagger-cross.
function voidArch() {
  const extra = `
    <linearGradient id="bgV" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#000"/>
      <stop offset="0.7" stop-color="#0b0204"/>
      <stop offset="1" stop-color="#000"/>
    </linearGradient>`;
  const arch = lancet(720, 2700, 760, 1500, 1500);
  const arch2 = lancet(720, 2700, 700, 1520, 1440);
  const body = `
    <rect width="${W}" height="${H}" fill="url(#bgV)"/>
    <ellipse cx="720" cy="2600" rx="520" ry="200" fill="#6a0610" opacity="0.45" filter="url(#blur90)"/>
    <g fill="none" stroke="#c0101e" stroke-width="4" filter="url(#glow)">
      <path d="${arch}"/>
      <path d="${arch2}" stroke-width="2" opacity="0.6"/>
      <line x1="300" y1="2700" x2="1140" y2="2700"/>
    </g>
    <g fill="#e01e37" filter="url(#glow)">
      <rect x="712" y="1560" width="16" height="560" rx="4"/>
      <rect x="610" y="1700" width="220" height="16" rx="4"/>
      <path d="M720 2120 L740 2160 L720 2260 L700 2160 Z"/>
      <circle cx="720" cy="1540" r="18"/>
    </g>`;
  return svg(body, extra);
}

module.exports = {
  W,
  H,
  wallpapers: {
    'gothic-crimson-cathedral': cathedral,
    'gothic-crimson-rose-window': roseLock,
    'gothic-crimson-void': voidArch,
  },
};
