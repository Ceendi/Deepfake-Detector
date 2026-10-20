import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { AnalyzingState } from './AnalyzingState'

const video = new File(['x'], 'clip.mp4', { type: 'video/mp4' })
const audio = new File(['x'], 'voice.wav', { type: 'audio/wav' })

function renderState(props: Partial<Parameters<typeof AnalyzingState>[0]> = {}) {
  return render(
    <MemoryRouter>
      <AnalyzingState file={video} bySource={{}} {...props} />
    </MemoryRouter>,
  )
}

describe('AnalyzingState', () => {
  it('shows per-source progress with stage labels for a full analysis', () => {
    renderState({
      bySource: {
        video: { progress: 40, stage: 'INFERENCE', status: 'PROCESSING' },
        audio: { progress: 10, stage: 'UNKNOWN_STAGE', status: 'PROCESSING' },
      },
    })
    expect(screen.getByRole('progressbar', { name: 'Wideo · Analiza' })).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Audio' })).toBeInTheDocument()
    expect(screen.getByText(/zwykle kilka–kilkanaście sekund/)).toBeInTheDocument()
  })

  it('shows only audio for audio files and audio analyses', () => {
    const { unmount } = renderState({ file: audio })
    expect(screen.queryByRole('progressbar', { name: /Wideo/ })).not.toBeInTheDocument()
    unmount()
    renderState({
      analysisType: 'AUDIO',
      bySource: { audio: { progress: 5, stage: 'EXTRACTING_AUDIO', status: 'PROCESSING' } },
    })
    expect(
      screen.getByRole('progressbar', { name: 'Audio · Ekstrakcja audio' }),
    ).toBeInTheDocument()
  })

  it('offers cancelling and shows a cancel error', () => {
    const onCancel = vi.fn()
    renderState({ onCancel, cancelError: 'Nie udało się anulować.' })
    fireEvent.click(screen.getByRole('button', { name: /Anuluj/ }))
    expect(onCancel).toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('Nie udało się anulować.')
  })

  it.each([
    [{ watching: true }, 'Wynik pojawi się tutaj albo na stronie analizy.'],
    [{ watching: false }, 'Sprawdź jej stan na stronie analizy.'],
    [{ timedOut: true }, 'Przekroczono czas oczekiwania na analizę.'],
  ])('links to a still running analysis (%o)', (flags, message) => {
    renderState({ stillRunningId: 'a1', ...flags })
    expect(screen.getByRole('status')).toHaveTextContent(message)
    expect(screen.getByRole('link', { name: /Przejdź do analizy/ })).toHaveAttribute(
      'href',
      '/analysis-result/a1',
    )
  })
})
