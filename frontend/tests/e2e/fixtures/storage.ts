import path from 'node:path'

// Zapisane sesje (cookies KC + origin appki) z projektu `setup`. Reużywane przez testy, żeby
// NIE logować się per test — realm „deepfake" ma brute-force ON (quickLoginCheckMilliSeconds=1000),
// więc współbieżne logowania TEGO SAMEGO usera blokują konto na 60 s. Logujemy każdego raz.
const AUTH_DIR = path.join('playwright', '.auth')

export const ALICE_STATE = path.join(AUTH_DIR, 'alice.json')
export const BOB_STATE = path.join(AUTH_DIR, 'bob.json')

// Pusty stan = brak sesji (testy bramki autoryzacji i samego flow logowania startują wylogowane).
export const ANONYMOUS_STATE = { cookies: [], origins: [] }
