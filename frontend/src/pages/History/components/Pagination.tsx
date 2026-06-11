import { ChevronLeft, ChevronRight } from 'lucide-react'
import { clsx } from 'clsx'

import styles from '../History.module.css'

interface PaginationProps {
  page: number // 0-based
  totalPages: number
  onChange: (page: number) => void
}

export function Pagination({ page, totalPages, onChange }: PaginationProps) {
  if (totalPages <= 1) return null

  const current = page + 1 // 1-based do wyświetlania
  const window = pageWindow(current, totalPages)

  return (
    <nav className={styles.pagination} aria-label="Paginacja">
      <button
        type="button"
        className={styles.pageArrow}
        onClick={() => onChange(page - 1)}
        disabled={current === 1}
        aria-label="Poprzednia strona"
      >
        <ChevronLeft size={18} strokeWidth={2} aria-hidden="true" />
      </button>

      {window.map((p, i) =>
        p === 'ellipsis' ? (
          <span key={`gap-${i}`} className={styles.pageEllipsis} aria-hidden="true">
            …
          </span>
        ) : (
          <button
            key={p}
            type="button"
            className={clsx(styles.pageBtn, p === current && styles.pageBtnActive)}
            onClick={() => onChange(p - 1)}
            aria-current={p === current ? 'page' : undefined}
          >
            {p}
          </button>
        ),
      )}

      <button
        type="button"
        className={styles.pageArrow}
        onClick={() => onChange(page + 1)}
        disabled={current === totalPages}
        aria-label="Następna strona"
      >
        <ChevronRight size={18} strokeWidth={2} aria-hidden="true" />
      </button>
    </nav>
  )
}

// Okno stron: pierwsza, ostatnia i 3-elementowe okno wokół bieżącej (przy krańcach przyklejone do
// brzegu), z „…" w lukach. Np. 1 2 3 … 13 / 1 … 6 7 8 … 13 / 1 … 11 12 13.
function pageWindow(current: number, total: number): (number | 'ellipsis')[] {
  if (total <= 5) return Array.from({ length: total }, (_, i) => i + 1)

  let lo = current - 1
  let hi = current + 1
  if (lo < 2) {
    lo = 1
    hi = 3
  }
  if (hi > total - 1) {
    hi = total
    lo = total - 2
  }
  lo = Math.max(1, lo)
  hi = Math.min(total, hi)

  const out: (number | 'ellipsis')[] = []
  if (lo > 1) {
    out.push(1)
    if (lo > 2) out.push('ellipsis')
  }
  for (let p = lo; p <= hi; p++) out.push(p)
  if (hi < total) {
    if (hi < total - 1) out.push('ellipsis')
    out.push(total)
  }
  return out
}
