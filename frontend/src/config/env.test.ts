import template from '../../nginx/default.conf.template?raw'

import { resolveEnv } from './env'

// The module resolves `env` on import; tests do not depend on a frontend/.env file.
vi.hoisted(() => {
  window.__APP_CONFIG__ = {
    apiBaseUrl: '/api',
    keycloakUrl: 'http://localhost:8180',
    keycloakRealm: 'deepfake',
    keycloakClientId: 'deepfake-web',
  }
})

const BUILD_TIME = {
  VITE_API_BASE_URL: '/api',
  VITE_KEYCLOAK_URL: 'http://localhost:8180',
  VITE_KEYCLOAK_REALM: 'deepfake',
  VITE_KEYCLOAK_CLIENT_ID: 'deepfake-web',
}

// Mirrors the image's envsubst step on the /config.js body nginx returns.
function servedConfig(values: Record<string, string>): unknown {
  const body = /location = \/config\.js \{[\s\S]*?return 200 '([^']*)'/.exec(template)?.[1]
  const json = body && /^window\.__APP_CONFIG__ = (\{.*\});\\n$/.exec(body)?.[1]
  if (!json) throw new Error('nginx template does not serve window.__APP_CONFIG__')
  return JSON.parse(json.replace(/\$\{(\w+)\}/g, (_, name: string) => values[name] ?? ''))
}

describe('resolveEnv', () => {
  it('uses build-time values when no runtime config is served', () => {
    expect(resolveEnv(undefined, BUILD_TIME)).toEqual({
      apiBaseUrl: '/api',
      keycloakUrl: 'http://localhost:8180',
      keycloakRealm: 'deepfake',
      keycloakClientId: 'deepfake-web',
    })
  })

  it('lets runtime values override build-time ones', () => {
    const env = resolveEnv({ keycloakUrl: 'http://localhost:8280' }, BUILD_TIME)
    expect(env.keycloakUrl).toBe('http://localhost:8280')
    expect(env.keycloakRealm).toBe('deepfake')
  })

  it('falls back for blank or non-string runtime values', () => {
    const env = resolveEnv({ keycloakUrl: '  ', keycloakRealm: 42 }, BUILD_TIME)
    expect(env.keycloakUrl).toBe('http://localhost:8180')
    expect(env.keycloakRealm).toBe('deepfake')
  })

  it('ignores a runtime config that is not an object', () => {
    expect(resolveEnv('broken', BUILD_TIME).apiBaseUrl).toBe('/api')
  })

  it('works from runtime values alone, as in the published image', () => {
    const runtime = {
      apiBaseUrl: '/api',
      keycloakUrl: 'https://id.example.org',
      keycloakRealm: 'deepfake',
      keycloakClientId: 'deepfake-web',
    }
    expect(resolveEnv(runtime, {})).toEqual(runtime)
  })

  it('names the missing variable', () => {
    expect(() => resolveEnv({}, { ...BUILD_TIME, VITE_KEYCLOAK_REALM: undefined })).toThrow(
      'VITE_KEYCLOAK_REALM',
    )
  })

  it('returns a frozen object', () => {
    expect(Object.isFrozen(resolveEnv(undefined, BUILD_TIME))).toBe(true)
  })
})

describe('nginx /config.js', () => {
  it('serves every setting the app reads', () => {
    const served = servedConfig({
      API_BASE_URL: '/api',
      KEYCLOAK_URL: 'http://localhost:8280',
      KEYCLOAK_REALM: 'deepfake',
      KEYCLOAK_CLIENT_ID: 'deepfake-web',
    })
    expect(resolveEnv(served, {})).toEqual({
      apiBaseUrl: '/api',
      keycloakUrl: 'http://localhost:8280',
      keycloakRealm: 'deepfake',
      keycloakClientId: 'deepfake-web',
    })
  })
})

describe('env', () => {
  afterEach(() => {
    delete window.__APP_CONFIG__
    vi.resetModules()
  })

  it('reads the config that /config.js put on window', async () => {
    window.__APP_CONFIG__ = {
      apiBaseUrl: '/gateway',
      keycloakUrl: 'http://localhost:8280',
      keycloakRealm: 'r',
      keycloakClientId: 'c',
    }
    vi.resetModules()
    const { env } = await import('./env')
    expect(env.apiBaseUrl).toBe('/gateway')
    expect(env.keycloakUrl).toBe('http://localhost:8280')
  })
})
