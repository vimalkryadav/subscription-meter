import './pagination.css';
import { useState } from 'react';
import { pageSizes, pageWindow, type PageSize } from './pageWindow';

export function usePagination(total: number) {
  const [requested, setPage] = useState(0);
  const [size, setSize] = useState<PageSize>(10);
  // Clamp immediately on filtering/deletion, and don't restore an obsolete page if rows return.
  const window = pageWindow(total, requested, size);
  if (requested !== window.page) setPage(window.page);
  return { ...window, size, setPage, setSize: (next: PageSize) => { setSize(next); setPage(0); } };
}

export function Pagination({ total, paging, label }: { total: number; paging: ReturnType<typeof usePagination>; label: string }) {
  if (total <= pageSizes[0]) return null;
  return <nav className="workspace-pagination" aria-label={label + ' pagination'}>
    <span aria-live="polite">{paging.start + 1}–{paging.end} of {total.toLocaleString()} · Page {paging.page + 1} of {paging.pages}</span>
    <div className="workspace-pagination-controls">
      <label>Rows per page <select value={paging.size} onChange={event => {
        const size = pageSizes.find(n => n === Number(event.target.value));
        if (size) paging.setSize(size);
      }}>{pageSizes.map(size => <option key={size} value={size}>{size}</option>)}</select></label>
      <button type="button" disabled={paging.page === 0} onClick={() => paging.setPage(paging.page - 1)}>Previous</button>
      <button type="button" disabled={paging.page + 1 >= paging.pages} onClick={() => paging.setPage(paging.page + 1)}>Next</button>
    </div>
  </nav>;
}
