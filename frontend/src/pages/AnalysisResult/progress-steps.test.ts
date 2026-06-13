import { describe, it, expect } from 'vitest'

import { sourcesForType, buildSteps, overallProgress, activeStageLabel } from './progress-steps'

describe('sourcesForType', () => {
  it('rozbija FULL na wideo + audio, single-source zostaje jednym torem', () => {
    expect(sourcesForType('FULL')).toEqual(['video', 'audio'])
    expect(sourcesForType('VIDEO')).toEqual(['video'])
    expect(sourcesForType('AUDIO')).toEqual(['audio'])
  })
})

describe('buildSteps', () => {
  it('brak zdarzenia → wszystkie kroki pending', () => {
    expect(buildSteps('video', undefined).map((s) => s.state)).toEqual([
      'pending',
      'pending',
      'pending',
      'pending',
    ])
  })

  it('bieżący stage = active, wcześniejsze = done, dalsze = pending', () => {
    // wideo w trakcie inferencji (jak na makiecie, 62%)
    const steps = buildSteps('video', { progress: 62, stage: 'INFERENCE' })
    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'active', 'pending'])
    expect(steps[2].label).toBe('Analiza modelu wideo')
  })

  it('100% na ostatnim kroku → wszystko done', () => {
    const steps = buildSteps('audio', { progress: 100, stage: 'ANALYSIS_COMPLETED' })
    expect(steps.every((s) => s.state === 'done')).toBe(true)
  })

  it('audio toleruje zamknięty zbiór z kontraktu (INFERENCE → krok 3)', () => {
    // gdyby audio-detektor emitował zamknięty zbiór zamiast opisowych nazw
    const steps = buildSteps('audio', { progress: 50, stage: 'INFERENCE' })
    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'active', 'pending', 'pending'])
  })

  it('nieznany stage → pozycja szacowana z progresu (fail-safe)', () => {
    const steps = buildSteps('video', { progress: 80, stage: 'WHATEVER' })
    // 80% z 4 kroków → indeks 3 (ostatni) aktywny
    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'done', 'active'])
  })
})

describe('overallProgress', () => {
  it('średnia ze źródeł; brak zdarzenia źródła liczy się jako 0%', () => {
    expect(
      overallProgress(['video', 'audio'], { video: { progress: 60, stage: 'INFERENCE' } }),
    ).toBe(30)
  })

  it('pojedyncze źródło → jego własny postęp', () => {
    expect(
      overallProgress(['audio'], { audio: { progress: 42, stage: 'ANALYZING_SEGMENTS' } }),
    ).toBe(42)
  })

  it('nic nie ruszyło → null (pasek nieokreślony)', () => {
    expect(overallProgress(['video', 'audio'], {})).toBeNull()
  })
})

describe('activeStageLabel', () => {
  it('zwraca etykietę pierwszego aktywnego kroku (wideo przed audio)', () => {
    const groups = [
      { steps: buildSteps('video', { progress: 62, stage: 'INFERENCE' }) },
      { steps: buildSteps('audio', { progress: 10, stage: 'EXTRACTING_AUDIO' }) },
    ]
    expect(activeStageLabel(groups)).toBe('Analiza modelu wideo')
  })

  it('brak aktywnego kroku → null', () => {
    const groups = [{ steps: buildSteps('video', undefined) }]
    expect(activeStageLabel(groups)).toBeNull()
  })
})
