import { Component, Suspense, type ReactNode } from "react";

/**
 * Wraps a page that's loaded on demand: a quiet placeholder while its code downloads, and if the
 * download fails -- offline before it was ever fetched, or a new version deployed while this one
 * was open, so the old file is gone -- a plain message with Reload instead of a blank screen.
 * Keyed by tab where it's used, so moving to another tab clears a failure.
 */
export class PageBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      return (
        <div className="mx-auto max-w-md px-4 py-16 text-center">
          <p className="text-sm text-slate-700 dark:text-slate-300">
            This page couldn't be loaded — you may be offline, or the app was updated.
          </p>
          <button
            onClick={() => window.location.reload()}
            className="mt-3 rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-700"
          >
            Reload
          </button>
        </div>
      );
    }
    return (
      <Suspense fallback={<p className="px-4 py-16 text-center text-sm text-slate-500">Loading…</p>}>
        {this.props.children}
      </Suspense>
    );
  }
}
