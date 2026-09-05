import { fileURLToPath, URL } from 'node:url'
import { existsSync, readFileSync } from 'node:fs'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

interface ProjectConfig {
  server?: { address?: string; port?: number }
  frontend?: { devPort?: number; apiProxyTarget?: string; websocketProxyTarget?: string }
}

const configPath = fileURLToPath(new URL('../.config.json', import.meta.url))

function loadProjectConfig(): ProjectConfig {
  if (!existsSync(configPath)) return {}
  try {
    return JSON.parse(readFileSync(configPath, 'utf8')) as ProjectConfig
  } catch (error) {
    throw new Error(`无法读取统一配置文件 ${configPath}`, { cause: error })
  }
}

export default defineConfig(() => {
  const projectConfig = loadProjectConfig()
  const serverAddress = projectConfig.server?.address === '0.0.0.0' ? '127.0.0.1' : projectConfig.server?.address || '127.0.0.1'
  const serverPort = projectConfig.server?.port || 8080
  const apiProxyTarget = projectConfig.frontend?.apiProxyTarget || `http://${serverAddress}:${serverPort}`
  const websocketProxyTarget = projectConfig.frontend?.websocketProxyTarget || apiProxyTarget.replace(/^http/, 'ws')

  return {
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      port: projectConfig.frontend?.devPort || 5173,
      proxy: {
        '/api': apiProxyTarget,
        '/ws': { target: websocketProxyTarget, ws: true },
      },
    },
  }
})
