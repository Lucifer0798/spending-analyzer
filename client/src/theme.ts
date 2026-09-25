export type Theme = "light" | "dark" | "system";

const STORAGE_KEY = "theme";

/** "system" is never stored literally -- its absence from storage *is* "system", so a later
 *  OS-level change is picked up on the next load without needing to revisit this setting. */
export function getStoredTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "system";
  } catch {
    return "system";
  }
}

function prefersDark(): boolean {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

/** Applies a theme to the page and remembers it for next time. */
export function applyTheme(theme: Theme): void {
  const dark = theme === "system" ? prefersDark() : theme === "dark";
  const root = document.documentElement;
  root.classList.toggle("dark", dark);
  // Also affects native form controls and scrollbars, which the dark: class alone doesn't reach.
  root.style.colorScheme = dark ? "dark" : "light";

  try {
    if (theme === "system") localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Private browsing or storage disabled -- the toggle still works for this page load, it
    // just won't be remembered for the next one.
  }
}
