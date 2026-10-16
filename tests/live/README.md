# Live acceptance (korpus mediów + awarie zależności)

`live_acceptance.py` sprawdza działający, pełny stos (`core` + `auth` + `ml`) na prawdziwych plikach
i przy awariach zależności. Modele detektorów nie są w repozytorium, więc zestaw uruchamia się
lokalnie, nie w CI. Wyniki ostatniego przebiegu: [`docs/reports/live-acceptance.md`](../../docs/reports/live-acceptance.md).

## Co sprawdza

| Test | Scenariusz | Oczekiwanie |
|---|---|---|
| `test_corpus_rejections_happen_at_upload` | wideo > 4K, audio > 30 min, losowe bajty jako `.mp4`, WebM | `422` przy uploadzie, bez zapisu w S3 |
| `test_corpus_accepted_media_reach_a_domain_outcome` | MP3 z okładką 3000×3000, FLAC, WAV z rozszerzeniem `.mp4`, AVI (MJPEG), pion 1080×1920, wideo bez dźwięku, ucięty MP4 | `COMPLETED` albo `FAILED` z kodem domenowym; nigdy `ANALYSIS_TIMEOUT` ani `DEAD_LETTERED` |
| `test_faults_keep_the_baseline_outcome` | restart PostgreSQL, RabbitMQ, Redis, SeaweedFS; crash orchestratora, audio- i video-detectora w trakcie analizy | ten sam status i werdykt co przebieg bez awarii; awaria musi trafić w trwającą analizę |
| `test_zz_no_new_dead_letters` | stan `*.dlq` i `analysis.backend.quarantine` przed i po | brak przyrostu |

Media powstają w trakcie testu (ffmpeg) z `load-tests/jmeter/sample-5mb.mp4`; nic binarnego nie
jest dodawane do repozytorium. Próbka nie zawiera twarzy, więc ścieżka wideo kończy się
domenowym `NO_FACE_DETECTED`; analizy audio dochodzą do werdyktu.

## Uruchomienie

```bash
# 1. Stos z realmem dev (konto deepfake-loadtest) i podniesionymi limitami, jak w CI E2E.
sed -e 's/^BACKPRESSURE_MAX_INFLIGHT_PER_USER=.*/BACKPRESSURE_MAX_INFLIGHT_PER_USER=100/' \
    -e 's|^# KEYCLOAK_REALM_CONFIG=.*|KEYCLOAK_REALM_CONFIG=dev/realm-export.json|' \
    -e 's/^# UPLOAD_RL_BURST=.*/UPLOAD_RL_BURST=6000/' .env.example > live.env
docker compose -p dfd-live --env-file live.env --profile core --profile auth --profile ml up -d --build --wait

# 2. Zestaw (Python 3.10+, ffmpeg na PATH); około 15 minut.
LIVE_COMPOSE="docker compose -p dfd-live --env-file live.env --profile core --profile auth --profile ml" \
LIVE_REPORT=live-report.md python tests/live/live_acceptance.py
```

| Zmienna | Domyślnie | Znaczenie |
|---|---|---|
| `LIVE_COMPOSE` | `docker compose` | komenda compose stosu (awarie i odczyt kolejek) |
| `LIVE_GATEWAY` / `LIVE_KEYCLOAK` | `http://localhost:8080` / `:8180` | adresy stosu |
| `LIVE_CLIENT_ID` / `LIVE_CLIENT_SECRET` | `deepfake-loadtest` / wartość z `.env.example` | konto serwisowe z realmu dev |
| `LIVE_SAMPLE` | `load-tests/jmeter/sample-5mb.mp4` | plik bazowy korpusu |
| `LIVE_ANALYSIS_TIMEOUT` | `900` | limit oczekiwania na wynik (s) |
| `LIVE_REPORT` | — | ścieżka tabeli wyników (Markdown) |

Zestaw restartuje kontenery, więc uruchamiaj go wyłącznie na jednorazowym projekcie compose.
Każda awaria sprawdza status analizy bezpośrednio przed restartem oraz po nim.
Wynik terminalny uzyskany podczas restartu jest porównywany z bazowym; komunikat o spóźnionej
awarii dotyczy wyłącznie analizy zakończonej przed jej wywołaniem.
Powrót stosu do zdrowia jest sprawdzany w `finally`, także po nieudanym restarcie lub asercji.
Sprawdzenie obejmuje `compose ps --all`: kontenery restartujące się, zatrzymane i niezdrowe
blokują następną awarię. Wyjątkiem są znane jednorazowe serwisy inicjalizujące zakończone kodem 0.
Jeśli stos nie wróci do zdrowia w 300 s, seria awarii zostaje przerwana.
Regresje tego mechanizmu (bez modeli i Dockera) uruchamia
`PYTHONUTF8=1 python infra/tests/test_live_acceptance.py`; ten test działa również w CI.

Uploady w obu skryptach są sekwencyjne, więc domyślny limit
`UPLOAD_MAX_CONCURRENT_PER_USER=2` wystarcza. Równoległe testy Playwright w CI
ustawiają `UPLOAD_MAX_CONCURRENT_PER_USER=4`, do globalnego limitu repliki.

## Skalowanie detektorów

`detector_scaling.py` wrzuca N analiz naraz i mierzy, ile czasu uruchomione detektory potrzebują
na opróżnienie kolejki. Metoda i wyniki: [`docs/reports/detector-scaling.md`](../../docs/reports/detector-scaling.md).
