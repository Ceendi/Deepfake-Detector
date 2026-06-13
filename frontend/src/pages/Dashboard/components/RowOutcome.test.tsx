import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import type { AnalysisSummary } from '@/api/types'
import { RowOutcome } from './RowOutcome'

function summary(overrides: Partial<AnalysisSummary> = {}): AnalysisSummary {
  return {
    id: 'id-1',
    fileId: 'file-abc',
    type: 'VIDEO',
    status: 'COMPLETED',
    verdict: 'REAL',
    confidence: 0.9,
    createdAt: '2026-06-13T10:00:00.000Z',
    updatedAt: '2026-06-13T10:00:00.000Z',
    ...overrides,
  }
}

describe('RowOutcome', () => {
  it('PENDING/PROCESSING → pasek postępu i „Analiza w toku"', () => {
    render(<RowOutcome analysis={summary({ status: 'PROCESSING' })} />)
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(screen.getByText('Analiza w toku')).toBeInTheDocument()
  })

  it('FAILED → „Błąd analizy"', () => {
    render(<RowOutcome analysis={summary({ status: 'FAILED', verdict: null, confidence: null })} />)
    expect(screen.getByText('Błąd analizy')).toBeInTheDocument()
  })

  it('CANCELLED → „Anulowana"', () => {
    render(
      <RowOutcome analysis={summary({ status: 'CANCELLED', verdict: null, confidence: null })} />,
    )
    expect(screen.getByText('Anulowana')).toBeInTheDocument()
  })

  it('COMPLETED + FAKE → badge FAKE i sformatowana pewność', () => {
    render(
      <RowOutcome
        analysis={summary({ status: 'COMPLETED', verdict: 'FAKE', confidence: 0.923 })}
      />,
    )
    expect(screen.getByText('FAKE')).toBeInTheDocument()
    expect(screen.getByText('0.92')).toBeInTheDocument()
    expect(screen.getByText('Zakończona')).toBeInTheDocument()
  })

  it('COMPLETED + REAL bez confidence → badge REAL, brak liczby pewności', () => {
    render(
      <RowOutcome analysis={summary({ status: 'COMPLETED', verdict: 'REAL', confidence: null })} />,
    )
    expect(screen.getByText('REAL')).toBeInTheDocument()
    expect(screen.queryByText(/^\d\.\d{2}$/)).toBeNull()
  })
})
