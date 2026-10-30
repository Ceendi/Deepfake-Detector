import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { RouteError } from './RouteError'
vi.mock('react-router-dom', () => ({
  useRouteError: () => new TypeError('Failed to fetch dynamically imported module'),
  isRouteErrorResponse: () => false,
}))
it('offers a reload after a stale lazy chunk fails', () => {
  render(<RouteError />)
  expect(screen.getByRole('button', { name: 'Odśwież stronę' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Odśwież stronę' }))
})
