import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { Alert } from './Alert'

describe('Alert', () => {
  it('renderuje tytuł i treść', () => {
    render(
      <Alert variant="info" title="Uwaga">
        Coś się dzieje
      </Alert>,
    )
    expect(screen.getByText('Uwaga')).toBeInTheDocument()
    expect(screen.getByText('Coś się dzieje')).toBeInTheDocument()
  })

  // Logika: danger jest asertywny (role="alert"), reszta to grzeczny role="status".
  it('wariant danger ma role="alert"', () => {
    render(
      <Alert variant="danger" title="Błąd">
        boom
      </Alert>,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('pozostałe warianty mają role="status"', () => {
    render(
      <Alert variant="success" title="OK">
        zrobione
      </Alert>,
    )
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('bez onClose nie ma przycisku zamknięcia', () => {
    render(
      <Alert variant="info" title="x">
        y
      </Alert>,
    )
    expect(screen.queryByRole('button', { name: 'Zamknij' })).toBeNull()
  })

  it('z onClose renderuje przycisk i woła go po kliknięciu', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <Alert variant="info" title="x" onClose={onClose}>
        y
      </Alert>,
    )
    await user.click(screen.getByRole('button', { name: 'Zamknij' }))
    expect(onClose).toHaveBeenCalledOnce()
  })
})
