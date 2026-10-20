import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ErrorBoundary } from './ErrorBoundary'

function Boom(): never {
  throw new Error('render failed')
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('ErrorBoundary', () => {
  it('renders children when nothing fails', () => {
    render(<ErrorBoundary>ok</ErrorBoundary>)
    expect(screen.getByText('ok')).toBeInTheDocument()
  })

  it('shows the default fallback, logs the error and offers a reload', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const reload = vi.fn()
    vi.stubGlobal('location', { reload })
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Coś poszło nie tak')
    expect(consoleError).toHaveBeenCalledWith('Nieobsłużony błąd UI:', expect.any(Error))
    fireEvent.click(screen.getByRole('button', { name: 'Odśwież stronę' }))
    expect(reload).toHaveBeenCalled()
  })

  it('uses a custom fallback', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    render(
      <ErrorBoundary fallback={<p>własny komunikat</p>}>
        <Boom />
      </ErrorBoundary>,
    )
    expect(screen.getByText('własny komunikat')).toBeInTheDocument()
  })
})
