import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { AnalysisAgeLimitError } from '@/api/watch-analysis'
import type { Analysis } from '@/api/types'
import { LiveProgress } from './LiveProgress'
vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))
vi.mock('../use-analysis-stream', () => ({ useAnalysisStream: vi.fn(() => ({})) }))
import { useAnalysisStream } from '../use-analysis-stream'
it('distinguishes the absolute analysis age from a connection failure', async () => {
  vi.mocked(useAnalysisStream).mockImplementation((_id, _result, onError) => {
    queueMicrotask(() => onError(new AnalysisAgeLimitError()))
    return {}
  })
  render(
    <LiveProgress
      analysis={{ id: 'old', type: 'VIDEO', createdAt: '2026-10-15T00:00:00Z' } as Analysis}
      onSettled={vi.fn()}
    />,
  )
  expect(await screen.findByRole('status')).toHaveTextContent(
    'Przekroczono czas oczekiwania na analizę',
  )
  expect(screen.queryByText('Utracono połączenie z analizą')).not.toBeInTheDocument()
})
