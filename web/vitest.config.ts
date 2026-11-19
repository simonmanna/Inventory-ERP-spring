import { defineConfig } from 'vitest/config';
import path from 'node:path';

/**
 * Unit tests of the web app's pure logic (`src/**` files named `*.test.ts`). Kept apart from vite.config.ts so the PWA
 * plugin and the build options stay out of the test run. Tests run in Node: no DOM.
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
});
