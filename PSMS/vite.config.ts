import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';
import { railwayDevPlugin } from './scripts/railway-dev-plugin';

export default defineConfig(({ command, isPreview, mode }) => ({
  base: command === 'build' || isPreview ? '/production-dispatch/' : '/',
  server: { proxy: { '/api/railway': 'http://127.0.0.1:3101', '/api': 'http://127.0.0.1:3100' } },
  preview: { proxy: { '/api/railway': 'http://127.0.0.1:3101', '/api': 'http://127.0.0.1:3100' } },
  plugins: [react(), railwayDevPlugin(command==='serve'&&!isPreview&&loadEnv(mode,process.cwd(),'VITE_').VITE_PSMS_REAL_API!=='true')],
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    testTimeout: 30_000,
  },
}));
