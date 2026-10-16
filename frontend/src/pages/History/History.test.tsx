import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import History from './History'
vi.mock('./use-history', () => ({
  useHistory: () => ({
    state: 'ready',
    items: [
      {
        id: 'one',
        fileId: 'file-one',
        type: 'VIDEO',
        status: 'COMPLETED',
        createdAt: '2026-10-15T12:00:00Z',
      },
    ],
  }),
}))
vi.mock('@/api/file-meta', () => ({
  useFileNames: () => ({ names: new Map(), loading: false, incomplete: true }),
}))
vi.mock('./components/HistoryList', () => ({ HistoryList: () => null }))
it('warns about incomplete filename search, including zero matches', () => {
  render(
    <MemoryRouter>
      <History />
    </MemoryRouter>,
  )
  expect(screen.queryByText(/Wyniki mogą być niepełne/)).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'missing' } })
  expect(screen.getByRole('status')).toHaveTextContent('Wyniki mogą być niepełne')
})
