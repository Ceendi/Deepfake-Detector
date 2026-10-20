import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

const fileMeta = vi.hoisted(() => ({ value: null as { name?: string; duration?: number } | null }))
vi.mock('@/api/file-meta', () => ({ useFileMeta: () => fileMeta.value }))

import type { AnalysisStatus, AnalysisSummary } from '@/api/types'
import { HistoryRow } from './HistoryRow'

const base: AnalysisSummary = {
  id: 'a1',
  fileId: 'abcdef1234567890',
  type: 'VIDEO',
  status: 'COMPLETED',
  verdict: 'REAL',
  confidence: 0.9,
  createdAt: '2026-10-20T10:00:00Z',
  updatedAt: '2026-10-20T10:01:00Z',
}

function renderRow(analysis: Partial<AnalysisSummary>) {
  return render(
    <MemoryRouter>
      <HistoryRow analysis={{ ...base, ...analysis }} />
    </MemoryRouter>,
  )
}

describe('HistoryRow', () => {
  it('links to the result and shows the file name, duration and verdict', () => {
    fileMeta.value = { name: 'clip.mp4', duration: 3725.4 }
    renderRow({ verdict: 'FAKE' })
    expect(screen.getByRole('link')).toHaveAttribute('href', '/analysis-result/a1')
    expect(screen.getByTitle('clip.mp4')).toBeInTheDocument()
    expect(screen.getByText('01:02:05')).toBeInTheDocument()
    expect(screen.getByText('FAKE')).toBeInTheDocument()
    expect(screen.getByText('Zakończona')).toBeInTheDocument()
  })

  it('falls back to a shortened file id without metadata', () => {
    fileMeta.value = null
    renderRow({ type: 'AUDIO' })
    expect(screen.getByTitle('#abcdef12')).toBeInTheDocument()
    expect(screen.getByText('AUDIO')).toBeInTheDocument()
    expect(screen.queryByText('·')).not.toBeInTheDocument()
  })

  it.each<[AnalysisStatus, string, boolean]>([
    ['PENDING', 'W toku', true],
    ['PROCESSING', 'W toku', true],
    ['FAILED', 'Błąd', false],
    ['CANCELLED', 'Anulowana', false],
  ])('shows %s as "%s"', (status, label, progress) => {
    fileMeta.value = null
    const { container } = renderRow({ status, verdict: null })
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(container.querySelector('[role="progressbar"]') !== null).toBe(progress)
    expect(screen.queryByText('—') !== null).toBe(!progress)
  })
})
