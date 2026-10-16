import { beforeEach, describe, expect, it } from 'vitest'

import { loginRedirectUri, restoreLoginHash } from './redirect'

const ORIGIN = window.location.origin
beforeEach(() => {
  sessionStorage.clear()
  window.history.replaceState(null, '', '/')
})

describe('loginRedirectUri', () => {
  it('zachowuje deep link z query i hash', () => {
    expect(loginRedirectUri('/analysis-result/abc?x=1#top', ORIGIN)).toBe(
      `${ORIGIN}/analysis-result/abc?x=1`,
    )
  })

  it.each([undefined, '', 'https://evil.example', '//evil.example/x', '/\\evil.example', '/login'])(
    'odrzuca %s (open redirect / pętla na /login)',
    (path) => {
      expect(loginRedirectUri(path, ORIGIN)).toBeUndefined()
    },
  )
})

it('restores the saved hash after authentication and consumes it once', () => {
  loginRedirectUri('/analysis-result/abc?x=1#top')
  window.history.replaceState({ retained: true }, '', '/analysis-result/abc?x=1')
  restoreLoginHash()
  expect(window.location.hash).toBe('#top')
  expect(window.history.state).toEqual({ retained: true })
  window.history.replaceState(null, '', '/analysis-result/abc?x=1')
  restoreLoginHash()
  expect(window.location.hash).toBe('')
})

it('does not restore a hash on a different route', () => {
  loginRedirectUri('/history?q=one#top')
  window.history.replaceState(null, '', '/upload')
  restoreLoginHash()
  expect(window.location.hash).toBe('')
})
