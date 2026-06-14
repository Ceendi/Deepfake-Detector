# E2E (Playwright)

Testy end-to-end uderzają w **żywy stack** (prawdziwy Keycloak + Gateway + Orchestrator +
File Service). Logowanie idzie przez realny redirect PKCE na hostowaną stronę Keycloaka,
a `/api/**` proxuje dev server Vite na Gateway (`vite.config.ts`).

## Wymagania przed uruchomieniem

1. **Backend + Keycloak** (z korzenia repo):
   ```bash
   docker compose --profile core --profile auth up        # + --profile ml dla detektorów
   ```
2. **Realm `deepfake`** z userami `alice` / `bob` (hasło `Test1234!@`) — seedowany z
   `infra/keycloak/realm-export.json`.
3. **Dev server** na `:5173` — jeśli nie działa, Playwright (`webServer`) podniesie go sam.
   (Backendu/Keycloaka `webServer` **nie** startuje — muszą działać niezależnie.)

## Uruchamianie

```bash
npm run e2e                       # wszystkie testy (Chromium + Firefox)
npm run e2e -- --project=chromium # jedna przeglądarka
npm run e2e -- --ui               # tryb UI (debug)
npx playwright show-report        # raport HTML po przebiegu
```

## Co jest pokryte

| Plik                 | Scenariusz                                                                              |
| -------------------- | --------------------------------------------------------------------------------------- |
| `auth.spec.ts`       | redirect niezalogowanego → `/login`, pełne logowanie przez Keycloak, wylogowanie        |
| `navigation.spec.ts` | nawigacja nagłówkiem: Dashboard → Upload → Historia → Profil                            |
| `upload.spec.ts`     | walidacja kliencka (odrzut formatu), realny upload + `POST /analysis` (201), anulowanie |
| `history.spec.ts`    | strona historii wczytuje listę bez błędu                                                |
| `idor.spec.ts`       | analiza alice jest dla boba 404 (IDOR guard), właściciel dostaje 200                    |

## Test werdyktu (domyślnie pominięty)

`upload.spec.ts` zawiera pełną ścieżkę _login → upload → progress → werdykt_. Realny detektor
**nie wyda sensownego werdyktu** z syntetycznego pliku (video wymaga wykrywalnej twarzy, audio —
mowy) i potrzebuje wgranych modeli, więc test jest pomijany, dopóki nie wskażesz prawdziwego pliku:

```bash
E2E_VERDICT_FIXTURE=/ścieżka/do/klipu_z_twarzą.mp4 npm run e2e -- --project=chromium
```

## Konfiguracja przez zmienne środowiskowe

| Zmienna               | Domyślnie               | Opis                                        |
| --------------------- | ----------------------- | ------------------------------------------- |
| `E2E_BASE_URL`        | `http://localhost:5173` | adres front-app pod testy                   |
| `E2E_ALICE_USER/PASS` | `alice` / `Test1234!@`  | główny użytkownik testowy                   |
| `E2E_BOB_USER/PASS`   | `bob` / `Test1234!@`    | drugi użytkownik (IDOR)                     |
| `E2E_VERDICT_FIXTURE` | — (pominięte)           | ścieżka do realnego pliku do testu werdyktu |

## Uwagi

- Każdy test loguje się we **własnym kontekście** (świeża sesja KC), więc `fullyParallel` jest
  bezpieczne — wylogowanie w jednym teście nie psuje innych.
- Testy `upload`/`idor` tworzą realne analizy na koncie `alice` (dane przyrastają w historii —
  to oczekiwane). Plik wejściowy to wygenerowany w pamięci WAV (`fixtures/media.ts`), nie commitujemy binariów.
- E2E **nie** jest częścią CI (`.github/workflows/ci.yml`) — wymaga pełnego stacku. Uruchamiaj lokalnie / on-demand.
