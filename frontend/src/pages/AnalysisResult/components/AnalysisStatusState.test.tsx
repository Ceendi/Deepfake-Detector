import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import failure from '../../../../../orchestrator/src/test/resources/contracts/audio-no-speech-result.json'
import type { Analysis } from '@/api/types'

import { AnalysisStatusState } from './AnalysisStatusState'

function failedAnalysis(errorMessage: string): Analysis {
  return {
    id: 'no-speech',
    userId: 'alice',
    fileId: 'f',
    fileKey: 'k',
    type: 'FULL',
    status: 'FAILED',
    verdict: null,
    confidence: null,
    videoProb: 0.8,
    audioProb: null,
    details: null,
    errorMessage,
    createdAt: '2026-10-11T10:00:00Z',
    updatedAt: '2026-10-11T10:00:00Z',
  }
}

describe('analysis failure explanation', () => {
  it('explains NO_SPEECH without suggesting file damage or an authenticity verdict', () => {
    render(
      <MemoryRouter>
        <AnalysisStatusState
          analysis={failedAnalysis(`[${failure.error.code}] ${failure.error.message}`)}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('Analiza nie powiodła się')).toBeInTheDocument()
    expect(screen.getAllByText(/Wgraj nagranie z wyraźnie słyszalną mową/)[0]).toBeInTheDocument()
    expect(screen.getByText(`[${failure.error.code}] ${failure.error.message}`)).toBeInTheDocument()
    expect(screen.queryByText(/Plik może być uszkodzony/)).not.toBeInTheDocument()
    expect(screen.queryByText('REAL')).not.toBeInTheDocument()
    expect(screen.queryByText('FAKE')).not.toBeInTheDocument()
  })

  it.each([
    ['NO_AUDIO_TRACK', 'Materiał nie zawiera ścieżki audio'],
    ['MEDIA_TOO_LONG', 'Materiał przekracza dopuszczalną długość'],
  ])('explains %s without suggesting file damage', (code, message) => {
    render(
      <MemoryRouter>
        <AnalysisStatusState analysis={failedAnalysis(`[${code}] ${message}.`)} />
      </MemoryRouter>,
    )
    expect(screen.queryByText(/Plik może być uszkodzony/)).not.toBeInTheDocument()
    expect(screen.getAllByText(new RegExp(message))[0]).toBeInTheDocument()
  })

  it('retains the existing generic failure explanation for other codes', () => {
    render(
      <MemoryRouter>
        <AnalysisStatusState analysis={failedAnalysis('[PROCESSING_ERROR] Decode failed.')} />
      </MemoryRouter>,
    )
    expect(screen.getByText(/Plik może być uszkodzony/)).toBeInTheDocument()
  })
})
