import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 系统设置与维护系统（SYS）独立工程配置
// 部署适配：
// - base 默认 '/'（Nginx 根路径）；子路径部署时用 `vite build --base=/sys/`
//   或设置 VITE_BASE=/sys/。
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
      port: 5175, // IAM=5174、数字孪生=5173，SYS 用 5175 避免本地同时启动冲突
      proxy: {
        // 登录/登出走 IAM 认证中心（8081）
        '/api/v1/iam': {
          target: env.VITE_DEV_IAM_TARGET || 'http://127.0.0.1:8081',
          changeOrigin: true,
        },
        // SYS 业务接口（8082）
        '/api/v1/sys': {
          target: env.VITE_DEV_SYS_TARGET || 'http://127.0.0.1:8082',
          changeOrigin: true,
        },
      },
    },
    preview: {
      host: '0.0.0.0',
      port: 4176,
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
