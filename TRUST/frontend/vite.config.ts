import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import vue from "@vitejs/plugin-vue";
import {
  defineConfig,
  type Plugin,
  type PreviewServer,
  type ViteDevServer,
} from "vite";

const accountsFile = fileURLToPath(
  new URL("../.local/development-accounts.json", import.meta.url),
);

function localQuickLogin(): Plugin {
  const configure = (server: ViteDevServer | PreviewServer) => {
    server.middlewares.use("/__dev/quick-login", async (request, response) => {
      const address = request.socket.remoteAddress || "";
      if (
        request.method !== "GET" ||
        !["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(address)
      ) {
        response.statusCode = 404;
        response.end();
        return;
      }
      try {
        const accounts = JSON.parse(await readFile(accountsFile, "utf8"));
        response.statusCode = 200;
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.setHeader("Cache-Control", "no-store");
        response.end(JSON.stringify({ accounts }));
      } catch {
        response.statusCode = 404;
        response.end();
      }
    });
  };

  return {
    name: "trust-local-quick-login",
    configureServer: configure,
    configurePreviewServer: configure,
  };
}

export default defineConfig({
  plugins: [vue(), localQuickLogin()],
  server: { proxy: { "/api": "http://127.0.0.1:28182" } },
  preview: { proxy: { "/api": "http://127.0.0.1:28182" } },
});
