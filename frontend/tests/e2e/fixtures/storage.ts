import path from 'node:path'

const AUTH_DIR = path.join('playwright', '.auth')

export const ALICE_STATE = path.join(AUTH_DIR, 'alice.json')
export const BOB_STATE = path.join(AUTH_DIR, 'bob.json')

export const ANONYMOUS_STATE = { cookies: [], origins: [] }
