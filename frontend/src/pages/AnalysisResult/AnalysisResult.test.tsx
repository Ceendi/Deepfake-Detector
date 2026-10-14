import { act, render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Analysis } from '@/api/types'

import AnalysisResult from './AnalysisResult'

vi.mock('@/api/analysis', () => ({
  getAnalysis: vi.fn(),
  deleteAnalysisRecord: vi.fn(),
  fetchReportPdf: vi.fn(),
}))
vi.mock('@/api/files', () => ({ deleteFile: vi.fn() }))
vi.mock('@/config/env', () => ({
  env: { apiBaseUrl: '/api', keycloakUrl: 'http://kc', keycloakRealm: 'r', keycloakClientId: 'c' },
}))

import { getAnalysis } from '@/api/analysis'

function failed(id: string, fileKey: string): Analysis {
  return {
    id,
    userId: 'u',
    fileId: `f-${id}`,
    fileKey,
    type: 'AUDIO',
    status: 'FAILED',
    verdict: null,
    confidence: null,
    videoProb: null,
    audioProb: null,
    details: null,
    errorMessage: '[PROCESSING_ERROR] x',
    createdAt: '2026-10-11T10:00:00Z',
    updatedAt: '2026-10-11T10:00:00Z',
  }
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('AnalysisResult', () => {
  it('po zmianie id nie pokazuje treści poprzedniej analizy podczas ładowania nowej', async () => {
    vi.mocked(getAnalysis).mockImplementation((id) =>
      id === 'a' ? Promise.resolve(failed('a', 'first.wav')) : new Promise(() => {}),
    )
    const router = createMemoryRouter(
      [{ path: '/analysis-result/:id', element: <AnalysisResult /> }],
      { initialEntries: ['/analysis-result/a'] },
    )
    render(<RouterProvider router={router} />)
    expect(await screen.findByText('first.wav')).toBeInTheDocument()

    await act(async () => {
      await router.navigate('/analysis-result/b')
    })

    expect(screen.queryByText('first.wav')).not.toBeInTheDocument()
    expect(getAnalysis).toHaveBeenLastCalledWith('b', expect.any(AbortSignal))
  })

  it('pozwala usunąć analizę FAILED', async () => {
    vi.mocked(getAnalysis).mockResolvedValue(failed('a', 'first.wav'))
    const router = createMemoryRouter(
      [{ path: '/analysis-result/:id', element: <AnalysisResult /> }],
      { initialEntries: ['/analysis-result/a'] },
    )
    render(<RouterProvider router={router} />)

    expect(await screen.findByRole('button', { name: 'Usuń analizę' })).toBeInTheDocument()
  })
})
