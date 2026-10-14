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
  // jsdom nie implementuje <dialog>.showModal/close.
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
  fireEvent.click(buttons[buttons.length - 1]) // przycisk potwierdzenia w dialogu
}

describe('DeleteAnalysisButton', () => {
  it('usuwa rekord, potem plik (zwolnienie kwoty) i wraca do historii', async () => {
    vi.mocked(deleteAnalysisRecord).mockResolvedValue(undefined)
    vi.mocked(deleteFile).mockResolvedValue(undefined)
    renderButton()

    await confirmDelete()

    await waitFor(() => expect(screen.getByText('Historia')).toBeInTheDocument())
    expect(deleteAnalysisRecord).toHaveBeenCalledWith('an-1')
    expect(deleteFile).toHaveBeenCalledWith('f-1')
    expect(vi.mocked(deleteAnalysisRecord).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(deleteFile).mock.invocationCallOrder[0],
    )
  })

  it('404 przy usuwaniu pliku jest ignorowane', async () => {
    vi.mocked(deleteAnalysisRecord).mockResolvedValue(undefined)
    vi.mocked(deleteFile).mockRejectedValue(new ApiError({ status: 404, message: 'x' }))
    renderButton()

    await confirmDelete()

    await waitFor(() => expect(screen.getByText('Historia')).toBeInTheDocument())
  })

  it('inny błąd usuwania pliku (np. 503) jest pokazany, a ponowienie zwalnia już tylko plik', async () => {
    vi.mocked(deleteAnalysisRecord).mockResolvedValue(undefined)
    vi.mocked(deleteFile)
      .mockRejectedValueOnce(new ApiError({ status: 503, message: 'x' }))
      .mockResolvedValueOnce(undefined)
    renderButton()

    await confirmDelete()

    expect(
      await screen.findByText(/Analiza została usunięta, ale nie udało się zwolnić miejsca/),
    ).toBeInTheDocument()
    expect(screen.queryByText('Historia')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Spróbuj ponownie', hidden: true }))

    await waitFor(() => expect(screen.getByText('Historia')).toBeInTheDocument())
    expect(deleteAnalysisRecord).toHaveBeenCalledTimes(1)
    expect(deleteFile).toHaveBeenCalledTimes(2)
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
