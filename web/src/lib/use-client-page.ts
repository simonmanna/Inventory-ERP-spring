import { useEffect, useState } from 'react';

/**
 * Pages a list the server returns in one piece, so a year of documents is not rendered as one table. The rows are
 * already loaded: this only bounds what is drawn. `reset` (e.g. the active filter) returns to page 1.
 */
export function useClientPage<T>(rows: T[] | undefined, initialPageSize = 25, reset?: unknown) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(initialPageSize);
  const total = rows?.length ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  useEffect(() => { setPage(1); }, [reset, pageSize]);
  // Rows removed (a refetch after cancelling the last row of the last page): stay on a page that exists.
  useEffect(() => { if (page > totalPages) setPage(totalPages); }, [page, totalPages]);

  return {
    pageRows: (rows ?? []).slice((page - 1) * pageSize, page * pageSize),
    page,
    pageSize,
    total,
    totalPages,
    setPage,
    setPageSize,
  };
}
