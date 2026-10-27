# DeepfakeDetector — frontend

React + TypeScript + Vite, własne komponenty na CSS Modules. Uruchomienie aplikacji
z backendem i Keycloak opisuje [SETUP.md](../SETUP.md#step-4--frontend-vite).

## Nazwa pliku na stronie wyniku

Nagłówek wyniku pobiera oryginalną nazwę przez `GET /api/files/{fileId}/metadata`.
Wspólny cache w `src/api/file-meta.ts` obsługuje wynik, historię i wyszukiwanie;
równoległe żądania tego samego pliku są łączone. Odpowiedź 404 jest zapamiętywana
jako brak metadanych. Błędy sieci, 429 i 5xx powodują jedno automatyczne ponowienie po 5 s, także bez
opuszczania strony wyniku. Ponowienie znika przy zmianie pliku lub odmontowaniu;
401/403/404 nie uruchamiają ponowień. Następne montowanie może spróbować ponownie.
Wyszukiwanie nazw ma najwyżej 4 żądania naraz i zatrzymuje kolejkę po błędzie.
Historia pokazuje wtedy ostrzeżenie „Wyniki mogą być niepełne”, również przy zerowej
liczbie trafień. Zmiana samej frazy nie uruchamia ponownie pobierania wszystkich nazw.

Podczas pobierania nagłówek ma placeholder o wysokości jednego wiersza. Długie nazwy
są skracane z pełnym tekstem w `title`, a znaki sterujące i formatowania są usuwane.
Gdy nazwy brak, historyczny klucz `{uuid}_nazwa` daje nazwę bez prefiksu;
klucz `{uuid}.{ext}` lub samo UUID daje „Plik bez nazwy”.

Tytuł strony wyniku nie zawiera nazwy pliku. Raport PDF również jej nie używa;
jego nazwa pobierania to `report-{analysisId}.pdf`.

## Komunikaty i śledzenie analizy

`UPLOAD_USER_LIMIT` (429) ma osobny komunikat o równoległych uploadach użytkownika.
Kody `MEDIA_TOO_LONG` i `UNSUPPORTED_MEDIA` są obsługiwane zarówno w polu `code`, jak
w prefiksie `[KOD]` komunikatu 422. Starsze odpowiedzi `INVALID_FILE` zachowują
mapowanie po treści. Techniczne komunikaty 400/409/503 mają polskie odpowiedniki.

Osiągnięcie `createdAt + 7200 s` kończy obserwowanie z komunikatem o czasie oczekiwania,
oddzielnym od błędu połączenia. Frontend nie ustawia sam statusu FAILED; stan analizy
pozostaje autorytatywnie w backendzie. Okna odzyskiwania, limity i backoff pozostają takie same.

## Logowanie i motyw

Deep link zachowuje ścieżkę, query i hash. Fragment jest zapamiętywany w `sessionStorage`
bieżącej karty, a `redirect_uri` wysyłany do Keycloaka nie zawiera `#`. Po udanym
`init` kotwica jest przywracana raz, tylko na pasującej ścieżce i query. Router dostaje
powiadomienie o zmianie. Przy zablokowanym storage logowanie działa, ale kotwica może
zostać pominięta. Zewnętrzne adresy powrotu są odrzucane.

Motyw logowania buduje się przy budowaniu obrazu Keycloak (`infra/keycloak/Dockerfile`,
kontekst `frontend/`), więc JAR nie trafia do Git, a na hoście nie są potrzebne Java ani Maven.
Po zmianie motywu: `docker compose --profile auth up -d --build keycloak`. Lokalnie
`npm run build-keycloak-theme` nadal działa (wymaga Javy 17+ i Mavena).

## Konfiguracja w obrazie

`src/config/env.ts` czyta najpierw `window.__APP_CONFIG__` z `/config.js`, a brakujące
wartości bierze z `VITE_*` wkompilowanych przy budowaniu. W obrazie Dockera `/config.js`
generuje nginx przy starcie kontenera ze zmiennych `KEYCLOAK_URL`, `KEYCLOAK_REALM`,
`KEYCLOAK_CLIENT_ID` i `API_BASE_URL` (`nginx/default.conf.template`), więc jeden obraz z GHCR
działa z dowolnym adresem Keycloaka; Compose ustawia `KEYCLOAK_URL` z `KEYCLOAK_PORT`.
Przy `npm run dev` serwowany jest pusty `public/config.js`, więc obowiązuje `frontend/.env`.

## Wylogowanie

Wylogowanie zawsze kończy sesję SSO w Keycloak i wraca na stronę główną; stan
`AuthContext` jest czyszczony od razu. Samo wyczyszczenie tokenów w przeglądarce
nie wystarcza, bo przy kolejnym ładowaniu `check-sso` zalogowałby użytkownika
z powrotem. Gdy `idToken` jest niedostępny (rzadko, np. po nieudanym odświeżeniu
tokenu), Keycloak pokazuje stronę potwierdzenia wylogowania.
Tokeny nie są zapisywane w `localStorage`.

## Weryfikacja

```bash
npm ci
npm run lint
npm run test:run
npm run build
```

Testy Vitest obejmują metadane, fallbacki, błędy pobierania, zmianę pliku,
bezpieczne wyświetlanie nazw i obie ścieżki wylogowania. Test Playwright uploadu
sprawdza oryginalną nazwę na stronie wyniku po rzeczywistym uploadzie i utworzeniu
analizy, bez oczekiwania na detektory. Wymagania i polecenia E2E:
[tests/e2e/README.md](tests/e2e/README.md).
