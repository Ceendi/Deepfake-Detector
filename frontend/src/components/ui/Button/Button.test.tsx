import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { Button } from './Button'
import styles from './Button.module.css'

describe('Button', () => {
  it('renderuje dzieci jako dostępny przycisk', () => {
    render(
      <Button variant="primary" size="md">
        Wyślij
      </Button>,
    )
    expect(screen.getByRole('button', { name: 'Wyślij' })).toBeInTheDocument()
  })

  it('domyślny type to "button" (nie submituje formularza przypadkiem)', () => {
    render(
      <Button variant="primary" size="md">
        x
      </Button>,
    )
    expect(screen.getByRole('button')).toHaveAttribute('type', 'button')
  })

  it('woła onClick po kliknięciu', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(
      <Button variant="primary" size="md" onClick={onClick}>
        Klik
      </Button>,
    )
    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('disabled blokuje kliknięcie', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(
      <Button variant="primary" size="md" disabled onClick={onClick}>
        Klik
      </Button>,
    )
    const button = screen.getByRole('button')
    expect(button).toBeDisabled()
    await user.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('isLoading → disabled + aria-busy, klik nie przechodzi', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(
      <Button variant="primary" size="md" isLoading onClick={onClick}>
        Ładowanie
      </Button>,
    )
    const button = screen.getByRole('button')
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    await user.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })

  // Wariant nie ma zachowania, tylko styl — to JEST kontrakt design-systemu. Używamy tego samego
  // obiektu `styles` co komponent, więc test jest odporny na zahashowane nazwy klas CSS Modules.
  it('nakłada klasę wariantu', () => {
    render(
      <Button variant="danger" size="sm">
        x
      </Button>,
    )
    expect(screen.getByRole('button')).toHaveClass(styles.danger)
  })
})
