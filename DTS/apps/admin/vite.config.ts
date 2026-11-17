import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
// 部署适配：
// - base 默认 '/'（Nginx 根路径）；子路径部署时用 `vite build --base=/dt/`
//   或设置 VITE_BASE=/dt/，所有静态资源（models/data/cesium）会自动带上前缀。
// - dev 下 /api 代理到 Easegress 网关（VITE_DEV_API_TARGET），生产由 Nginx 反代。
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
    // 依赖预构建：Cesium 体积大，提前优化避免开发期卡顿
    optimizeDeps: {
      include: ['cesium', 'echarts', 'vue', 'vue-router', 'pinia', 'element-plus'],
    },
    server: {
      host: '0.0.0.0', // 允许通过服务器 IP 访问进行联调
      port: 5173,
      proxy: {
        // 开发环境把同源 /api 转发到 Easegress 网关；生产环境改由 Nginx 反代
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://127.0.0.1:8080',
          changeOrigin: true,
        },
      },
    },
    preview: {
      host: '0.0.0.0',
      port: 4173,
    },
    css: {
      preprocessorMaxWorkers: 0,
    },
    build: {
      outDir: 'dist',
      assetsDir: 'assets',
      sourcemap: false,
      // Cesium/ECharts 体积大，提高阈值并在下方手动分包
      chunkSizeWarningLimit: 1600,
      rollupOptions: {
        output: {
          // 将第三方大库拆成独立 chunk，利于 Nginx 长缓存与并行加载
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined
            if (id.includes('cesium')) return 'vendor-cesium'
            if (id.includes('echarts') || id.includes('zrender')) return 'vendor-echarts'
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element'
            if (id.includes('@vue') || id.includes('vue') || id.includes('pinia')) return 'vendor-vue'
            return 'vendor'
          },
        },
      },
    },
  }
})
