# E2E (Playwright)

Testy end-to-end działają na **żywym stosie** (Keycloak, Gateway, Orchestrator, File Service).
Logowanie idzie przez prawdziwy redirect PKCE na stronę Keycloaka, a `/api/**` proxuje dev server
Vite na Gateway (`vite.config.ts`).

## Przygotowanie

1. W `.env` w katalogu głównym ustaw realm deweloperski z użytkownikami `alice` / `bob`
   (hasło `Test1234!@`): `KEYCLOAK_REALM_CONFIG=dev/realm-export.json`.
2. Testy wgrywają pliki równolegle, a bez detektorów analizy zostają aktywne, więc przy domyślnych
   limitach kolejne przebiegi dostają 429. Podnieś w `.env` `BACKPRESSURE_MAX_INFLIGHT_PER_USER`,
   `UPLOAD_MAX_CONCURRENT_PER_USER` i `UPLOAD_RL_BURST` (wartości z `.github/workflows/e2e.yml`)
   albo uruchamiaj na świeżym stosie.
3. Uruchom backend i Keycloak (detektory nie są potrzebne):
   ```bash
   docker compose --profile core --profile auth up -d --build
   ```
4. Skopiuj `frontend/.env.example` do `frontend/.env`. Dev server na `:5173` Playwright uruchomi
   sam (`webServer`), jeśli jeszcze nie działa; backendu nie uruchamia.

## Uruchamianie

```bash
npm run e2e                       # Chromium i Firefox
npm run e2e -- --project=chromium # jedna przeglądarka (tak jak w CI)
npm run e2e -- --ui               # tryb UI (debug)
npx playwright show-report        # raport HTML po przebiegu
```

Projekt `setup` (`auth.setup.ts`) loguje `alice` i `bob` raz i zapisuje sesje w `playwright/.auth/`;
pozostałe testy z nich korzystają. Testy samego logowania działają tylko w Chromium (blokada
brute-force Keycloaka).

| Plik                 | Scenariusz                                                                                                                       |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `auth.spec.ts`       | przekierowanie niezalogowanego na `/login`, logowanie zachowujące ścieżkę/query/hash bez fragmentu w `redirect_uri`, wylogowanie |
| `navigation.spec.ts` | nawigacja nagłówkiem: Dashboard → Upload → Historia → Profil                                                                     |
| `upload.spec.ts`     | odrzucenie formatu po stronie klienta, upload + `POST /analysis` (201), oryginalna nazwa w wyniku, anulowanie                    |
| `history.spec.ts`    | historia wczytuje listę bez błędu                                                                                                |
| `idor.spec.ts`       | analiza alice jest dla boba „nie znaleziono”, właściciel ją widzi                                                                |

Testy tworzą prawdziwe analizy na koncie `alice`. Wgrywany plik to WAV generowany w pamięci
(`fixtures/media.ts`); binariów nie commitujemy.

## Test werdyktu (domyślnie pominięty)

`upload.spec.ts` zawiera pełną ścieżkę _upload → postęp → werdykt_. Syntetyczny plik nie da
sensownego werdyktu (wideo wymaga twarzy, audio mowy), a test potrzebuje profilu `ml`, więc jest
pomijany, dopóki nie wskażesz prawdziwego pliku:

```bash
E2E_VERDICT_FIXTURE=/ścieżka/do/klipu_z_twarzą.mp4 npm run e2e -- --project=chromium
```

## Zmienne środowiskowe

| Zmienna                             | Domyślnie               | Opis                      |
| ----------------------------------- | ----------------------- | ------------------------- |
| `E2E_BASE_URL`                      | `http://localhost:5173` | adres frontendu           |
| `E2E_ALICE_USER` / `E2E_ALICE_PASS` | `alice` / `Test1234!@`  | główny użytkownik testowy |
| `E2E_BOB_USER` / `E2E_BOB_PASS`     | `bob` / `Test1234!@`    | drugi użytkownik (IDOR)   |
| `E2E_VERDICT_FIXTURE`               | brak (test pominięty)   | plik do testu werdyktu    |

W CI (`.github/workflows/e2e.yml`) stos `core` + `auth` z realmem dev i podniesionymi limitami
działa w jednorazowym projekcie Compose, a testy uruchamiają się w Chromium.
