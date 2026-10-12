import { useSyncExternalStore } from "react";

/**
 * Registers public/sw.js -- production builds only. In `npm run dev` Vite serves unbundled modules
 * that change on every save, and a worker caching them would only get in the way.
 */
export function registerServiceWorker() {
  if (!import.meta.env.PROD || !("serviceWorker" in navigator)) return;
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {
      // Not fatal: the app works the same without it, it just can't be installed or open offline.
    });
  });
}

// --- install prompt ---------------------------------------------------------------

/** Chrome and Edge's install event; not in TypeScript's DOM types yet. */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

let deferredPrompt: BeforeInstallPromptEvent | null = null;
const installListeners = new Set<() => void>();
const notifyInstall = () => installListeners.forEach((l) => l());

/**
 * Starts listening for the browser's "this can be installed" event. Called before React renders,
 * since the event can fire before any component has mounted to hear it.
 */
export function captureInstallPrompt() {
  window.addEventListener("beforeinstallprompt", (e) => {
    // Keep the browser's own mini-infobar from appearing; the header button offers it instead.
    e.preventDefault();
    deferredPrompt = e as BeforeInstallPromptEvent;
    notifyInstall();
  });
  window.addEventListener("appinstalled", () => {
    deferredPrompt = null;
    notifyInstall();
  });
}

/**
 * Whether the browser will let us offer installation right now. False on browsers without the
 * event (Safari and Firefox -- there it's Share → Add to Home Screen), and once installed.
 */
export function useCanInstall(): boolean {
  return useSyncExternalStore(
    (listener) => {
      installListeners.add(listener);
      return () => installListeners.delete(listener);
    },
    () => deferredPrompt !== null
  );
}

export async function promptInstall() {
  const prompt = deferredPrompt;
  if (!prompt) return;
  await prompt.prompt();
  await prompt.userChoice;
  // A prompt can only be shown once; the browser fires a fresh event if it can be offered again.
  deferredPrompt = null;
  notifyInstall();
}

// --- connectivity ------------------------------------------------------------------

export function useOnline(): boolean {
  return useSyncExternalStore(
    (listener) => {
      window.addEventListener("online", listener);
      window.addEventListener("offline", listener);
      return () => {
        window.removeEventListener("online", listener);
        window.removeEventListener("offline", listener);
      };
    },
    () => navigator.onLine
  );
}
