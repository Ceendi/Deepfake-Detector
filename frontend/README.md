# DeepfakeDetector — frontend

React + TypeScript + Vite, własne komponenty na CSS Modules. Uruchomienie aplikacji
z backendem i Keycloak opisuje [SETUP.md](../SETUP.md#step-4--frontend-vite).

## Nazwa pliku na stronie wyniku

Nagłówek wyniku pobiera oryginalną nazwę przez `GET /api/files/{fileId}/metadata`.
Wspólny cache w `src/api/file-meta.ts` obsługuje wynik, historię i wyszukiwanie;
równoległe żądania tego samego pliku są łączone. Odpowiedź 404 jest zapamiętywana
jako brak metadanych. Błędy sieci i 429 pozwalają ponowić pobranie przy kolejnym montowaniu.

Podczas pobierania nagłówek ma placeholder o wysokości jednego wiersza. Długie nazwy
są skracane z pełnym tekstem w `title`, a znaki sterujące i formatowania są usuwane.
Gdy nazwy brak, historyczny klucz `{uuid}_nazwa` daje nazwę bez prefiksu;
klucz `{uuid}.{ext}` lub samo UUID daje „Plik bez nazwy”.

Tytuł strony wyniku nie zawiera nazwy pliku. Raport PDF również jej nie używa;
jego nazwa pobierania to `report-{analysisId}.pdf`.

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
