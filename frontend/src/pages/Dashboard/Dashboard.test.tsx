import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/analysis', () => ({ getStats: vi.fn(), listAnalyses: vi.fn() }))
vi.mock('@/api/file-meta', () => ({ useFileMeta: () => null }))

import { getStats, listAnalyses } from '@/api/analysis'
import type { AnalysisSummary, UserStats } from '@/api/types'
import { AuthContext, type AuthState } from '@/context/auth-context'
import Dashboard from './Dashboard'

const stats = (overrides: Partial<UserStats> = {}): UserStats => ({
  total: 4,
  byStatus: { completed: 3, failed: 1, cancelled: 0, inProgress: 0 },
  byType: { video: 1, audio: 1, full: 2 },
  verdicts: { fake: 1, real: 2 },
  avgConfidence: 0.8,
  last7Days: 2,
  lastAnalysisAt: '2026-10-20T10:00:00Z',
  ...overrides,
})

const analysis: AnalysisSummary = {
  id: 'a1',
  fileId: 'abcdef1234567890',
  type: 'FULL',
  status: 'COMPLETED',
  verdict: 'FAKE',
  confidence: 0.9,
  createdAt: '2026-10-20T10:00:00Z',
  updatedAt: '2026-10-20T10:01:00Z',
}

function renderDashboard(user: AuthState['user'] = { name: 'Alice Smith' } as AuthState['user']) {
  const auth = { isAuthenticated: true, isLoading: false, user } as AuthState
  return render(
    <AuthContext value={auth}>
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>
    </AuthContext>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('Dashboard', () => {
  it('greets by first name and summarises verdicts and recent analyses', async () => {
    vi.mocked(getStats).mockResolvedValue(stats())
    vi.mocked(listAnalyses).mockResolvedValue({ content: [analysis] } as never)
    renderDashboard()
    expect(await screen.findByRole('heading', { name: 'Cześć, Alice.' })).toBeInTheDocument()
    expect(screen.getByText('↗ +2 w ostatnich 7 dniach')).toBeInTheDocument()
    expect(screen.getByText('33%')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /#abcdef12/ })).toHaveAttribute(
      'href',
      '/analysis-result/a1',
    )
    expect(listAnalyses).toHaveBeenCalledWith(0, 5, expect.any(AbortSignal))
  })

  it('handles a week without analyses and no decided verdicts', async () => {
    vi.mocked(getStats).mockResolvedValue(stats({ last7Days: 0, verdicts: { fake: 0, real: 0 } }))
    vi.mocked(listAnalyses).mockResolvedValue({ content: [] } as never)
    renderDashboard({ username: 'bob' } as AuthState['user'])
    expect(await screen.findByText('brak nowych w tym tygodniu')).toBeInTheDocument()
    expect(screen.getByText('0%')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Cześć, bob.' })).toBeInTheDocument()
  })

  it('shows the empty state for a new user', async () => {
    vi.mocked(getStats).mockResolvedValue(stats({ total: 0 }))
    vi.mocked(listAnalyses).mockResolvedValue({ content: [] } as never)
    renderDashboard(null)
    expect(await screen.findByText('Nie masz jeszcze żadnych analiz.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Cześć, użytkowniku.' })).toBeInTheDocument()
  })

  it('reports a loading failure', async () => {
    vi.mocked(getStats).mockRejectedValue(new Error('down'))
    vi.mocked(listAnalyses).mockResolvedValue({ content: [] } as never)
    renderDashboard()
    expect(await screen.findByText('Nie udało się wczytać analiz')).toBeInTheDocument()
  })

  it('ignores the abort when it unmounts while loading', async () => {
    vi.mocked(getStats).mockImplementation(
      (signal?: AbortSignal) =>
        new Promise((_, reject) => signal?.addEventListener('abort', () => reject(new Error('x')))),
    )
    vi.mocked(listAnalyses).mockResolvedValue({ content: [] } as never)
    const { unmount } = renderDashboard()
    unmount()
    await Promise.resolve()
  })
})
