// The service worker that makes the app installable and lets it open without a connection.
//
// It caches the app shell -- the page and its built JS/CSS -- and nothing else. /api is never
// touched: every number on screen comes live from the server, so there's no stale data to show,
// and nothing private is left in the browser's cache after signing out.
//
//   - Page loads go to the network first, so a new deploy shows up on the next load; the cached
//     copy is only the fallback for when the server can't be reached.
//   - /assets/* are content-hashed by Vite, so a cached file never goes stale: cache first. Each
//     fresh page load prunes the files the new build no longer uses -- following references from
//     the page into its scripts, since every page but the first is a chunk only scripts name -- so
//     old builds don't pile up.
//
// Bump VERSION only to force every cache to be thrown away (a change to this file is picked up by
// the browser on its own).
const VERSION = "v1";
const SHELL_CACHE = `shell-${VERSION}`;
const ASSET_CACHE = `assets-${VERSION}`;
const SHELL_URL = "/";

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => cache.add(SHELL_URL))
      // Nothing to wait for -- the old worker only ever served the same kind of shell.
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL_CACHE && k !== ASSET_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;

  if (request.mode === "navigate") {
    event.respondWith(page(request));
  } else if (url.pathname.startsWith("/assets/")) {
    event.respondWith(asset(request));
  }
  // Anything else (icons, the manifest) goes to the network as normal.
});

async function page(request) {
  try {
    const response = await fetch(request);
    if (response.ok) {
      const copy = response.clone();
      const cache = await caches.open(SHELL_CACHE);
      await cache.put(SHELL_URL, copy.clone());
      await pruneAssets(await copy.text());
    }
    return response;
  } catch {
    const cached = await caches.match(SHELL_URL);
    return cached ?? new Response("Spending Analyzer can't reach its server.", {
      status: 503,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });
  }
}

async function asset(request) {
  const cached = await caches.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok) {
    const cache = await caches.open(ASSET_CACHE);
    await cache.put(request, response.clone());
  }
  return response;
}

/**
 * Drops cached build files the current build no longer uses. "Uses" follows references through the
 * JS too, not just the page: each page past the first is a chunk named only inside the entry script,
 * and pruning by the page alone would throw those away on every load (and break them offline).
 */
async function pruneAssets(html) {
  const referenced = new Set();
  const pending = [...assetPaths(html)];
  while (pending.length > 0) {
    const path = pending.pop();
    if (referenced.has(path)) continue;
    referenced.add(path);
    if (!path.endsWith(".js")) continue;
    try {
      const response = await asset(new Request(path));
      if (response.ok) pending.push(...assetPaths(await response.clone().text()));
    } catch {
      // Can't read it now: keep what's cached rather than guess.
      return;
    }
  }
  const cache = await caches.open(ASSET_CACHE);
  for (const request of await cache.keys()) {
    if (!referenced.has(new URL(request.url).pathname)) await cache.delete(request);
  }
}

/**
 * Every /assets/ file a page or script names: the page as "/assets/x", the entry script's preload
 * list as "assets/x", and one chunk importing another (both in /assets/) as "./x.js".
 */
function assetPaths(text) {
  return (text.match(/\/?assets\/[^"'`\s)]+|\.\/[\w.-]+\.(?:js|css)/g) ?? []).map((p) =>
    p.startsWith("./") ? `/assets/${p.slice(2)}` : p.startsWith("/") ? p : `/${p}`
  );
}
