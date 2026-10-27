// Crimson Reactor: a ray-marched arc reactor floating in front of an endless hex tunnel.
// GLSL ES 1.00, shared by the wallpaper and its preview.
precision highp float;

uniform vec2 uRes;          // buffer size in pixels
uniform float uTime;        // seconds
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
uniform float uEnergy;      // mode: .7 calm, 1 normal, 1.3 night
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
float gBoot;

// Returns distance; mat: 0 dark metal, 1 glowing, 2 copper coils.
float map(vec3 p, out float mat, out float glow) {
    // Housing: a thick outer ring.
    float housing = sdTorus(p, 1.0, 0.11);
    // Ten coils round it, slowly turning.
    vec3 q = p;
    q.xy = rot(gSpin * 0.25) * q.xy;
    q = sector(q, 10.0);
    float coil = sdBox(q - vec3(0.78, 0.0, 0.0), vec3(0.12, 0.075, 0.1)) - 0.015;
    // Coil windings: thin glowing bands across each coil.
    float wind = sdBox(q - vec3(0.78, 0.0, -0.02), vec3(0.135, 0.02, 0.105));
    // Inner glowing ring and a counter-spinning segmented ring.
    float inner = sdTorus(p, 0.56, 0.035);
    vec3 s = p;
    s.xy = rot(-gSpin) * s.xy;
    s = sector(s, 6.0);
    float seg = sdBox(s - vec3(0.44, 0.0, 0.03), vec3(0.03, 0.12, 0.03));
    // Core: a lens with a triangle set in it.
    float core = length(p * vec3(1.0, 1.0, 2.2)) - 0.27;
    vec3 t = p;
    t.xy = rot(gSpin * 0.5) * t.xy;
    float tri = max(max(-t.y - 0.1, dot(t.xy, vec2(0.866, 0.5)) - 0.1), dot(t.xy, vec2(-0.866, 0.5)) - 0.1);
    tri = max(abs(tri) - 0.012, abs(t.z + 0.06) - 0.03);

    // Bolts round the housing's face.
    vec3 b = sector(p, 20.0);
    float bolt = length(b - vec3(1.0, 0.0, -0.1)) - 0.028;
    float metal = min(min(housing, bolt), sdTorus(p - vec3(0.0, 0.0, 0.08), 0.66, 0.05));
    float hot = min(min(inner, seg), min(core, min(tri, wind)));
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
    float z = 0.42 / max(r, 0.02) + uTime * speed;
    vec2 tp = vec2(a / TAU * 28.0, z * 3.4);
    float e = hexEdge(tp);
    // A thin bright edge with a soft glow round it.
    float line = smoothstep(0.035, 0.0, e) + 0.35 * exp(-e * 14.0);
    // Some cells light up, flickering like data.
    vec2 cellId = floor(tp + 0.5);
    float lit = step(0.93, hash(cellId + floor(uTime * 0.7))) * smoothstep(0.1, 0.3, e);
    // Data pulses running along the walls towards you.
    float lane = floor(a / TAU * 28.0);
    float band = pow(0.5 + 0.5 * sin(z * 1.6 - uTime * 3.0 * uEnergy + lane * 1.7), 24.0);
    float fog = smoothstep(0.08, 0.9, r) * (0.6 + 0.4 * smoothstep(1.6, 0.4, r));
    vec3 c = col * (line * (0.18 + 1.8 * band) + lit * 0.22) * fog;
    // Each cell a panel, shaded a little differently, so the walls read as surfaces.
    c += col * (0.025 + 0.05 * hash(cellId)) * smoothstep(0.0, 0.12, e) * fog * (0.5 + 0.5 * sin(a * 2.0 + 1.0));
    // Warp streaks flying past.
    float sl = floor(a / TAU * 90.0);
    float sh = hash(vec2(sl, 3.0));
    float sz = fract(z * 0.35 * (0.6 + sh) - uTime * 0.25 * uEnergy + sh * 7.0);
    c += mix(col, vec3(1.0), 0.4) * step(0.82, sh) * smoothstep(0.0, 0.04, sz) * smoothstep(0.12, 0.04, sz) * fog * 0.7;
    // A message: the walls light up in the app's colour as the wave passes out through them.
    float wave = uPulseT * 1.4;
    c += uPulseColor * line * smoothstep(wave - 0.5, wave, r) * smoothstep(wave + 0.05, wave - 0.05, r) * exp(-uPulseT * 1.2) * 2.2;
    // Charging: the walls fill with the second colour from the bottom.
    float fill = uCharge * step(1.0 - uLevel, (1.0 - (gl_FragCoord.y / uRes.y)));
    c = mix(c, col2 * line * (0.5 + 2.0 * band) * fog, fill);
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
    float callSpin = 1.0 + 5.0 * uCall;
    gSpin = uTime * 0.6 * uEnergy * callSpin + (1.0 - boot) * 6.0;

    vec3 col = uPrimary;
    vec3 col2 = uSecondary;
    if (uLow > 0.5) col *= 0.55 + 0.45 * pow(0.5 + 0.5 * sin(uTime * 2.2), 3.0);

    // Background tunnel, with a little parallax from the tilt.
    vec3 c = tunnel(uv - uTilt * 0.06, 0.55 * uEnergy, col, col2) * boot;

    // Ray-march the reactor, tilted with the phone.
    vec3 ro = vec3(0.0, 0.0, -3.0);
    vec3 rd = normalize(vec3(uv * 0.52, 1.0));
    mat2 tx = rot(uTilt.y * 0.35 + sin(uTime * 0.4) * 0.06);
    mat2 ty = rot(uTilt.x * 0.35 + cos(uTime * 0.31) * 0.06);
    ro.yz = tx * ro.yz; rd.yz = tx * rd.yz;
    ro.xz = ty * ro.xz; rd.xz = ty * rd.xz;
    float scale = 0.55 + 0.45 * boot;
    ro /= scale;
    float t = 0.0;
    float glowAcc = 0.0;
    float mat = 0.0;
    float hit = 0.0;
    for (int i = 0; i < 56; i++) {
        vec3 p = ro + rd * t;
        float g;
        float d = map(p, mat, g);
        glowAcc += exp(-g * 22.0) * 0.045;
        if (d < 0.001) { hit = 1.0; break; }
        t += d * 0.9;
        if (t > 7.0) break;
    }
    float corePulse = 0.75 + 0.25 * sin(uTime * 2.4) + 0.6 * exp(-uPulseT * 2.5);
    vec3 hotCol = mix(col, vec3(1.0), 0.18) * corePulse;
    if (hit > 0.5) {
        vec3 p = ro + rd * t;
        vec3 n = normalAt(p);
        if (mat > 1.5) {
            // Copper coils in the second colour, catching the core's light.
            vec3 l = normalize(-p);
            float diff = max(dot(n, l), 0.0);
            float fres = pow(1.0 - max(dot(n, -rd), 0.0), 2.0);
            float spec = pow(max(dot(reflect(rd, n), normalize(vec3(-0.4, 0.6, -1.0))), 0.0), 24.0);
            c = col2 * (0.12 + 0.55 * diff + 0.5 * fres) * boot + mix(col2, vec3(1.0), 0.5) * spec;
        } else if (mat > 0.5) {
            // Hotter towards the middle: a swirling white-hot core, the theme colour outside.
            float pr2 = length(p.xy);
            float heat = smoothstep(0.6, 0.0, pr2);
            float swirl = 0.5 + 0.5 * sin(atan(p.y, p.x) * 3.0 + pr2 * 14.0 - uTime * 3.0 * uEnergy);
            float rings = 0.5 + 0.5 * sin(pr2 * 60.0 - uTime * 6.0 * uEnergy);
            c = mix(hotCol * (1.25 + 0.3 * swirl + 0.2 * rings), vec3(1.0, 0.97, 0.97) * 1.7, heat * 0.55) * boot;
        } else {
            // Dark metal lit by the core and a rim of the theme colour.
            vec3 l = normalize(-p);
            float diff = max(dot(n, l), 0.0);
            float fres = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
            vec3 refl = reflect(rd, n);
            float spec = pow(max(dot(refl, normalize(vec3(-0.4, 0.6, -1.0))), 0.0), 40.0);
            c = vec3(0.05, 0.045, 0.05) + col * (0.35 * diff + 0.9 * fres) * boot + vec3(spec * 0.8);
        }
    }
    c += hotCol * glowAcc * boot;

    // Bloom round the core, and light rays streaming out through the coils.
    float rr = length(uv);
    c += col * 0.55 * exp(-rr * 3.0) * corePulse * boot;
    float ang = atan(uv.y, uv.x);
    float rays = pow(0.5 + 0.5 * sin(ang * 10.0 + gSpin * 0.25 * 10.0), 6.0) * 0.6 + 0.4 * pow(0.5 + 0.5 * sin(ang * 23.0 - uTime * 0.7), 10.0);
    c += col * rays * smoothstep(0.45, 0.75, rr) * exp(-(rr - 0.6) * 2.2) * 0.35 * corePulse * boot * (1.0 - hit);

    // A message: a shockwave in the app's colour.
    float pr = uPulseT * 1.4;
    c += uPulseColor * exp(-abs(rr - pr) * 28.0) * exp(-uPulseT * 1.6) * 1.8;

    // A call: two comets circling the reactor.
    if (uCall > 0.0) {
        for (int k = 0; k < 2; k++) {
            float ang = uTime * 4.0 + float(k) * PI;
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
        vec2 ep = (frag / uRes.x) * 9.0 * depth + vec2(float(k) * 13.1, uTime * (0.35 / depth) * uEnergy) + uTilt * 0.4 * float(k);
        vec2 cell = floor(ep);
        vec2 f = fract(ep) - 0.5;
        float h = hash(cell + float(k));
        vec2 off = vec2(h - 0.5, hash(cell * 1.7) - 0.5) * 0.6;
        float d = length(f - off);
        float tw = 0.5 + 0.5 * sin(uTime * 3.0 + h * 40.0);
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
