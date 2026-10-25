# Gothic Crimson for Theme Park

Everything to build the full Gothic Crimson theme in **Good Lock → Theme Park**: system colours, quick panel,
keyboard, volume panel and icons. Theme Park builds themes on the phone, so this folder has the background
images and the exact colours for each tab.

| File | Used for |
| --- | --- |
| `quick-panel.jpg` | Quick panel background: glossy black 3D bands sweeping down the screen with a crimson neon line, over carbon fibre |
| `keyboard.jpg` | Keyboard background: the same bands running across, dark at the top so the keys stay readable |
| `../wallpapers/*.jpg` | Wallpapers, also used to build the system colours |

Save both images to your phone first (open each on GitHub → **⋯ → Download**).

## Colours

| Name | Hex | Where |
| --- | --- | --- |
| Void | `#0A0708` | Backgrounds |
| Crypt | `#140C0F` | Panels, keys |
| Ash | `#1E1416` | Raised surfaces, buttons that are off |
| Blood | `#B3001B` | Buttons that are on, accents |
| Crimson | `#E01E37` | Main accent, sliders |
| Bone | `#E8DCDC` | Text and icons |
| Dust | `#9A8A8C` | Secondary text |

## Order

Apply in this order: **Theme → Quick panel → Keyboard → Volume → Icon**. Applying a Theme can reset the
icons, so do icons last.

## 1. Theme (system colours)

1. Theme Park → **Theme** tab → **Create new**.
2. Pick `gothic-crimson-void.jpg` (or your current wallpaper). Theme Park suggests colour palettes from it.
3. Choose the darkest red palette. Then tap the colour circles to fine-tune:
   - Main / accent colour: `#E01E37`
   - Background: `#0A0708`
   - Text: `#E8DCDC`
4. Turn **Dark mode** on if there's an option.
5. **Save**, then tap the new theme on the Theme tab and **Apply**.

## 2. Quick panel

1. **Quick panel** tab → **Create new**.
2. Background: choose **Image** and pick `quick-panel.jpg`. Keep blur off or low, and set any dim/transparency
   option to about 20%.
3. Colours:
   - Active buttons: `#B3001B`
   - Inactive buttons: `#1E1416`
   - Icons and text: `#E8DCDC`
   - Brightness slider: `#E01E37`
4. **Save**, then **Apply**.

## 3. Keyboard (Samsung Keyboard)

1. **Keyboard** tab → **Create new**.
2. Background: choose **Image** and pick `keyboard.jpg` (or the colour `#0A0708`).
3. Colours:
   - Keys: `#1E1416`
   - Key text: `#E8DCDC`
   - Special keys (Shift, Enter, Space): `#B3001B`
   - Highlights / pressed keys: `#E01E37`
4. **Save**, then **Apply**. Make sure Samsung Keyboard is your keyboard in **Settings → General management →
   Keyboard list and default**.

## 4. Volume panel

1. **Volume** tab → **Create new**.
2. Colours:
   - Slider / active bar: `#E01E37`
   - Background: `#140C0F`
   - Icons: `#E8DCDC`
3. **Save**, then **Apply**.

## 5. Icons

1. Install `CrimsonIcons.apk` (see [icons/README.md](../../../icons/README.md)).
2. **Icon** tab → **Create new** → pick **Crimson Icons** → **Save** → **Apply**.

## Undo

Each tab lists what you made. Open it and choose the default, or go to **Settings → Themes** and pick another
theme.

## Change the images

Both, and the Crimson Void wallpaper, are real 3D renders (three.js), made to match a glossy black-and-red
carbon wallpaper. The scenes are `../src/3d/bands.html` and `../src/3d/void.html`, with shared materials and
geometry in `../src/3d/common.js`. To re-render:

```
cd theme/gothic-crimson/src/3d
npm install
node render.js
```

It needs Playwright with Chromium, and renders with WebGL in software, so no graphics card is required.
