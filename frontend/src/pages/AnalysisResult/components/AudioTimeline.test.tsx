import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { AudioTimeline } from './AudioTimeline'

const contract = { score_contract: 'audio-threshold-v1' }

describe('AudioTimeline', () => {
  it('summarises segments and points at the riskiest one', () => {
    const { container } = render(
      <AudioTimeline
        metadata={{
          ...contract,
          duration_seconds: 20,
          segment_predictions: [
            { start_time: 4, end_time: 8, prob_fake: 0.9 },
            { start_time: 0, end_time: 4, prob_fake: 0.2 },
            { start_time: 12, end_time: 16, prob_fake: 0.4 },
            { bogus: true },
          ],
        }}
      />,
    )
    expect(
      screen.getByRole('img', {
        name: 'Oś czasu ryzyka audio: 3 fragmentów, najwyższe ryzyko 90% w 4–8 s',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText('90%')).toBeInTheDocument()
    expect(screen.getByTitle('12–16 s · 40% ryzyka')).toBeInTheDocument()
    // Adjacent segments merge into one gradient run; the gap after 8 s starts a new run.
    const runs = container.querySelectorAll('[class*="audioRun"]:not([class*="audioRuns"])')
    expect(runs).toHaveLength(2)
    expect((runs[0] as HTMLElement).style.background).toContain('linear-gradient')
    expect(screen.getByText('20 s')).toBeInTheDocument()
  })

  it('takes the timeline length from the segments when metadata has none', () => {
    render(
      <AudioTimeline
        metadata={{
          ...contract,
          segment_predictions: [{ start_time: 0, end_time: 10, prob_fake: 0.6 }],
        }}
      />,
    )
    expect(screen.getByText('10 s')).toBeInTheDocument()
  })

  it('renders nothing without usable segments or duration', () => {
    const { container: noContract } = render(
      <AudioTimeline
        metadata={{ segment_predictions: [{ start_time: 0, end_time: 1, prob_fake: 1 }] }}
      />,
    )
    expect(noContract).toBeEmptyDOMElement()
    const { container: noArray } = render(<AudioTimeline metadata={{ ...contract }} />)
    expect(noArray).toBeEmptyDOMElement()
    const { container: zero } = render(
      <AudioTimeline
        metadata={{
          ...contract,
          segment_predictions: [{ start_time: 0, end_time: 0, prob_fake: 1 }],
        }}
      />,
    )
    expect(zero).toBeEmptyDOMElement()
  })
})
