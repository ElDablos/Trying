// Shared renderer setup for the 3D scenes: glossy black materials, crimson bloom, filmic tone mapping.
import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';

export const CRIMSON = 0xe01e37;

/** A renderer the size of the page, with a soft studio environment so glossy black shows its curves. */
export function setup({ width, height, exposure = 1.0 }) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1);
  renderer.setSize(width, height);
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = exposure;
  document.body.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x030203);
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  return { renderer, scene };
}

/** Glossy black plastic, like the bands in the user's wallpaper. */
export function gloss(params = {}) {
  return new THREE.MeshPhysicalMaterial({
    color: 0x060506,
    metalness: 0.0,
    roughness: 0.22,
    clearcoat: 1,
    clearcoatRoughness: 0.06,
    envMapIntensity: 0.35,
    ...params,
  });
}

/** Light-emitting surfaces, for the neon. */
export function glow(color = CRIMSON, intensity = 4) {
  return new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(intensity) });
}

/**
 * A thick ribbon following a curve in the XY plane: a rounded-rectangle cross-section, [width] across the
 * curve and [thickness] towards the camera. Built by hand so the bands never twist at an S-bend.
 */
export function ribbon(curve2d, { width, thickness, steps = 400, radius = 0.12 }) {
  const profile = [];
  const corner = 6;
  const hw = width / 2 - radius;
  const ht = thickness / 2 - radius;
  for (const [cx, cy, start] of [[hw, ht, 0], [-hw, ht, 90], [-hw, -ht, 180], [hw, -ht, 270]]) {
    for (let i = 0; i <= corner; i++) {
      const a = ((start + (i / corner) * 90) * Math.PI) / 180;
      profile.push([cx + Math.cos(a) * radius, cy + Math.sin(a) * radius]);
    }
  }
  const ring = profile.length;
  const pos = [];
  for (let s = 0; s <= steps; s++) {
    const t = s / steps;
    const p = curve2d.getPoint(t);
    const tan = curve2d.getTangent(t);
    const n = new THREE.Vector2(-tan.y, tan.x);
    for (const [u, v] of profile) pos.push(p.x + n.x * u, p.y + n.y * u, v);
  }
  const index = [];
  for (let s = 0; s < steps; s++) {
    for (let i = 0; i < ring; i++) {
      const a = s * ring + i;
      const b = s * ring + ((i + 1) % ring);
      const c = a + ring;
      const d = b + ring;
      index.push(a, c, b, b, c, d);
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setIndex(index);
  geo.computeVertexNormals();
  return geo;
}

/** The same curve shifted sideways by [offset] and towards the camera by [z], as a 3D path for a tube. */
export function offsetPath(curve2d, offset, z, samples = 300) {
  const pts = [];
  for (let i = 0; i <= samples; i++) {
    const t = i / samples;
    const p = curve2d.getPoint(t);
    const tan = curve2d.getTangent(t);
    pts.push(new THREE.Vector3(p.x - tan.y * offset, p.y + tan.x * offset, z));
  }
  return new THREE.CatmullRomCurve3(pts);
}

/** Renders once with bloom, then flags the page as done for the screenshot script. */
export function finish({ renderer, scene, camera, width, height, bloom = {} }) {
  const composer = new EffectComposer(renderer);
  composer.addPass(new RenderPass(scene, camera));
  composer.addPass(new UnrealBloomPass(
    new THREE.Vector2(width, height), bloom.strength ?? 0.9, bloom.radius ?? 0.5, bloom.threshold ?? 0.9,
  ));
  composer.addPass(new OutputPass());
  composer.render();
  window.__done = true;
}
