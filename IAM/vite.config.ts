import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 统一身份与权限管理系统（IAM）独立工程配置
// 部署适配：
// - base 默认 '/'（Nginx 根路径）；子路径部署时用 `vite build --base=/iam/`
//   或设置 VITE_BASE=/iam/。
// - dev 下 /api 代理到后端网关（VITE_DEV_API_TARGET），生产由 Nginx 反代。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const base = process.env.VITE_BASE || env.VITE_BASE || '/'

  return {
    base,
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    optimizeDeps: {
      include: ['vue', 'vue-router', 'pinia', 'element-plus', '@element-plus/icons-vue'],
    },
    server: {
      host: '0.0.0.0', // 允许通过服务器 IP 访问进行联调
      port: 5174, // 与数字孪生前端(5173)错开，避免本地同时启动时端口冲突
      proxy: {
        // 开发环境把同源 /api 转发到 IAM 后端（8081，context-path /api/v1/iam）；生产由 Nginx 反代
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://127.0.0.1:8081',
          changeOrigin: true,
        },
      },
    },
    preview: {
      host: '0.0.0.0',
      port: 4174,
    },
    build: {
      outDir: 'dist',
      assetsDir: 'assets',
      sourcemap: false,
      rollupOptions: {
        output: {
          // 第三方库拆包，利于 Nginx 长缓存
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element'
            if (id.includes('@vue') || id.includes('vue') || id.includes('pinia')) return 'vendor-vue'
            return 'vendor'
          },
        },
      },
    },
  }
})
