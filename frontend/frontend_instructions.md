# Brief — Osoba 2 (Frontend + DevOps)

**Projekt:** DeepfakeDetector — webowa aplikacja do detekcji deepfake video+audio (praca dyplomowa, 4-osobowy zespół, 2 semestry).
**Pełny plan:** `DeepfakeDetector_Plan.md` — przeczytaj sekcje 2, 3.1, 3.5, 3.6, 10 (twoja osoba), 11 (timeline).

## Twoja rola

Jesteś jedyną osobą frontendową w zespole + ogarniasz DevOps (CI/CD, dashboards). Load: ~65–70%.

## Funkcjonalności

**Funkcjonalności MVP (15):** upload pliku, logowanie i rejestracja (Keycloak), analiza wideo, analiza audio, historia analiz, śledzenie postępu w czasie rzeczywistym (WebSocket), wizualizacja wyniku (Grad-CAM), podgląd przesyłanego pliku, anulowanie analizy, powiadomienie o zakończeniu, limit rozmiaru pliku (500 MB), rate limiting, obsługa wielu formatów, wyświetlenie raportu, IDOR protection (własność zasobów).

**Funkcjonalności V2 (5):** pobieranie raportu PDF, i18n (PL/EN), dark mode, edycja profilu (Keycloak Account Console), zmiana hasła (Keycloak inline).

## Co robisz

### Frontend (core)

- **Stack:** Node 22 LTS + React 19 + TypeScript 5 + Vite 7 + CSS Modules + Czysty React (Context/Hooks dla stanu) + React Hook Form + Zod
- **Auth:** `keycloak-js` (PKCE flow, public client `deepfake-web`), route guards, token refresh
- **Strony:** Dashboard, Upload (drag-n-drop + preview + progress + cancel), AnalysisResult (verdict, confidence bar, Grad-CAM heatmap), History (paginacja + filtry), Profile (link do Keycloak Account Console)
- **WebSocket:** `@stomp/stompjs` subskrypcja `/user/queue/analysis-progress` → aktualizacja stanu aplikacji w czystym React
- **OpenAPI codegen:** `openapi-typescript` generuje typy z `/v3/api-docs` backendu — jedno źródło prawdy, nie duplikuj DTO ręcznie
- **V2:** i18n (PL/EN via `react-i18next`), dark mode (CSS variables), PDF raport download

### Testy

- Vitest + React Testing Library (unit)
- Playwright (E2E): login → upload → progress → verdict (cross-browser: Chromium + Firefox)

### Optymalizacja (raport MO — wymagany)

- `React.lazy` + Suspense dla route-level code splitting
- `rollup-plugin-visualizer` bundle analysis

## Co używasz, czego nie używasz

✅ CSS Modules (własne komponenty, pełna kontrola)
❌ MUI, Ant Design, Chakra, Shadcn/ui, Tailwind (nie ma ich w stacku)
✅ Czysty React (Context, Custom Hooks) — zarządzanie stanem i połączenia z API
❌ Redux, MobX, Zustand, TanStack Query
✅ `keycloak-js` PKCE
❌ Custom auth flow (Keycloak robi wszystko)

## Kontrakty z zespołem

- **Backend (Osoba 1):** dostarczy OpenAPI spec na `/v3/api-docs`. Typy TS → codegen. WebSocket STOMP destynacje: `/user/queue/analysis-progress` (tylko twoje eventy — routing Spring robi).
- **AI (Osoba 3+4):** zwracają presigned URL-e do Grad-CAM PNG w SeaweedFS S3 (bucket `analysis-artifacts`). Nie rób preview inaczej.
- **Keycloak:** realm `deepfake`, client `deepfake-web` (public, PKCE). Realm export dostaniesz od Backendu w `infra/keycloak/realm-export.json`.

## Must-have bezpieczeństwo

- CSP headers (restrictive, no inline scripts)
- Token w memory, NIE localStorage (XSS risk)
- HTTPS w prod, CORS whitelist origin

## Gdzie szukać kontekstu

- `CLAUDE.md` w repo — konwencje projektu
- Backend OpenAPI spec (`/v3/api-docs`) — kontrakty
