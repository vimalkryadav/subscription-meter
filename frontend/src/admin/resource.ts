/** Resource lifecycle independent of rendering; cleanup prevents stale data, errors and completion. */
export function startResource<T>(path: string, fetcher: (path: string, options: RequestInit) => Promise<T>, handlers: {
  success: (value: T) => void; failure: (error: unknown) => void; complete: () => void;
}) {
  const controller = new AbortController();
  let current = true;
  fetcher(path, { signal: controller.signal })
    .then(value => { if (current) handlers.success(value); })
    .catch(error => { if (current && !(error instanceof Error && error.name === 'AbortError')) handlers.failure(error); })
    .finally(() => { if (current) handlers.complete(); });
  return () => { current = false; controller.abort(); };
}
