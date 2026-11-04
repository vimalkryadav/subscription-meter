import { Component, type ErrorInfo, type ReactNode } from 'react';
/** Preserve a recovery action if a rendering error occurs; never log user payloads. */
export default class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(_error: Error, _info: ErrorInfo) { /* No sensitive dashboard telemetry. */ }
  render() {
    if (this.state.failed) return <main style={{ padding: 32, maxWidth: 700, margin: 'auto' }}><h1>Something went wrong</h1><p role="alert">The dashboard could not display this view. Reload the page to try again.</p><button onClick={() => location.reload()}>Reload page</button> <a href="/">Member portal</a></main>;
    return this.props.children;
  }
}
