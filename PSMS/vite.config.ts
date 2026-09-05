import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ command }) => ({
  base: command === 'build' ? '/production-dispatch/' : '/',
  plugins: [react()],
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    testTimeout: 30_000,
  },
}));
