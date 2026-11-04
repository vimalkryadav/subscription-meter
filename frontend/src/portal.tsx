import ErrorBoundary from './shared/ErrorBoundary';
import { createRoot } from 'react-dom/client';
import PortalApp from './portal/PortalApp';
createRoot(document.getElementById('root')!).render(<ErrorBoundary><PortalApp /></ErrorBoundary>);
