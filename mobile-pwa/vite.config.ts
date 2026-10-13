import { defineConfig, loadEnv } from 'vite';
import type { ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// The brand colour (the app's --primary) and the dark page colour, for the manifest and the browser chrome.
const THEME_COLOR = '#2563eb';
const BACKGROUND_COLOR = '#f6f7f9';

/**
 * Passes /api on to the backend. The browser only ever talks to this server (the same origin), so no CORS is involved; the Origin
 * header is dropped on the way so the backend, which only lists its own web apps' origins, does not mistake the call for a
 * cross-origin one. Used by both the dev server and `vite preview`.
 */
function apiProxy(target: string): Record<string, ProxyOptions> {
  return {
    '/api': {
      target,
      changeOrigin: true,
      configure: (proxy) => { proxy.on('proxyReq', (req) => req.removeHeader('origin')); },
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080';
  // Vite refuses requests addressed to a host it does not know. The usual tunnel domains are allowed so the app can be opened on a phone
  // through one; VITE_ALLOWED_HOSTS (comma separated, e.g. m.example.com) adds others.
  const allowedHosts = ['.ngrok-free.dev', '.ngrok-free.app', '.ngrok.app', '.ngrok.io', ...(env.VITE_ALLOWED_HOSTS || '').split(',').map((h) => h.trim()).filter(Boolean)];
  return {
    plugins: [
      react(),
      VitePWA({
        // A new version installs and takes over at once: the app shell is the only thing cached, so there is nothing to migrate.
        registerType: 'autoUpdate',
        includeAssets: ['favicon.ico', 'apple-touch-icon-180x180.png'],
        manifest: {
          name: 'HandOffly',
          short_name: 'HandOffly',
          description: 'Proof of handoff and return tracking, made for the phone.',
          lang: 'en',
          start_url: '/',
          scope: '/',
          id: '/',
          display: 'standalone',
          orientation: 'portrait',
          theme_color: THEME_COLOR,
          background_color: BACKGROUND_COLOR,
          categories: ['business', 'productivity'],
          icons: [
            { src: 'pwa-64x64.png', sizes: '64x64', type: 'image/png' },
            { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
            { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
            { src: 'maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
          ],
        },
        workbox: {
          // Only the built app itself (HTML, scripts, styles, icons) is kept for offline start-up. No API response, handoff,
          // recipient page, PDF or attachment is ever cached: a request to /api is not given to the service worker at all.
          globPatterns: ['**/*.{js,css,html,ico,png,svg,webmanifest}'],
          navigateFallback: 'index.html',
          navigateFallbackDenylist: [/^\/api\//],
          cleanupOutdatedCaches: true,
          clientsClaim: true,
          skipWaiting: true,
          runtimeCaching: [],
        },
      }),
    ],
    server: {
      port: 5180,
      strictPort: true,
      allowedHosts,
      // In development the browser talks to this server and /api is passed on to the backend, so no CORS is involved.
      proxy: apiProxy(apiTarget),
    },
    preview: {
      port: 4180,
      strictPort: true,
      allowedHosts,
      proxy: apiProxy(apiTarget),
    },
    // Logic tests run in plain Node; a component test opts into jsdom with `// @vitest-environment jsdom` on its first line.
    test: { environment: 'node', include: ['src/**/*.test.{ts,tsx}'], setupFiles: ['src/test/setup.ts'] },
  };
});
