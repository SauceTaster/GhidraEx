import { defineConfig } from "vite";

const servicePort = Number.parseInt(process.env.GHIDRAEX_SERVICE_PORT ?? "18787", 10);
if (!Number.isInteger(servicePort) || servicePort < 1 || servicePort > 65_535) {
  throw new Error("GHIDRAEX_SERVICE_PORT must be an integer between 1 and 65535");
}

const engineMode = process.env.VITE_GHIDRAEX_ENGINE_MODE ?? "synthetic";
if (engineMode !== "synthetic" && engineMode !== "real") {
  throw new Error("VITE_GHIDRAEX_ENGINE_MODE must be either synthetic or real");
}

function realGatewayProxy(): Record<string, object> {
  const origin = process.env.GHIDRAEX_GATEWAY_ORIGIN;
  const token = process.env.GHIDRAEX_GATEWAY_TOKEN;
  if (!origin || !/^http:\/\/(127\.0\.0\.1|\[::1\]):[1-9][0-9]{0,4}\/?$/.test(origin)) {
    throw new Error("GHIDRAEX_GATEWAY_ORIGIN must be an explicit loopback HTTP origin");
  }
  if (!token || token.length < 43 || token.length > 128 || !/^[A-Za-z0-9_-]+$/.test(token)) {
    throw new Error("GHIDRAEX_GATEWAY_TOKEN must be a valid out-of-band gateway credential");
  }
  const target = origin.endsWith("/") ? origin.slice(0, -1) : origin;
  return {
    "/engine/v1": {
      target,
      changeOrigin: true,
      followRedirects: false,
      rewrite: (path: string) => path.replace(/^\/engine\/v1(?=\/|$)/, "/v1"),
      configure: (proxy: { on: (event: string, listener: (request: { setHeader: (name: string, value: string) => void }) => void) => void }) => {
        proxy.on("proxyReq", (request) => {
          // The credential exists only in the Vite server process. It is never
          // added to import.meta.env, the browser bundle, a URL, or diagnostics.
          request.setHeader("Authorization", `Bearer ${token}`);
        });
      },
    },
  };
}

export default defineConfig(({ command }) => ({
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: `http://127.0.0.1:${servicePort}`,
        changeOrigin: false,
      },
      ...(engineMode === "real" && command === "serve" ? realGatewayProxy() : {}),
    },
  },
  preview: {
    host: "127.0.0.1",
    port: 4173,
    strictPort: true,
  },
}));
