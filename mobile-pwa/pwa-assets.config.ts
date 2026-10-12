import { defineConfig, minimal2023Preset } from '@vite-pwa/assets-generator/config';

// Run `npm run generate-icons` after changing public/favicon.svg: it writes the PNG and .ico icons next to it.
export default defineConfig({
  preset: {
    ...minimal2023Preset,
    // The background of the maskable and Apple icons, so the glyph is not cropped on a transparent square.
    maskable: { ...minimal2023Preset.maskable, resizeOptions: { background: '#2563eb', fit: 'contain' }, padding: 0.3 },
    apple: { ...minimal2023Preset.apple, resizeOptions: { background: '#2563eb', fit: 'contain' }, padding: 0.2 },
  },
  images: ['public/favicon.svg'],
});
