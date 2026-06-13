import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { Pagination } from './Pagination'

describe('Pagination', () => {
  it('nie renderuje nic gdy jest ≤1 strona', () => {
    const { container } = render(<Pagination page={0} totalPages={1} onChange={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('na pierwszej stronie strzałka „wstecz" jest wyłączona', () => {
    render(<Pagination page={0} totalPages={5} onChange={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Poprzednia strona' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Następna strona' })).toBeEnabled()
  })

  it('na ostatniej stronie strzałka „dalej" jest wyłączona', () => {
    render(<Pagination page={4} totalPages={5} onChange={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Następna strona' })).toBeDisabled()
  })

  it('klik numeru strony woła onChange z indeksem 0-based', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Pagination page={0} totalPages={5} onChange={onChange} />)
    await user.click(screen.getByRole('button', { name: '3' }))
    expect(onChange).toHaveBeenCalledWith(2)
  })

  it('strzałki przesuwają o jedną stronę', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Pagination page={2} totalPages={5} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Następna strona' }))
    expect(onChange).toHaveBeenCalledWith(3)

    await user.click(screen.getByRole('button', { name: 'Poprzednia strona' }))
    expect(onChange).toHaveBeenCalledWith(1)
  })

  it('bieżąca strona ma aria-current="page"', () => {
    render(<Pagination page={2} totalPages={5} onChange={vi.fn()} />)
    expect(screen.getByRole('button', { name: '3' })).toHaveAttribute('aria-current', 'page')
  })

  it('przy wielu stronach wstawia wielokropki i krańce (1 … okno … N)', () => {
    render(<Pagination page={6} totalPages={13} onChange={vi.fn()} />)
    // Okno wokół strony 7: 1 … 6 7 8 … 13 → dwa wielokropki, krańce 1 i 13 obecne.
    expect(screen.getAllByText('…')).toHaveLength(2)
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '13' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '4' })).toBeNull()
  })
})
