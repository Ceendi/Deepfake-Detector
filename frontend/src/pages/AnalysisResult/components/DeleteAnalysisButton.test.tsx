import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/errors'

import { DeleteAnalysisButton } from './DeleteAnalysisButton'

vi.mock('@/api/analysis', () => ({ deleteAnalysisRecord: vi.fn() }))
vi.mock('@/api/files', () => ({ deleteFile: vi.fn() }))

import { deleteAnalysisRecord } from '@/api/analysis'
import { deleteFile } from '@/api/files'

beforeAll(() => {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.open = true
  }
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
    this.open = false
  }
})

beforeEach(() => {
  vi.resetAllMocks()
})

function renderButton() {
  render(
    <MemoryRouter initialEntries={['/analysis-result/an-1']}>
      <Routes>
        <Route
          path="/analysis-result/:id"
          element={<DeleteAnalysisButton analysis={{ id: 'an-1', fileId: 'f-1' }} />}
        />
        <Route path="/history" element={<p>Historia</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

async function confirmDelete() {
  fireEvent.click(screen.getByRole('button', { name: 'Usuń analizę' }))
  const buttons = screen.getAllByRole('button', { name: 'Usuń analizę', hidden: true })
  fireEvent.click(buttons[buttons.length - 1])
}

describe('DeleteAnalysisButton', () => {
  it('usuwa rekord i wraca do historii; backend zwalnia współdzielony plik', async () => {
    vi.mocked(deleteAnalysisRecord).mockResolvedValue(undefined)
    vi.mocked(deleteFile).mockResolvedValue(undefined)
    renderButton()

    await confirmDelete()

    await waitFor(() => expect(screen.getByText('Historia')).toBeInTheDocument())
    expect(deleteAnalysisRecord).toHaveBeenCalledWith('an-1')
    expect(deleteFile).not.toHaveBeenCalled()
  })

  it('404 rekordu wraca do historii bez usuwania pliku', async () => {
    vi.mocked(deleteAnalysisRecord).mockRejectedValue(new ApiError({ status: 404, message: 'x' }))
    renderButton()
    await confirmDelete()
    await waitFor(() => expect(screen.getByText('Historia')).toBeInTheDocument())
    expect(deleteFile).not.toHaveBeenCalled()
  })

  it('503 backendu pozostawia możliwość bezpiecznego ponowienia', async () => {
    vi.mocked(deleteAnalysisRecord)
      .mockRejectedValueOnce(new ApiError({ status: 503, message: 'x' }))
      .mockResolvedValueOnce(undefined)
    renderButton()
    await confirmDelete()
    expect(
      await screen.findByText('Nie udało się usunąć analizy. Spróbuj ponownie.'),
    ).toBeInTheDocument()
    const buttons = screen.getAllByRole('button', { name: 'Usuń analizę', hidden: true })
    fireEvent.click(buttons[buttons.length - 1])
    await waitFor(() => expect(screen.getByText('Historia')).toBeInTheDocument())
    expect(deleteAnalysisRecord).toHaveBeenCalledTimes(2)
    expect(deleteFile).not.toHaveBeenCalled()
  })

  it('409 rekordu (analiza w toku) nie usuwa pliku i pokazuje komunikat', async () => {
    vi.mocked(deleteAnalysisRecord).mockRejectedValue(
      new ApiError({ status: 409, message: 'Analysis is still active' }),
    )
    renderButton()

    await confirmDelete()

    expect(
      await screen.findByText('Analiza jest jeszcze w toku — anuluj ją przed usunięciem.'),
    ).toBeInTheDocument()
    expect(deleteFile).not.toHaveBeenCalled()
    expect(screen.queryByText('Analysis is still active')).not.toBeInTheDocument()
  })
})
