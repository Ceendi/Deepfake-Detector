# CLAUDE.md — DeepfakeDetector

Kontekst projektu dla asystentów AI pracujących w tym repo. Trzymaj się konwencji tu opisanych, zanim zaczniesz pisać kod lub proponować zmiany.

---

## Czym jest projekt

**DeepfakeDetector** — webowa aplikacja do detekcji deepfake'ów wideo i audio, realizowana jako projekt dyplomowy (mikrousługi + uczenie maszynowe). System przyjmuje plik od usera, analizuje go (video + audio), zwraca verdict (FAKE/REAL), confidence, heatmapy Grad-CAM i raport.

**Pełna specyfikacja:** `DeepfakeDetector_Plan.md` (w korzeniu repo). Zanim zaczniesz pracę, przeczytaj sekcje 2 (architektura), 4 (moduły) i 13 (struktura repo).

---

## Zespół (4 osoby)

| Osoba    | Rola              | Główne obszary                                                    |
| -------- | ----------------- | ----------------------------------------------------------------- |
| 1 (user) | Backend Senior    | Gateway, Orchestrator, File Service, infra, observability, D4, MO |
| 2        | Frontend + DevOps | React SPA, CI/CD, E2E, Grafana dashboards                         |
| 3        | AI Video          | Video Detector + trening FF++/Celeb-DF                            |
| 4        | AI Audio          | Audio Detector + trening ASVspoof                                 |

Szczegóły podziału: sekcja 10 planu. Jeśli implementujesz coś — sprawdź, czyja to odpowiedzialność.

---

## Stack — wersje do używania (maj 2026)

**Źródło prawdy dla wersji infra:** `docker-compose.yml`. Jeśli widzisz rozjazd między tym plikiem a compose — compose wygrywa, popraw CLAUDE.md w PR.

### Backend (Java)

- Java 25 LTS
- Spring Boot 4.0 (Spring Framework 7, Jakarta EE 11, Hibernate 7, Jackson 3, JPA 3.2)
- Spring Cloud 2025.1 (Oakwood) — **uwaga: 2025.0 (Northfields) NIE jest kompatybilny z SB 4.0**
- PostgreSQL 18, Redis 8, RabbitMQ 4.3, SeaweedFS (S3-kompatybilne, image `chrislusf/seaweedfs`), Keycloak 26.6.1 (z dedykowanym PostgreSQL)
- Flyway (`spring-boot-starter-flyway`, nie samo `flyway-core`), MapStruct, Lombok (≥ 1.18.40 dla Jackson 3 + JDK 25; w `lombok.config` ustaw `lombok.jacksonized.jacksonVersion += 3`), Micrometer + Prometheus registry
- OpenTelemetry: **`spring-boot-starter-opentelemetry`** (natywny starter SB 4.0, preferowany) + opcjonalnie Java agent
- Jackson 3 (`tools.jackson.*`) — `JacksonJsonMessageConverter` zamiast `Jackson2JsonMessageConverter` w Spring AMQP
- springdoc-openapi 3.x (kompatybilny z SB 4.0 + Jakarta EE 11)
- Testy: JUnit 5 + Mockito + **Testcontainers** (integracyjne)

### Frontend

- Node 22 LTS
- React 19 + TypeScript 5 + Vite 7
- CSS Modules (własne komponenty, bez MUI / Shadcn / Tailwind)
- Czysty React (Context, Custom Hooks) dla stanu i zapytań do API
- React Hook Form + Zod
- `@stomp/stompjs` + `sockjs-client`
- `keycloak-js` (PKCE)
- Testy: Vitest + React Testing Library + Playwright (E2E)
- **Szczegółowe instrukcje projektowe:** Zobacz [`frontend/frontend_instructions.md`](frontend/frontend_instructions.md) w celu zapoznania się z dokładnym stackiem, architekturą i listą zadań do zrobienia na froncie.

### ML (Python)

- Python 3.12
- PyTorch 2.7 + PyTorch Lightning 2.5
- ONNX Runtime 1.25
- `timm`, `insightface` (RetinaFace), `albumentations`, `audiomentations`
- HuggingFace `transformers` (Wav2Vec2-XLS-R)
- `pytorch-grad-cam`
- FastAPI 0.136 (tylko `/health`, `/metrics`), uvicorn[standard] 0.46, `pika` 1.4 (consumer), pydantic 2.13
- `prometheus-client` 0.25, `structlog` 25, `boto3` 1.42, OpenTelemetry Python SDK
- Weights & Biases (experiment tracking)
- **Install:** `uv` (de facto standard 2026, ~10x szybsze niż pip). Dockerfile używa `uv pip install --system -r pyproject.toml` — instalujemy _tylko_ deps z `[project.dependencies]`, sam projekt nie jest budowany w obrazie (żeby code change nie inwalidował warstwy z deps)
- **Źródło prawdy dla wersji Python deps:** `video-detector/pyproject.toml` (audio-detector trzyma identyczną kopię — różni się tylko `name`/`description` + dwa env defaults w `consumer.py`). Pyproject wygrywa nad CLAUDE.md — popraw CLAUDE.md w PR jeśli widzisz rozjazd

### Infra

- Docker Compose v2 + profile: `core`, `ml`, `monitoring`, `auth`
- GitHub Actions (monorepo CI/CD)
- JMeter (load testy D4)

**Nie podnoś wersji bez potrzeby.** Jeśli widzisz starą wersję w kodzie — to błąd, wyrównaj do powyższych.

### Uwagi migracyjne (SB 4.0 vs 3.5)

- Jackson: `com.fasterxml.jackson.*` → `tools.jackson.*` (oprócz `jackson-annotations` — zostaje `com.fasterxml`). Używaj `JsonMapper.builder().build()` zamiast `new ObjectMapper()`.
- Hibernate 7: `merge()` zamiast `saveOrUpdate()`, brak `@Proxy`, ścisłejsza Criteria API.
- Flyway: wymagany `spring-boot-starter-flyway` (samo `flyway-core` nie wystarcza do auto-config).
- Spring AMQP: `JacksonJsonMessageConverter` (Jackson 3) zamiast `Jackson2JsonMessageConverter`.
- Null safety: JSpecify annotations zamiast `@Nullable` z Spring.
- Retry: wbudowane w `spring-core`, nie potrzeba `spring-retry` jako osobnej zależności.

### JVM args dla kontenerów Java (JEP 519)

Compact Object Headers (Java 25) są **stable, ale NIE domyślnie włączone**. Każdy Dockerfile Java MUSI ustawić:

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders"
```

Zysk ~10–15% RSS w typowym Spring Boot kontenerze, kluczowe dla 4 JVM-ów na maszynach studenckich (16 GB RAM). Domyślne włączenie planowane dopiero w JEP 534 (Java 26+).

---

## Architektura — skrót

```
Frontend ──HTTPS──► Gateway ──► FILE-SERVICE ──► SeaweedFS (S3)
                           └──► ORCHESTRATOR ──► PostgreSQL + Redis
                                      │
                                      ├── AMQP publish ──► RabbitMQ ──► Video Detector ─┐
                                      │                              └──► Audio Detector─┤
                                      └── AMQP consume ◄───────────────────────────────── ┘
                                      │
                                      └── WebSocket STOMP ──► Frontend (progress/result)

Side-cars: Keycloak (OIDC/JWT), Eureka (discovery)
Observability: Prometheus + Loki + Tempo + Grafana + OpenTelemetry
```

**Zasada:** wszystko blokujące lub kosztowne → async przez RabbitMQ. HTTP sync tylko dla szybkich zapytań (status, list, upload init).

---

## Wymagania formalne (D1–D6)

| ID  | Wymaganie                  | Realizacja                                                                  |
| --- | -------------------------- | --------------------------------------------------------------------------- |
| D1  | Async broker               | RabbitMQ topic exchange, task queueing + event pub                          |
| D2  | Centralne logi + korelacja | Loki + `correlation_id` w MDC/structlog + OpenTelemetry trace_id            |
| D3  | Metryki                    | Prometheus + Grafana, Actuator + prometheus_client                          |
| D4  | Replikacja + load test     | File Service ×2 + JMeter (50 concurrent × 60s)                              |
| D5  | Discovery                  | Eureka, `lb://SERVICE-NAME` w Gateway                                       |
| D6  | Odporność                  | DLQ, retry, healthcheck, idempotentność, stuck job recovery, reconnect loop |

**Nie usuwaj żadnego z nich** — to wymagania formalne projektu.

---

## Konwencje

### Nazewnictwo

- Serwisy (foldery): `gateway`, `eureka-server`, `orchestrator`, `file-service`, `video-detector`, `audio-detector`, `frontend`
- Package Java: `com.deepfake.{service}.*`
- Python modules: `src/pipeline/`, `src/models/`, `src/training/`, `src/evaluation/`, `src/explainability/`
- RabbitMQ: exchange `analysis.exchange` (topic) + `analysis.dlx` (direct, DLX), routing keys `analysis.video`, `analysis.audio`, `analysis.progress`, `analysis.results`, `analysis.cancel`, DLQ: `*.dlq`

### Topologia AMQP

Broker startuje pusty. **Topologia (exchanges, queues, bindings) deklarowana w kodzie aplikacji:** Spring AMQP `@Bean` w Orchestratorze, `pika channel.queue_declare(...)` w detektorach. **Nie używamy `infra/rabbitmq/definitions.json`** — pełny kontrakt i wymagane argumenty queue są w [`docs/contracts/amqp-messages.md`](docs/contracts/amqp-messages.md). Każdy producer i consumer musi deklarować z **dokładnie tymi samymi argumentami**, inaczej RabbitMQ zwraca `PRECONDITION_FAILED`.

### DTO / kontrakty

- Jedno źródło prawdy: OpenAPI spec generowany przez springdoc-openapi → frontend używa `openapi-typescript` do generacji typów
- Komunikaty AMQP: JSON, pola obowiązkowe `{ analysis_id, file_bucket, file_key, correlation_id, timestamp }`
- Nie definiuj kontraktów ad-hoc — sprawdź istniejące w `orchestrator/src/main/java/.../dto/`

### Database

- Flyway migracje: `V1__init.sql`, `V2__indexes.sql` itd. — nigdy nie edytuj istniejących migracji, zawsze nowa
- Indeksy: covering na `(user_id, created_at DESC)`, partial na active/stuck analyses
- JPA, nie natywne SQL (chyba że naprawdę trzeba — np. atomic UPDATE RETURNING)

### Security — must-have

- Każdy endpoint z zasobem → **IDOR guard**: sprawdź `resource.user_id == jwt.sub`, zwróć 404 (nie 403) gdy niezgodne
- File upload: magic bytes → ffprobe → MIME whitelist. Nigdy tylko rozszerzenie
- S3 credentials (SeaweedFS) **rozdzielone**: File Service (RW na `deepfake-uploads`), Detektory (R na `deepfake-uploads`, RW na `analysis-artifacts`). Konfiguracja w `infra/seaweedfs/s3.json.tmpl`
- RabbitMQ: własne credentials, NIE guest/guest
- `.env` w `.gitignore`; `.env.example` committed

### Niezawodność — must-have

- Consumer AMQP: **manual ack**, publisher confirms, idempotentność (Redis `SET NX processing:{id}`)
- Race condition na agregacji V+A: atomowy `UPDATE ... WHERE status = 'PENDING' RETURNING *`
- Stuck job recovery: `@Scheduled` co 5 min, jobs > 10 min w `PROCESSING` → `FAILED`
- Python consumer: reconnect loop z 5s backoff
- DLQ consumer: każda kolejka ma swoją DLQ, konsumer DLQ → status FAILED + notyfikacja WS

### Observability — must-have

- Każdy serwis: `/actuator/prometheus` (Java) lub `/metrics` (Python)
- Każdy log: JSON + `correlation_id` + `trace_id`
- OpenTelemetry: `spring-boot-starter-opentelemetry` (Java) / OpenTelemetry Python SDK (Python); opcjonalnie Java agent
- Metryki biznesowe: `analyses_total`, `cache_hits_total`, `queue_depth`, `inference_latency_seconds`

---

## Struktura repo

Pełna: sekcja 13 planu. Najważniejsze:

```
deepfake-detector/
├── docker-compose.yml              # profile: core, ml, monitoring, auth
├── .env.example
├── infra/                          # keycloak, seaweedfs, prometheus, grafana, loki — RabbitMQ topology nie jest tutaj, deklarujemy ją w kodzie (Spring AMQP @Bean / pika queue_declare)
├── gateway/                        # Java Spring Cloud Gateway
├── eureka-server/                  # Java Spring Cloud Eureka
├── orchestrator/                   # Java Spring Boot — core biznes
├── file-service/                   # Java Spring Boot — upload + SeaweedFS (S3 SDK)
├── video-detector/                 # Python — FastAPI + pika + PyTorch
├── audio-detector/                 # Python — FastAPI + pika + PyTorch
├── frontend/                       # React + TS + Vite (szczegóły: frontend/frontend_instructions.md)
├── load-tests/jmeter/              # plany D4
├── docs/                           # UML, BPMN, raporty MO
└── .github/workflows/              # CI/CD
```

---

## Komendy (dev)

```bash
# Pełny stack (core infra + auth + ml)
docker compose --profile core --profile auth --profile ml up

# Tylko infra (bez aplikacji — lokalny dev z IDE)
docker compose --profile core up

# Z monitoringiem
docker compose --profile core --profile monitoring up

# Backend (z folderu serwisu)
mvn spring-boot:run                     # dev
mvn verify                              # test + integration
mvn package -DskipTests                 # build jar

# Frontend (frontend/)
pnpm dev                                # dev server
pnpm build                              # prod build
pnpm test                               # Vitest
pnpm e2e                                # Playwright

# ML (video-detector/ lub audio-detector/)
uvicorn src.main:app --reload           # dev
pytest                                  # testy
python -m src.training.train            # trening (Colab/Kaggle)
```

---

## Co NIE robić

- **Nie dodawaj Kafki** — mamy RabbitMQ, wymaganie explicit
- **Nie proponuj Kubernetes** — Docker Compose jest wymaganiem
- **Nie usuwaj DLQ / retry / idempotentności** — to jest D6
- **Nie rób Orchestratora stateless klasterowanego** — w MVP SPOF jest OK (healthcheck + restart)
- **Nie używaj MUI / Ant Design / Shadcn/ui / Tailwind** — frontend opiera się na CSS Modules i własnych komponentach
- **Nie dodawaj `guest/guest` dla RabbitMQ ani publicznych S3 creds (SeaweedFS)**
- **Nie commituj `.env`**, plików modeli `.pt`/`.onnx`, dataset binaries
- **Nie edytuj istniejących Flyway migracji** — zawsze nowa wersja
- **Nie podnoś wersji frameworków bez potrzeby** — trzymaj się listy wyżej
- **Nie mieszaj Jackson 2 i Jackson 3** na classpath — wybierz Jackson 3 (domyślny SB 4.0)
- **Nie używaj `Jackson2JsonMessageConverter`** — w SB 4.0 użyj `JacksonJsonMessageConverter`
- **Nie używaj `spring-cloud-dependencies` 2025.0** z Spring Boot 4.0 — wymaga 2025.1 (Oakwood)

---

## Checki przed PR

1. `mvn verify` zielony (Java) / `pytest` zielony (Python) / `pnpm test` zielony (frontend)
2. Lint bez warningów (checkstyle/ruff/eslint)
3. Nowy endpoint → OpenAPI spec zaktualizowany, frontend typy regenerowane
4. Nowa migracja DB → `V{N}__opis.sql`, nie edycja istniejących
5. Nowe metryki → dashboard w Grafanie (import JSON)
6. Zmiana w kontraktach AMQP → sync po stronie konsumentów
7. Security: IDOR guard dla nowego zasobu, `.env` nie w git
8. `docker compose --profile core up` działa na czystej maszynie

---

## Gdy czegoś nie wiesz

1. Sprawdź `DeepfakeDetector_Plan.md` (główne źródło prawdy)
2. Sprawdź README w folderze serwisu
3. Poszukaj istniejącego wzorca w innym serwisie (ta sama technologia — ta sama konwencja)
4. Zapytaj autora odpowiedzialnego za obszar (patrz sekcja 10 planu) — nie zgaduj

---

## Cele jakościowe (target semestr 1)

- ML video intra-dataset (FF++): F1 ≥ 0.85, AUC ≥ 0.90
- ML video cross-dataset (Celeb-DF): accuracy ≥ 0.75
- ML audio ASVspoof eval: EER ≤ 3%
- ML audio cross-dataset (WaveFake): accuracy ≥ 0.70
- Inference p95: video 30s clip < 8s, audio 30s < 3s (ONNX CPU)
- HTTP p95: GET analysis < 50ms (cache hit), < 200ms (cache miss)
- Throughput: ≥ 2 analizy/s per para detektorów

Te liczby są w planie (sekcja 7, 12.5). Jeśli je nie spełniasz — debugujesz, nie obniżasz targetu.
