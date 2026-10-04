import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import fixtures from '../../../../orchestrator/src/test/resources/contracts/audio-score-results.json'
import type { Analysis, SourceDetails } from '@/api/types'

import { AudioTimeline } from './components/AudioTimeline'
import { ModalitySection } from './components/ModalitySection'
import { SummarySection } from './components/SummarySection'
import { audioOutcome, parseAudioSegments, sourceOutcome } from './result-utils'

function analysis(prob: number, details: SourceDetails): Analysis {
  return {
    id: 'audio-contract',
    userId: 'alice',
    fileId: 'f',
    fileKey: 'k',
    type: 'AUDIO',
    status: 'COMPLETED',
    verdict: 'REAL',
    confidence: 0,
    videoProb: null,
    audioProb: prob,
    details: { audio: details },
    errorMessage: null,
    createdAt: '2026-10-04T10:00:00Z',
    updatedAt: '2026-10-04T10:00:00Z',
  }
}

describe('audio score contract', () => {
  it.each(fixtures)('interprets $label using the published decision scale', (fixture) => {
    const { result } = fixture.payload
    const details = {
      modelVersion: result.model_version,
      metadata: result.metadata,
      gradcamKeys: [],
      gradcamUrls: [],
    }
    expect(audioOutcome(result.prob_fake, details)?.verdict).toBe(result.verdict)
    expect(audioOutcome(result.prob_fake, details)?.confidence).toBe(result.confidence)
    expect(parseAudioSegments(result.metadata)[0].prob_fake).toBe(result.prob_fake)
  })

  it('uses REAL at equality and distance confidence', () => {
    expect(sourceOutcome(0.5)).toEqual({ verdict: 'REAL', confidence: 0 })
    expect(sourceOutcome(0.5001).verdict).toBe('FAKE')
    expect(sourceOutcome(0.4).confidence).toBeCloseTo(0.2)
  })

  it('renders threshold equality as REAL in both source descriptions', () => {
    const fixture = fixtures.find((f) => f.label === 'accurate-threshold')!
    const details = {
      modelVersion: 'v1.3.0-accurate',
      metadata: fixture.payload.result.metadata,
      gradcamKeys: [],
      gradcamUrls: [],
    }
    render(
      <>
        <ModalitySection analysis={analysis(0.5, details)} />
        <SummarySection analysis={analysis(0.5, details)} />
      </>,
    )
    expect(screen.getByText('REAL')).toBeInTheDocument()
    expect(
      screen.getByText('Dźwięk: głos nie wykazuje oznak sztucznego generowania.'),
    ).toBeInTheDocument()
  })

  it('preserves historical source verdicts and does not color raw segments on the new scale', () => {
    const details: SourceDetails = {
      modelVersion: 'v1.2.0-fast',
      verdict: 'FAKE',
      confidence: 0.1,
      gradcamKeys: [],
      gradcamUrls: [],
      metadata: {
        threshold_used: 0.3049,
        segment_predictions: [{ start_time: 0, end_time: 1, prob_fake: 0.4 }],
      },
    }
    expect(audioOutcome(0.4, details)?.verdict).toBe('FAKE')
    expect(audioOutcome(0.4, undefined)).toBeNull()
    expect(parseAudioSegments(details.metadata)).toEqual([])
    render(
      <>
        <ModalitySection analysis={analysis(0.4, details)} />
        <SummarySection analysis={analysis(0.4, details)} />
        <AudioTimeline metadata={details.metadata} />
      </>,
    )
    expect(screen.getByText('FAKE')).toBeInTheDocument()
    expect(screen.getByText(/Wynik archiwalny:/)).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /Oś czasu ryzyka audio/ })).not.toBeInTheDocument()
    expect(
      screen.getByText('Dźwięk: głos wykazuje cechy mowy generowanej lub sklonowanej sztucznie.'),
    ).toBeInTheDocument()
  })
})
