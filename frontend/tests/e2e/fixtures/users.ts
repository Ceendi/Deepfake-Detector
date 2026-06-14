// Testowi użytkownicy zaseedowani w realmie „deepfake" (infra/keycloak/realm-export.json).
// Hasła można nadpisać zmiennymi środowiskowymi, gdyby realm w danym środowisku był inny.
export interface TestUser {
  username: string
  password: string
}

export const ALICE: TestUser = {
  username: process.env.E2E_ALICE_USER ?? 'alice',
  password: process.env.E2E_ALICE_PASS ?? 'Test1234!@',
}

export const BOB: TestUser = {
  username: process.env.E2E_BOB_USER ?? 'bob',
  password: process.env.E2E_BOB_PASS ?? 'Test1234!@',
}
