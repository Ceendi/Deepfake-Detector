import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import { Badge } from './Badge'

describe('Badge', () => {
  it('renderuje treść', () => {
    render(<Badge variant="success">REAL</Badge>)
    expect(screen.getByText('REAL')).toBeInTheDocument()
  })

  it('renderuje ikonę wariantu (dekoracyjną, aria-hidden)', () => {
    const { container } = render(<Badge variant="danger">FAKE</Badge>)
    // Ikona jest dekoracyjna (aria-hidden), więc nie ma roli — sprawdzamy obecność svg.
    expect(container.querySelector('svg')).toBeInTheDocument()
  })
})
