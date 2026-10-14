import { describe, expect, it } from 'vitest'

import { loginRedirectUri } from './redirect'

const ORIGIN = 'http://localhost:5173'

describe('loginRedirectUri', () => {
  it('zachowuje deep link z query i hash', () => {
    expect(loginRedirectUri('/analysis-result/abc?x=1#top', ORIGIN)).toBe(
      'http://localhost:5173/analysis-result/abc?x=1#top',
    )
  })

  it.each([undefined, '', 'https://evil.example', '//evil.example/x', '/\\evil.example', '/login'])(
    'odrzuca %s (open redirect / pętla na /login)',
    (path) => {
      expect(loginRedirectUri(path, ORIGIN)).toBeUndefined()
    },
  )
})
