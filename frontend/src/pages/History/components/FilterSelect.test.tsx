import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { FilterSelect } from './FilterSelect'

const options = [
  { value: 'ALL', label: 'Wszystkie' },
  { value: 'FAKE', label: 'FAKE' },
  { value: 'REAL', label: 'REAL' },
]

describe('FilterSelect', () => {
  it('shows the selected option and changes it from the list', () => {
    const onChange = vi.fn()
    render(<FilterSelect label="Werdykt" value="ALL" options={options} onChange={onChange} />)
    const toggle = screen.getByRole('button', { name: /Werdykt: Wszystkie/ })
    fireEvent.click(toggle)
    expect(screen.getByRole('option', { name: 'Wszystkie' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    fireEvent.click(screen.getByRole('option', { name: 'FAKE' }))
    expect(onChange).toHaveBeenCalledWith('FAKE')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('falls back to the first option for an unknown value', () => {
    render(<FilterSelect label="Werdykt" value="UNKNOWN" options={options} onChange={vi.fn()} />)
    expect(screen.getByRole('button', { name: /Werdykt: Wszystkie/ })).toBeInTheDocument()
  })

  it('closes on Escape and on an outside click, but not on other keys or inside clicks', () => {
    render(<FilterSelect label="Werdykt" value="REAL" options={options} onChange={vi.fn()} />)
    const toggle = screen.getByRole('button', { name: /Werdykt: REAL/ })

    fireEvent.click(toggle)
    fireEvent.keyDown(document, { key: 'Enter' })
    fireEvent.mouseDown(screen.getByRole('listbox'))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    fireEvent.click(toggle)
    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    fireEvent.click(toggle)
    fireEvent.click(toggle)
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })
})
