import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import type { BatchItem } from '../use-batch-queue'
import { BatchQueue } from './BatchQueue'

function item(name: string, patch: Partial<BatchItem> = {}): BatchItem {
  return { key: name, file: new File(['x'], name), status: 'pending', ...patch }
}

function renderQueue(props: Partial<Parameters<typeof BatchQueue>[0]> = {}) {
  const handlers = { onStart: vi.fn(), onCancelAll: vi.fn(), onReset: vi.fn() }
  render(
    <MemoryRouter>
      <BatchQueue
        items={[]}
        skipped={[]}
        started
        finished={false}
        cancelling={false}
        {...handlers}
        {...props}
      />
    </MemoryRouter>,
  )
  return handlers
}

const row = (name: string) => screen.getByRole('listitem', { name })

describe('BatchQueue', () => {
  it('shows the status of each file and a summary counter', () => {
    renderQueue({
      items: [
        item('a.mp4'),
        item('b.mp4', { status: 'uploading', progress: 42.4 }),
        item('c.wav', { status: 'queued' }),
        item('d.mp4', { status: 'analyzing', progress: 30, analysisId: 'an-d' }),
        item('e.mp4', { status: 'completed', verdict: 'FAKE', analysisId: 'an-e' }),
        item('f.mov', { status: 'failed', message: 'Plik jest za duży.' }),
      ],
    })

    expect(row('a.mp4')).toHaveTextContent('Czeka')
    expect(row('b.mp4')).toHaveTextContent('Wysyłanie 42%')
    expect(row('c.wav')).toHaveTextContent('W kolejce do analizy')
    expect(row('d.mp4')).toHaveTextContent('Analiza 30%')
    expect(row('e.mp4')).toHaveTextContent('Gotowe')
    expect(within(row('e.mp4')).getByText('FAKE')).toBeInTheDocument()
    expect(row('f.mov')).toHaveTextContent('Błąd')
    expect(row('f.mov')).toHaveTextContent('Plik jest za duży.')

    expect(screen.getByRole('status')).toHaveTextContent('1/6 gotowe · 1 z błędem')
    expect(screen.getByRole('link', { name: 'Wynik: e.mp4' })).toHaveAttribute(
      'href',
      '/analysis-result/an-e',
    )
    expect(within(row('a.mp4')).queryByRole('link')).not.toBeInTheDocument()
  })

  it('before start offers analysing the valid files and reports skipped ones', () => {
    const { onStart } = renderQueue({
      started: false,
      skipped: ['notatka.txt', 'docs/opis.pdf'],
      items: [item('a.mp4'), item('b.txt', { status: 'failed', message: 'x' }), item('c.wav')],
    })

    expect(
      screen.getByText(
        'Pominięto 2 pliki spoza obsługiwanych formatów: notatka.txt, docs/opis.pdf',
      ),
    ).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Analizuj wszystkie (2)' }))
    expect(onStart).toHaveBeenCalledOnce()
  })

  it.each([
    [6, 'f1.txt, f2.txt, f3.txt, f4.txt, f5.txt i 1 inny'],
    [7, 'f1.txt, f2.txt, f3.txt, f4.txt, f5.txt i 2 inne'],
    [10, 'f1.txt, f2.txt, f3.txt, f4.txt, f5.txt i 5 innych'],
  ])('names the first five of %i skipped files', (count, names) => {
    const skipped = Array.from({ length: count }, (_, index) => `f${index + 1}.txt`)
    renderQueue({ started: false, skipped, items: [item('a.mp4')] })

    expect(
      screen.getByText(`Pominięto ${count} plików spoza obsługiwanych formatów: ${names}`),
    ).toBeInTheDocument()
  })

  it('while running offers cancelling everything, when finished picking new files', () => {
    const { onCancelAll } = renderQueue({ items: [item('a.mp4', { status: 'uploading' })] })
    fireEvent.click(screen.getByRole('button', { name: 'Anuluj wszystko' }))
    expect(onCancelAll).toHaveBeenCalledOnce()
  })

  it('offers picking new files once every file is done', () => {
    const { onReset } = renderQueue({
      finished: true,
      items: [item('a.mp4', { status: 'cancelled' })],
    })
    expect(row('a.mp4')).toHaveTextContent('Anulowano')
    fireEvent.click(screen.getByRole('button', { name: 'Wybierz kolejne pliki' }))
    expect(onReset).toHaveBeenCalledOnce()
  })
})
