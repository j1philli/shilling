export const pages = ["home", "plan", "activity", "receipts", "settings", "import"];

// Only public, top-level navigation may cross the host boundary. No entity IDs,
// query strings, arbitrary URLs, credentials or financial content.
export function pageFromPath(path) {
  return typeof path === "string" && pages.includes(path.slice(1)) && path.startsWith("/")
    ? path.slice(1) : "home";
}

export function configFromEnv(env = process.env) {
  const port = Number(env.SHILLING_CHATGPT_PORT || 8787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid SHILLING_CHATGPT_PORT");
  const url = new URL(env.SHILLING_CHATGPT_PUBLIC_URL || `http://localhost:${port}`);
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/" ||
      (url.protocol !== "https:" && !(loopback && url.protocol === "http:"))) {
    throw new Error("SHILLING_CHATGPT_PUBLIC_URL must be an HTTPS origin (HTTP loopback is allowed for development)");
  }
  return { port, origin: url.origin, bind: env.SHILLING_CHATGPT_BIND || "127.0.0.1" };
}
