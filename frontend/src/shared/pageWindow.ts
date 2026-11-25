export const pageSizes = [10, 25, 50] as const;
export type PageSize = typeof pageSizes[number];

export function pageWindow(total: number, requested: number, size: PageSize) {
  const pages = Math.max(1, Math.ceil(total / size));
  const page = Math.max(0, Math.min(requested, pages - 1));
  const start = page * size;
  return { page, pages, start, end: Math.min(start + size, total) };
}
