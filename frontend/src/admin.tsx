import ErrorBoundary from './shared/ErrorBoundary';
import { createRoot } from 'react-dom/client';
import AdminApp from './admin/AdminApp';
createRoot(document.getElementById('root')!).render(<ErrorBoundary><AdminApp /></ErrorBoundary>);
