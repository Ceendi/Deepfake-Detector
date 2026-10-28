# DeepfakeDetector — frontend

React + TypeScript + Vite, własne komponenty na CSS Modules. Uruchomienie z backendem
i Keycloak: [SETUP.md](../SETUP.md#frontend-dev-server).

```bash
cp .env.example .env
npm ci
npm run dev        # http://localhost:5173, /api proxowane na gateway (localhost:8080)
```

## Weryfikacja

```bash
npm run lint
npm run test:run
npm run build
```

Testy E2E (Playwright) wymagają działającego stosu: [tests/e2e/README.md](tests/e2e/README.md).

## Konfiguracja w obrazie

`src/config/env.ts` czyta najpierw `window.__APP_CONFIG__` z `/config.js`, a brakujące wartości
bierze z `VITE_*` wkompilowanych przy budowaniu. W obrazie Dockera nginx generuje `/config.js`
przy starcie kontenera ze zmiennych `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`
i `API_BASE_URL` (`nginx/default.conf.template`), więc jeden obraz z GHCR działa z dowolnym
adresem Keycloaka; Compose ustawia `KEYCLOAK_URL` z `KEYCLOAK_PORT`. Przy `npm run dev`
`public/config.js` jest pusty, więc obowiązuje `frontend/.env`.

Motyw logowania Keycloak (keycloakify) buduje obraz Keycloak (`infra/keycloak/Dockerfile`,
kontekst `frontend/`); po zmianie motywu: `docker compose up -d --build keycloak`.
`npm run build-keycloak-theme` buduje JAR lokalnie (wymaga Javy 17+ i Mavena).

## Zachowania warte uwagi

- **Logowanie:** deep link zachowuje ścieżkę, query i hash. Fragment trafia do `sessionStorage`
  karty, a `redirect_uri` wysyłany do Keycloaka nie zawiera `#`; po `init` kotwica jest
  przywracana raz, tylko na pasującej ścieżce. Zewnętrzne adresy powrotu są odrzucane.
  Tokeny nie są zapisywane w `localStorage`.
- **Wylogowanie** zawsze kończy sesję SSO w Keycloak (samo wyczyszczenie tokenów nie wystarcza,
  bo `check-sso` zalogowałby użytkownika z powrotem). Bez `idToken` Keycloak pokazuje stronę
  potwierdzenia wylogowania.
- **Nazwa pliku** na stronie wyniku pochodzi z `GET /api/files/{fileId}/metadata` przez wspólny
  cache `src/api/file-meta.ts` (wynik, historia, wyszukiwanie). 404 jest zapamiętywane jako brak
  metadanych; błędy sieci, 429 i 5xx dają jedno ponowienie po 5 s. Wyszukiwanie wysyła najwyżej
  4 żądania naraz, a po błędzie historia pokazuje „Wyniki mogą być niepełne”. Nazwy są skracane
  i oczyszczane ze znaków sterujących; bez metadanych nazwa pochodzi z klucza `{uuid}_nazwa`,
  inaczej „Plik bez nazwy”. Tytuł strony i nazwa raportu PDF (`report-{analysisId}.pdf`) nie
  zawierają nazwy pliku.
- **Postęp analizy** idzie przez SSE (`src/api/stream.ts`, `watch-analysis.ts`); zasady
  ponawiania i limit `createdAt + 7200 s` opisuje
  [SSE delivery](../docs/reliability.md#sse-delivery). Frontend nie ustawia sam statusu FAILED.
- **Błędy API:** `UPLOAD_USER_LIMIT` (429) ma osobny komunikat, `MEDIA_TOO_LONG`
  i `UNSUPPORTED_MEDIA` są rozpoznawane w polu `code` i w prefiksie `[KOD]` komunikatu 422,
  a techniczne komunikaty 400/409/503 mają polskie odpowiedniki.
