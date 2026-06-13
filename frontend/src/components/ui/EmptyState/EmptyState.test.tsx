import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Inbox } from 'lucide-react'

import { EmptyState } from './EmptyState'

describe('EmptyState', () => {
  it('renderuje tytuł jako nagłówek', () => {
    render(<EmptyState icon={Inbox} title="Brak danych" />)
    expect(screen.getByRole('heading', { name: 'Brak danych' })).toBeInTheDocument()
  })

  it('renderuje opcjonalny opis, akcje i hint gdy podane', () => {
    render(
      <EmptyState
        icon={Inbox}
        title="Pusto"
        actions={<button type="button">Dodaj</button>}
        hint="ctrl+n"
      >
        Nic tu jeszcze nie ma
      </EmptyState>,
    )
    expect(screen.getByText('Nic tu jeszcze nie ma')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Dodaj' })).toBeInTheDocument()
    expect(screen.getByText('ctrl+n')).toBeInTheDocument()
  })

  it('pomija opis/akcje/hint gdy ich nie podano', () => {
    render(<EmptyState icon={Inbox} title="Pusto" />)
    expect(screen.queryByRole('button')).toBeNull()
  })
})
