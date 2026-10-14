# AGENTS.md — DeepfakeDetector

Instrukcje dla agentów AI (Claude Code, Codex, Cursor i inne). Krótko: co to jest, jak sprawdzić swoją pracę,
czego nie ruszać. Szczegóły są w dokumentach podlinkowanych niżej — czytaj je, gdy dotykasz danego obszaru.

## Projekt

Aplikacja webowa do wykrywania deepfake'ów wideo i audio (projekt dyplomowy: mikrousługi + ML). Użytkownik
wgrywa plik, system analizuje obraz i dźwięk, zwraca werdykt FAKE/REAL, pewność, heatmapy Grad-CAM i raport PDF.

```text
Frontend ──► Gateway ──► file-service ──► SeaweedFS (S3)
                    └──► orchestrator ──► PostgreSQL + Redis
                               ├─ AMQP ──► RabbitMQ ──► video-detector / audio-detector
                               │                └─ progress + results ──┘
                               └─ SSE ──► Frontend
Keycloak (OIDC/JWT), Eureka (discovery), Prometheus + Loki (Alloy) + Tempo + Grafana
```

| Katalog | Co | Właściciel |
|---|---|---|
| `gateway/`, `eureka-server/`, `orchestrator/`, `file-service/` | Spring Boot, pakiety `com.deepfake.{service}` | Osoba 1 (backend) |
| `frontend/` | React + TS + Vite, CSS Modules | Osoba 2 (frontend, CI/CD) |
| `video-detector/` | Python, FastAPI (`/health`, `/metrics`) + konsument pika, ONNX/torch CPU | Osoba 3 |
| `audio-detector/` | jak wyżej, Wav2Vec2 + MelCNN | Osoba 4 |
| `infra/`, `docker-compose.yml` | konfiguracja infrastruktury, profile `core`, `auth`, `ml`, `monitoring` | Osoba 1 |
| `docs/contracts/` | **kontrakty** REST, AMQP, statusy, S3, metryki | wszyscy |
| `tests/worker_redelivery/` | testy prawdziwego procesu detektora z RabbitMQ i Redis | backend |

Zmieniasz cudzy obszar w sposób, który zmienia zachowanie → zaznacz to w PR, żeby właściciel to przejrzał.

## Dokumentacja

- Uruchomienie, modele, problemy: `SETUP.md`, `README.md`, `docs/local-backend.md`
- Kontrakty (źródło prawdy, czytaj przed zmianą API/AMQP/S3): `docs/contracts/*.md`
- Decyzje architektoniczne: `docs/adr/`; obserwowalność: `docs/observability.md`;
  Keycloak (realm jako kod, nie zmieniaj ról w Admin UI): `infra/keycloak/README.md`
- Mechanizmy niezawodności (outbox, idempotencja, DLQ, recovery, Redis, SSE, artefakty):
  `docs/reliability.md`
- Raporty pomiarowe (D4, MO): `docs/reports/`

W komentarzach w kodzie odwołuj się tylko do `docs/adr`, `docs/contracts` i `README.md`.

## Komendy

```bash
docker compose --profile core --profile auth up -d          # backend + Keycloak
docker compose --profile core --profile auth --profile ml up -d   # + detektory (najpierw modele, patrz SETUP.md)
docker compose --profile core --profile monitoring up -d    # + Grafana/Prometheus/Loki/Tempo

cd orchestrator && ./mvnw verify          # test + integracja (Testcontainers, wymaga Dockera); Windows: mvnw.cmd
cd audio-detector && uv sync --dev && uv run pytest tests && uvx ruff check .   # tak samo video-detector
cd frontend && npm ci && npm run lint && npm run test:run && npm run build        # jak w CI
```

## Wersje i pułapki stosu

Źródłem prawdy dla wersji są `*/pom.xml`, `frontend/package.json`, `*/pyproject.toml` i `docker-compose.yml`.
Nie podnoś wersji bez potrzeby i nie przepisuj ich tutaj. Rzeczy nieoczywiste:

- **Spring Boot 4.0 / Java 25:** Jackson 3 (`tools.jackson.*`, oprócz `com.fasterxml` dla adnotacji), nie mieszaj
  z Jackson 2; w AMQP `JacksonJsonMessageConverter`, nie `Jackson2JsonMessageConverter`. Spring Cloud **2025.1**
  (2025.0 nie działa z SB 4.0). Flyway przez `spring-boot-starter-flyway`. Null safety: JSpecify.
  Retry listenerów: `max-retries` (SB 4.0 po cichu ignoruje `max-attempts`).
- Każdy Dockerfile Java ustawia
  `JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75"` (budżet RAM 16 GB).
- **Detektory:** inferencja wyłącznie CPU (ONNX `CPUExecutionProvider`, torch `map_location='cpu'`).
  Audio używa `soundfile` zamiast `torchaudio.load` (ten wymaga `torchcodec`, którego nie instalujemy).
  Obrazy instalują tylko `[project.dependencies]` przez `uv pip install --system`.
  Audio: pakiety treningowe (Lightning, transformers, pandas…) są w extra `training`
  (`uv sync --extra training`); runtime (`src/`) nie importuje `training/`, MelCNN jest w `src/models/mel_cnn.py`.
- **Frontend:** npm (jest `package-lock.json`), własne komponenty na CSS Modules — bez MUI/Ant/Shadcn/Tailwind.
  Postęp analizy idzie przez **SSE** (`src/api/stream.ts`).

## Niezmienniki (wymagania formalne D1–D6 — nie usuwaj ich)

- **D1 RabbitMQ:** exchange `analysis.exchange` (topic) i `analysis.dlx` (direct); routing `analysis.video`,
  `analysis.audio`, `analysis.progress`, `analysis.results`; każda kolejka ma `*.dlq`. Topologię deklaruje kod
  (Spring `@Bean` w orchestratorze, `pika queue_declare` w detektorach) z **identycznymi** argumentami, inaczej
  `PRECONDITION_FAILED`. Zmiana → aktualizacja `docs/contracts/amqp-messages.md` i obu stron.
- **D2/D3:** logi JSON z `correlation_id` i `trace_id`; `/actuator/prometheus` (Java) lub `/metrics` (Python).
  Nowa metryka → panel w Grafanie i wpis w `docs/contracts/metrics.md`.
- **D4:** file-service skalowalny (`FILE_SERVICE_REPLICAS`), testy obciążeniowe w `load-tests/`.
- **D5:** Eureka, routing `lb://SERVICE-NAME` w gatewayu.
- **D6 niezawodność:**
  - Wysyłka zadań przez transakcyjny outbox (`analysis_task_outbox`); wiadomość wychodzi tylko dla zacommitowanej,
    aktywnej analizy.
  - Detektory: ręczny ACK, prefetch 1, publisher confirms, wynik potwierdzony przed ACK, reconnect co 5 s
    (przy awarii S3 backoff 5/10/20/30 s, zadanie wraca do kolejki).
  - Listenery `analysis.results`/`progress` w trybie `AUTO` (ACK po commicie TX — świadomie, dla retry + recoverer);
    DLQ consumer w trybie `AUTO` po commicie, z potwierdzoną kwarantanną błędnych wiadomości.
    Wyniki: trwałe błędy 4 próby (1 s/4 s/15 s), potem potwierdzona publikacja do `analysis.results.dlq` → FAILED;
    błędne dane od razu do DLQ. Awaria bazy (utrata połączenia, restart, timeout): wiadomość wraca do kolejki,
    listener pauzuje 1/2/4… s (maks. 30 s) bez limitu prób — nigdy DLQ ani kwarantanna.
    Progress: 3 próby (100 ms/200 ms), potem log i ACK; nie powoduje FAILED.
  - Idempotencja per źródło: pierwszy wynik wygrywa w PostgreSQL (warunkowy UPDATE); Redis `dedup:{id}:{source}`
    to tylko podpowiedź ustawiana po commicie.
  - Przejścia terminalne (COMPLETED/FAILED/CANCELLED) tylko przez CAS na statusie; efekty uboczne raz, po commicie.
  - Backpressure: limit aktywnych analiz liczony w PostgreSQL pod advisory lockiem, bez osobnego licznika.
  - Stuck recovery: dispatch > 120 s, wysłane zadanie bez start-pingu > 1800 s i bezczynność > 600 s liczone
    per źródło (`video_started_at`/`audio_started_at`, `video_progress_at`/`audio_progress_at`),
    lub aktywna analiza starsza niż 7200 s od utworzenia → FAILED; progress nie odnawia wieku absolutnego.
    Pierwszy skan po starcie czeka 600 s (`RELIABILITY_STUCK_JOB_INITIAL_DELAY`).
  - Zatrzymanie FAILED/CANCELLED: flaga Redis `cancel:{id}` ustawiana po commicie; detektor sprawdza ją
    przy podjęciu, na każdym ticku i przed publikacją wyniku. Anulowanie nie jest komunikatem AMQP.
  - Redis fail-open (cache → DB, dedup → guard DB, cancel → licz do końca).
    Wyjątek: limiter uploadu w gatewayu jest świadomie fail-closed (503); pozostałe trasy są fail-open.

## Bezpieczeństwo

- Każdy endpoint z zasobem użytkownika sprawdza `resource.user_id == jwt.sub` i zwraca **404**, nie 403.
- Upload: magic bytes → ffprobe → whitelist MIME, nigdy samo rozszerzenie.
- Osobne poświadczenia S3: file-service RW na `deepfake-uploads`, detektory R na uploads i RW na
  `analysis-artifacts` (`infra/seaweedfs/s3.json.tmpl`); wewnętrzne API SeaweedFS tylko na
  loopbacku + klucze JWT. RabbitMQ nigdy `guest/guest`; serwisy łączą się jako
  `orchestrator`/`detector` z uprawnieniami z `infra/rabbitmq/provision_users.py` (zmiana
  topologii → zmiana uprawnień), admin tylko do operacji ręcznych.
- Security primitives (`JwtRoleConverter`, `AuthenticatedUser`, `@CurrentUser`, `SecurityConfig`,
  `CorrelationIdFilter`, `GlobalExceptionHandler`, `ErrorResponse`) są **świadomie zduplikowane** między
  orchestratorem a file-service. Zmieniasz jeden — zsynchronizuj drugi; nie wydzielaj wspólnego modułu.

## Granice

**Zawsze**
- Przed zmianą zachowania napisz test, który bez poprawki nie przechodzi (Java: JUnit + Testcontainers,
  Python: pytest, przepływ broker/worker: `tests/worker_redelivery`).
- Nowa migracja jako kolejny `V{N}__opis.sql`.
- Nowy lub zmieniony endpoint → zaktualizowane adnotacje OpenAPI i ręcznie zsynchronizowany
  `frontend/src/api/types.ts` (świadomie bez codegenu; nie commituj `src/api/generated/`).
- Komentarze w kodzie po angielsku, krótkie, tylko „dlaczego”; przyszła praca jako `TODO(...)`.

**Najpierw zapytaj**
- Zmiana kontraktu AMQP/REST, typu lub argumentów kolejek, progów niezawodności (timeouty, limity, retry).
- Zmiana zachowania przy awarii zależności (fail-open/fail-closed).
- Nowe zależności, podbicie wersji frameworków, zmiana profili compose.
- Zmiana progów decyzyjnych lub wag agregacji (`0.6·video + 0.4·audio`, FAKE gdy > 0.5).

**Nigdy**
- Edytować istniejących migracji Flyway.
- Usuwać DLQ, retry, idempotencji, outboxa ani stuck recovery.
- Dodawać Kafki ani Kubernetesa (wymagane są RabbitMQ i Docker Compose). Klastrować orchestratora
  (pojedyncza instancja z healthcheckiem i restartem jest świadomą decyzją).
- Commitować `.env`, poświadczeń, modeli (`.pt`, `.onnx`), datasetów ani binarnych plików mediów
  (testy generują media w pamięci).
- `git add -A` / `git add .` — w katalogu głównym bywają lokalne, nieśledzone pliki; dodawaj tylko swoje.

## Definition of done

1. Zielone dla zmienionych serwisów: `./mvnw verify`, `pytest` + `ruff`, `npm run lint && npm run test:run && npm run build`.
2. `git diff --check` czysty; Conventional Commits (`feat(orchestrator): ...`), nagłówek < 100 znaków (commitlint).
   Bez trailerów atrybucji AI (`Co-Authored-By`, „Generated with …”).
3. PR opisuje problem, zachowanie po zmianie, dotknięte serwisy, komendy weryfikacji i ograniczenia.
4. Zmiany w `docker-compose.yml`: `docker compose --profile core up` startuje na czystej maszynie.

## Cele jakościowe

ML: wideo FF++ F1 ≥ 0,85 / AUC ≥ 0,90, Celeb-DF acc ≥ 0,75; audio ASVspoof EER ≤ 3%, WaveFake acc ≥ 0,70.
Wydajność: inferencja p95 wideo 30 s < 8 s, audio 30 s < 3 s (CPU); GET analizy p95 < 50 ms (cache) / < 200 ms.
Gdy cel nie jest spełniony — szukaj przyczyny, nie obniżaj celu.
