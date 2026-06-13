import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import { ProgressBar } from './ProgressBar'

describe('ProgressBar', () => {
  it('tryb określony (value) ustawia aria-valuenow', () => {
    render(<ProgressBar value={42} />)
    const bar = screen.getByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '42')
    expect(bar).toHaveAttribute('aria-valuemin', '0')
    expect(bar).toHaveAttribute('aria-valuemax', '100')
  })

  it('tryb nieokreślony (brak value) → data-indeterminate, bez aria-valuenow', () => {
    render(<ProgressBar />)
    const bar = screen.getByRole('progressbar')
    expect(bar).toHaveAttribute('data-indeterminate', 'true')
    expect(bar).not.toHaveAttribute('aria-valuenow')
  })

  it('przycina wartość do zakresu 0–100', () => {
    const { rerender } = render(<ProgressBar value={150} />)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100')

    rerender(<ProgressBar value={-10} />)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
  })

  it('zaokrągla ułamkowy procent', () => {
    render(<ProgressBar value={42.6} />)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '43')
  })

  it('showValue renderuje procent jako tekst', () => {
    render(<ProgressBar value={42} showValue />)
    expect(screen.getByText('42%')).toBeInTheDocument()
  })

  it('showValue nie pokazuje procentu w trybie nieokreślonym', () => {
    render(<ProgressBar showValue />)
    expect(screen.queryByText(/%$/)).toBeNull()
  })

  it('label staje się dostępną nazwą paska', () => {
    render(<ProgressBar value={10} label="Postęp analizy" />)
    expect(screen.getByRole('progressbar', { name: 'Postęp analizy' })).toBeInTheDocument()
  })
})
