import {defineConfig} from 'vitest/config';
export default defineConfig({test:{environment:'node',include:['backend/tests/**/*.test.ts'],testTimeout:30_000}});
