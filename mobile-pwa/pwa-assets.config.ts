import { defineConfig, minimal2023Preset } from '@vite-pwa/assets-generator/config';

// Run `npm run generate-icons` after changing src/assets/brand/logo-mark.png (the HandOffly symbol, from the approved logo): it writes the PNG and .ico
// icons next to it; move them into public/. The logo was drawn on white, so the maskable and Apple icons, which cannot be transparent, get a white background.
export default defineConfig({
  preset: {
    ...minimal2023Preset,
    // The symbol is wider than it is tall: the padding keeps it inside the safe area that a maskable icon's mask never cuts.
    maskable: { ...minimal2023Preset.maskable, resizeOptions: { background: '#ffffff', fit: 'contain' }, padding: 0.18 },
    apple: { ...minimal2023Preset.apple, resizeOptions: { background: '#ffffff', fit: 'contain' }, padding: 0.12 },
  },
  images: ['src/assets/brand/logo-mark.png'],
});
