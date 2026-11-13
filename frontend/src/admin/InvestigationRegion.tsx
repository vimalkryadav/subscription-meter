import { useLayoutEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { Loading } from './ui';

/** Keep a refreshed report's space while showing only Loading, without old data flashes. */
export default function InvestigationRegion({ loading, children }: { loading: boolean; children?: ReactNode }) {
  const container = useRef<HTMLDivElement>(null), height = useRef(0);
  useLayoutEffect(() => {
    const node = container.current;
    if (!node || loading) return;
    const measure = () => { height.current = node.getBoundingClientRect().height; };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [loading]);
  return <div ref={container} aria-busy={loading} style={{ display: 'flow-root', ...(loading && height.current ? { minHeight: height.current } : {}) }}>{loading ? <Loading /> : children}</div>;
}
