// Crimson Reactor: a ray-marched arc reactor floating in front of an endless hex tunnel.
// GLSL ES 1.00, shared by the wallpaper and its preview.
precision highp float;

uniform vec2 uRes;          // buffer size in pixels
uniform float uTime;        // seconds
uniform float uPhase;       // seconds scaled by the mode's energy (.7 calm, 1 normal, 1.3 night), summed frame by frame
uniform float uSpin;        // the same, also sped up while a call rings
uniform vec3 uPrimary;      // theme colour
uniform vec3 uSecondary;    // theme second accent
uniform vec2 uTilt;         // phone tilt, about -1..1
uniform float uCenterY;     // reactor centre, fraction of the height from the top
uniform float uPulseT;      // seconds since a message arrived (large when none)
uniform vec3 uPulseColor;   // that app's colour
uniform float uCall;        // 0..1 while a call rings
uniform float uCharge;      // 0..1 while charging
uniform float uLevel;       // battery level 0..1
uniform vec3 uTap;          // tap x, y (0..1 from top-left) and seconds since
uniform float uBoot;        // seconds since unlock
uniform float uLow;         // 1 on low battery

#define PI 3.14159265
#define TAU 6.2831853

mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

// --- The reactor, as distance fields. Axis along z, facing the camera. ---

float sdTorus(vec3 p, float R, float r) { vec2 q = vec2(length(p.xy) - R, p.z); return length(q) - r; }
float sdBox(vec3 p, vec3 b) { vec3 q = abs(p) - b; return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0); }

// Repeats p round the z axis into n sectors, returning it in the first.
vec3 sector(vec3 p, float n) {
    float a = atan(p.y, p.x);
    float s = TAU / n;
    a = mod(a + s * 0.5, s) - s * 0.5;
    float r = length(p.xy);
    return vec3(r * cos(a), r * sin(a), p.z);
}

float gSpin;

// Charging energy: a warm white-gold, nudged towards the theme's second colour, so it stands out in
// every theme (even green ones, whose second colour is green too).
vec3 energyCol() { return mix(vec3(1.0, 0.82, 0.38), uSecondary, 0.25); }
float gBoot;

// Rounded box: softer edges catch the light instead of stair-stepping.
float sdRound(vec3 p, vec3 b, float r) { return sdBox(p, b - r) - r; }

// The coils' frame: turned with the coils, folded into the first of ten sectors.
vec3 coilSpace(vec3 p) {
    vec3 q = p;
    q.xy = rot(gSpin * 0.25) * q.xy;
    return sector(q, 10.0);
}

// Returns distance; mat: 0 dark metal, 1 glowing, 2 copper coils.
float map(vec3 p, out float mat, out float glow) {
    // Housing: a thick outer ring with a raised lip round its face.
    float housing = min(sdTorus(p, 1.0, 0.105), sdTorus(p - vec3(0.0, 0.0, -0.06), 1.0, 0.07));
    // Ten copper coils round it, slowly turning, wound round rounded cores.
    vec3 q = coilSpace(p);
    float coil = sdRound(q - vec3(0.78, 0.0, 0.0), vec3(0.125, 0.07, 0.095), 0.035);
    // Inner glowing ring and a counter-spinning segmented ring.
    float inner = sdTorus(p, 0.56, 0.032);
    vec3 s = p;
    s.xy = rot(-gSpin) * s.xy;
    s = sector(s, 6.0);
    float seg = sdRound(s - vec3(0.44, 0.0, 0.03), vec3(0.028, 0.11, 0.028), 0.02);
    // Core: a lens with a triangle set in it.
    float core = length(p * vec3(1.0, 1.0, 2.2)) - 0.27;
    vec3 t = p;
    t.xy = rot(gSpin * 0.5) * t.xy;
    float tri = max(max(-t.y - 0.1, dot(t.xy, vec2(0.866, 0.5)) - 0.1), dot(t.xy, vec2(-0.866, 0.5)) - 0.1);
    tri = max(abs(tri) - 0.012, abs(t.z + 0.06) - 0.03);

    // Bolts round the housing's face, and the collar between coils and core.
    vec3 b = sector(p, 20.0);
    float bolt = length(b - vec3(1.0, 0.0, -0.13)) - 0.024;
    float collar = sdTorus(p - vec3(0.0, 0.0, 0.08), 0.66, 0.045);
    float metal = min(min(housing, bolt), collar);
    float hot = min(min(inner, seg), min(core, tri));
    glow = hot;
    float d = min(metal, min(hot, coil));
    mat = d == hot ? 1.0 : (d == coil ? 2.0 : 0.0);
    return d;
}

vec3 normalAt(vec3 p) {
    float m, g;
    vec2 e = vec2(0.002, 0.0);
    return normalize(vec3(
        map(p + e.xyy, m, g) - map(p - e.xyy, m, g),
        map(p + e.yxy, m, g) - map(p - e.yxy, m, g),
        map(p + e.yyx, m, g) - map(p - e.yyx, m, g)));
}

// Lights a point on the reactor.
vec3 shade(vec3 p, vec3 rd, float mat, vec3 col, vec3 col2, vec3 hotCol, float boot) {
    vec3 n = normalAt(p);
    vec3 key = normalize(vec3(-0.4, 0.6, -1.0));
    vec3 l = normalize(-p);
    float diff = max(dot(n, l), 0.0);
    if (mat > 1.5) {
        // Copper coils in the second colour: fine windings, a lit seam down the middle, and a soft
        // highlight that rolls over the rounded edges.
        vec3 q = coilSpace(p);
        float wire = 0.6 + 0.4 * smoothstep(-0.3, 0.6, cos((q.x - 0.78) * TAU * 34.0));
        float fres = pow(1.0 - max(dot(n, -rd), 0.0), 2.0);
        float spec = pow(max(dot(reflect(rd, n), key), 0.0), 32.0);
        vec3 c = col2 * (0.1 + 0.6 * diff * wire + 0.45 * fres) * boot + mix(col2, vec3(1.0), 0.55) * spec * 0.9;
        float seam = smoothstep(0.03, 0.006, abs(q.y)) * smoothstep(0.02, -0.04, q.z);
        float halo = exp(-abs(q.y) * 40.0) * 0.35;
        return c * (1.0 - seam * 0.6) + hotCol * (seam * 1.5 + halo) * boot;
    } else if (mat > 0.5) {
        // Hotter towards the middle: a swirling white-hot core, the theme colour outside.
        float pr2 = length(p.xy);
        float heat = smoothstep(0.6, 0.0, pr2);
        float swirl = 0.5 + 0.5 * sin(atan(p.y, p.x) * 3.0 + pr2 * 14.0 - uPhase * 1.4);
        float rings = 0.5 + 0.5 * sin(pr2 * 60.0 - uPhase * 2.6);
        return mix(hotCol * (1.25 + 0.3 * swirl + 0.2 * rings), vec3(1.0, 0.97, 0.97) * 1.7, heat * 0.55) * boot;
    }
    // Brushed dark metal lit by the core, a rim of the theme colour and a sharp highlight.
    float fres = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
    float spec = pow(max(dot(reflect(rd, n), key), 0.0), 48.0);
    float brushed = 0.85 + 0.15 * sin(atan(p.y, p.x) * 180.0);
    return vec3(0.055, 0.05, 0.058) * brushed + col * (0.32 * diff + 0.85 * fres) * boot + vec3(spec * 0.75);
}

// --- The tunnel behind it: hex cells on the walls of an endless tube rushing towards you. ---

float hexEdge(vec2 p) {
    vec2 r = vec2(1.0, 1.732);
    vec2 h = r * 0.5;
    vec2 a = mod(p, r) - h;
    vec2 b = mod(p - h, r) - h;
    vec2 gv = dot(a, a) < dot(b, b) ? a : b;
    vec2 ap = abs(gv);
    float d = max(dot(ap, normalize(vec2(1.0, 1.732))), ap.x);
    return 0.5 - d; // 0 on the edge
}

vec3 tunnel(vec2 uv, float speed, vec3 col, vec3 col2) {
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float z = 0.42 / max(r, 0.02) + uPhase * speed;
    vec2 tp = vec2(a / TAU * 28.0, z * 3.4);
    float e = hexEdge(tp);
    // A thin bright edge with a soft glow round it.
    float line = smoothstep(0.035, 0.0, e) + 0.35 * exp(-e * 14.0);
    // Some cells light up, flickering like data.
    vec2 cellId = floor(tp + 0.5);
    float lit = step(0.93, hash(cellId + floor(uTime * 0.3))) * smoothstep(0.1, 0.3, e);
    // Data pulses running along the walls towards you.
    float lane = floor(a / TAU * 28.0);
    float band = pow(0.5 + 0.5 * sin(z * 1.6 - uPhase * 1.3 + lane * 1.7), 24.0);
    float fog = smoothstep(0.08, 0.9, r) * (0.6 + 0.4 * smoothstep(1.6, 0.4, r));
    vec3 c = col * (line * (0.18 + 1.8 * band) + lit * 0.22) * fog;
    // Each cell a panel, shaded a little differently, so the walls read as surfaces.
    c += col * (0.025 + 0.05 * hash(cellId)) * smoothstep(0.0, 0.12, e) * fog * (0.5 + 0.5 * sin(a * 2.0 + 1.0));
    // Warp streaks flying past.
    float sl = floor(a / TAU * 90.0);
    float sh = hash(vec2(sl, 3.0));
    float sz = fract(z * 0.35 * (0.6 + sh) - uPhase * 0.1 + sh * 7.0);
    c += mix(col, vec3(1.0), 0.4) * step(0.82, sh) * smoothstep(0.0, 0.04, sz) * smoothstep(0.12, 0.04, sz) * fog * 0.7;
    // A message: the walls light up in the app's colour as the wave passes out through them.
    float wave = uPulseT * 1.4;
    c += uPulseColor * line * smoothstep(wave - 0.5, wave, r) * smoothstep(wave + 0.05, wave - 0.05, r) * exp(-uPulseT * 1.2) * 2.2;
    // Charging: the walls fill with energy from the bottom up to the battery level, and bright bands
    // keep rising up the whole tunnel.
    vec3 en = energyCol();
    float fromBottom = gl_FragCoord.y / uRes.y;
    float fill = uCharge * smoothstep(uLevel + 0.02, uLevel - 0.02, fromBottom);
    c = mix(c, en * (line * (0.6 + 2.2 * band) + lit * 0.3) * fog, fill);
    float rising = pow(0.5 + 0.5 * sin(fromBottom * 16.0 - uTime * 3.2), 10.0);
    c += en * line * rising * uCharge * 1.4 * fog;
    // The level's edge glows like a waterline.
    c += en * exp(-abs(fromBottom - uLevel) * 160.0) * uCharge * 0.9;
    // A faint glow deep in the tunnel.
    c += col * 0.35 * exp(-r * 6.0);
    return c;
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 centre = vec2(0.5 * uRes.x, (1.0 - uCenterY) * uRes.y);
    vec2 uv = (frag - centre) / uRes.x * 2.0;

    float boot = clamp(uBoot / 1.6, 0.0, 1.0);
    gBoot = boot;
    gSpin = uSpin * 0.28 + (1.0 - boot) * 6.0;

    vec3 col = uPrimary;
    vec3 col2 = uSecondary;
    if (uLow > 0.5) col *= 0.55 + 0.45 * pow(0.5 + 0.5 * sin(uTime * 2.2), 3.0);

    // Background tunnel, with a little parallax from the tilt.
    vec3 c = tunnel(uv - uTilt * 0.06, 0.2, col, col2) * boot;

    // Ray-march the reactor, tilted with the phone.
    vec3 ro = vec3(0.0, 0.0, -3.0);
    vec3 rd = normalize(vec3(uv * 0.52, 1.0));
    mat2 tx = rot(uTilt.y * 0.35 + sin(uTime * 0.2) * 0.06);
    mat2 ty = rot(uTilt.x * 0.35 + cos(uTime * 0.15) * 0.06);
    ro.yz = tx * ro.yz; rd.yz = tx * rd.yz;
    ro.xz = ty * ro.xz; rd.xz = ty * rd.xz;
    float scale = 0.55 + 0.45 * boot;
    ro /= scale;
    float t = 0.0;
    float glowAcc = 0.0;
    float mat = 0.0;
    float hit = 0.0;
    // The ray's closest pass, for smooth edges: a pixel the reactor only partly covers is blended.
    float best = 1e3;
    float bestT = 0.0;
    float px = 1.6 / uRes.x;
    for (int i = 0; i < 64; i++) {
        vec3 p = ro + rd * t;
        float g;
        float d = map(p, mat, g);
        glowAcc += exp(-g * 22.0) * 0.045;
        float footprint = px * t;
        if (d / footprint < best) { best = d / footprint; bestT = t; }
        if (d < 0.0008) { hit = 1.0; break; }
        t += d * 0.9;
        if (t > 7.0) break;
    }
    float corePulse = 0.75 + 0.25 * sin(uTime * 1.5) + 0.6 * exp(-uPulseT * 2.5);
    // Charging: the core beats with energy, about once a second.
    float beat = pow(0.5 + 0.5 * sin(uTime * 5.4), 12.0) * uCharge;
    corePulse += beat * 0.8;
    vec3 hotCol = mix(col, vec3(1.0), 0.18) * corePulse;
    hotCol = mix(hotCol, energyCol() * corePulse, uCharge * 0.6);
    float cover = hit > 0.5 ? 1.0 : smoothstep(1.0, 0.0, best);
    if (cover > 0.0) {
        float tt = hit > 0.5 ? t : bestT;
        vec3 p = ro + rd * tt;
        float m, g;
        map(p, m, g);
        vec3 surf = shade(p, rd, m, col, col2, hotCol, boot);
        c = mix(c, surf, cover);
    }
    hit = cover;
    c += hotCol * glowAcc * boot;

    // Bloom round the core, and light rays streaming out through the coils.
    float rr = length(uv);
    c += col * 0.55 * exp(-rr * 3.0) * corePulse * boot;
    float ang = atan(uv.y, uv.x);
    float rays = pow(0.5 + 0.5 * sin(ang * 10.0 + gSpin * 0.25 * 10.0), 6.0) * 0.6 + 0.4 * pow(0.5 + 0.5 * sin(ang * 23.0 - uTime * 0.35), 10.0);
    c += col * rays * smoothstep(0.45, 0.75, rr) * exp(-(rr - 0.6) * 2.2) * 0.35 * corePulse * boot * (1.0 - hit);

    // Charging: a gauge round the reactor, lit up to the battery level, in segments.
    if (uCharge > 0.01) {
        float fromTop = atan(uv.x, uv.y);
        float frac = (fromTop < 0.0 ? fromTop + TAU : fromTop) / TAU;
        float seg = step(fract(frac * 60.0), 0.72);
        float band = exp(-abs(rr - 0.76) * 140.0);
        float on = step(frac, uLevel);
        c += energyCol() * band * seg * mix(0.18, 1.8 + 0.6 * beat, on) * uCharge;
        // A bright tip where the level reaches.
        c += energyCol() * exp(-length(uv - 0.76 * vec2(sin(uLevel * TAU), cos(uLevel * TAU))) * 60.0) * 1.5 * uCharge;
    }

    // A message: a shockwave in the app's colour.
    float pr = uPulseT * 1.4;
    c += uPulseColor * exp(-abs(rr - pr) * 28.0) * exp(-uPulseT * 1.6) * 1.8;

    // A call: two comets circling the reactor.
    if (uCall > 0.0) {
        for (int k = 0; k < 2; k++) {
            float ang = uTime * 2.6 + float(k) * PI;
            for (int j = 0; j < 8; j++) {
                float aj = ang - float(j) * 0.09;
                vec2 cp = vec2(cos(aj), sin(aj)) * 0.78;
                c += uPulseColor * uCall * (1.0 - float(j) / 8.0) * 0.0035 / (dot(uv - cp, uv - cp) + 0.0004);
            }
        }
    }

    // A tap: a ripple where the finger landed.
    vec2 tapPos = (vec2(uTap.x, 1.0 - uTap.y) * uRes - centre) / uRes.x * 2.0;
    float tr = length(uv - tapPos);
    c += col * exp(-abs(tr - uTap.z * 0.9) * 40.0) * exp(-uTap.z * 2.5) * 1.4;

    // Embers drifting up on three depths.
    for (int k = 0; k < 3; k++) {
        float depth = 1.0 + float(k) * 0.8;
        vec2 ep = (frag / uRes.x) * 9.0 * depth + vec2(float(k) * 13.1, uPhase * (0.17 / depth)) + uTilt * 0.4 * float(k);
        vec2 cell = floor(ep);
        vec2 f = fract(ep) - 0.5;
        float h = hash(cell + float(k));
        vec2 off = vec2(h - 0.5, hash(cell * 1.7) - 0.5) * 0.6;
        float d = length(f - off);
        float tw = 0.5 + 0.5 * sin(uTime * 1.5 + h * 40.0);
        c += mix(col, col2, step(0.85, h)) * smoothstep(0.06 / depth, 0.0, d) * step(0.6, h) * tw * 0.9 * boot;
    }

    // Boot: a scan line sweeping down.
    if (uBoot < 1.6) {
        float sy = 1.0 - (frag.y / uRes.y);
        c += col * exp(-abs(sy - uBoot / 1.2) * 60.0) * 1.5;
    }

    // Vignette and tone.
    vec2 sv = frag / uRes - 0.5;
    c *= 1.0 - dot(sv, sv) * 1.1;
    c = c / (1.0 + c * 0.45);
    gl_FragColor = vec4(c, 1.0);
}
