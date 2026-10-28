# Analiza wsadowa (CLI) i metryki jakości

`batch_analyze.py` analizuje wszystkie nagrania z folderu przez REST API (tak jak frontend: upload →
`POST /api/analysis` → odpytywanie `GET /api/analysis/{id}`) i zapisuje wynik do CSV, jeden wiersz
na plik. Przerwany przebieg wznawia się, uruchamiając tę samą komendę ponownie. Z folderami
`real`/`fake` liczy precision, recall, F1, accuracy i macierz pomyłek. Wymaga tylko Pythona 3.11+
(biblioteka standardowa) i działającego stosu z detektorami.

```bash
python tools/batch/batch_analyze.py recordings/ --out results.csv
python tools/batch/batch_analyze.py celeb-df-test/ --labels-from-folders --out celebdf.csv
python tools/batch/batch_analyze.py --metrics-only --out celebdf.csv --metrics-out metrics.json
```

Na Windows ustaw `PYTHONUTF8=1`.

## Logowanie

Token pobierany jest z Keycloak (`client_credentials`) i odnawiany przed wygaśnięciem, więc
wielogodzinne przebiegi działają bez przerw. Domyślnie to konto serwisowe `deepfake-loadtest`
z realmu dev (jak w `tests/live`), dostępne po ustawieniu w `.env`
`KEYCLOAK_REALM_CONFIG=dev/realm-export.json` (patrz `infra/keycloak/README.md`). Analizy trafiają
do historii tego konta, nie do konta użytkownika w UI.

| Flaga | Zmienna | Domyślnie |
|---|---|---|
| `--gateway` | `BATCH_GATEWAY` | `http://localhost:8080` |
| `--keycloak` | `BATCH_KEYCLOAK` | `http://localhost:8180` |
| `--realm` | `BATCH_REALM` | `deepfake` |
| `--client-id` | `BATCH_CLIENT_ID` | `deepfake-loadtest` |
| `--client-secret` | `BATCH_CLIENT_SECRET` | `LOADTEST_CLIENT_SECRET` z `.env.example` |

## Pliki i typ analizy

Folder jest przeszukiwany rekurencyjnie; brane są MP4, MOV, AVI, WAV, MP3 i FLAC (wielkość liter
w rozszerzeniu bez znaczenia), w stałej kolejności ścieżek. Ukryte pliki i foldery (`.git`,
`._clip.mp4` z macOS) są pomijane. Typ wybiera się jak we frontendzie: z MIME wykrytego przez
serwer `AUDIO` dla audio, `FULL` dla wideo (WAV nazwany `.mp4` dostanie `AUDIO`). `--type
VIDEO|AUDIO|FULL` wymusza jeden typ dla wszystkich plików. Pliki powyżej 500 MiB są odrzucane bez
uploadu.

## CSV

Nagłówek: `plik,typ,status,werdykt,prob_wideo,prob_audio,blad,czas_s` (UTF-8, przecinek).

| Kolumna | Zawartość |
|---|---|
| `plik` | ścieżka względem folderu, z `/` |
| `typ` | `FULL`, `AUDIO` lub `VIDEO` |
| `status` | `COMPLETED`, `FAILED`, `CANCELLED` (z API) albo `REJECTED` (API odrzuciło plik, 4xx) lub `ERROR` (narzędzie poddało się: brak wyniku w czasie, 5xx, sieć) |
| `werdykt` | `FAKE`/`REAL`, tylko dla `COMPLETED` |
| `prob_wideo`, `prob_audio` | `videoProb`, `audioProb` z API; puste, gdy źródło nie było analizowane |
| `blad` | `[KOD] komunikat`, np. `[NO_FACE_DETECTED] …`, `[MEDIA_TOO_LONG] …`, `[CLIENT_TIMEOUT] …` |
| `czas_s` | czas od uploadu do wyniku, łącznie z czekaniem na limity |

Każdy wiersz jest zapisywany i zrzucany na dysk zaraz po zakończeniu pliku, w kolejności kończenia.

## Limity i ponawianie

Limity backendu zostają bez zmian (10 uploadów/min, 2 równoległe uploady, 3 aktywne analizy,
5 GB na użytkownika); narzędzie czeka:

- `--concurrency 3` analiz w toku naraz, najwyżej 2 uploady równocześnie, uploady co
  `--upload-interval 6` s (przy podniesionym `UPLOAD_RL_*` można dać `0`);
- `429` i `503` czekają `Retry-After`; odpowiedzi bez nagłówka (np. `503` gatewaya przy awarii
  Redis) i błędy sieci czekają 5 s, potem 10, 20, 40 i 60 s. `429` jest ponawiane bez limitu,
  `5xx` i błędy sieci 8 razy, potem wiersz `ERROR`;
- odpowiedź `4xx` (np. `422 INVALID_FILE`) to wiersz `REJECTED`, a `FAILED` z detektora to wiersz
  `FAILED`; przebieg idzie dalej;
- na wynik jednej analizy czeka się `--timeout 1800` s, potem `ERROR [CLIENT_TIMEOUT]`;
- przekroczenie limitu miejsca (`409 STORAGE_QUOTA_EXCEEDED`) albo odrzucony token kończy
  przebieg (kod 3) po dokończeniu plików w toku; bieżący plik nie dostaje wiersza.

Przepustowość wyznacza czas detektorów, nie narzędzie: przy 3 analizach w toku to mniej więcej
`3 / czas jednej analizy`, najwyżej 10 plików na minutę.

## Wznawianie

`Ctrl+C` przerywa przebieg (kod 130); zapisane wiersze zostają, a analizy w toku kończą się na
serwerze. Ponowne uruchomienie z tym samym folderem i `--out` pomija pliki, które mają już wiersz,
z wyjątkiem wierszy `ERROR` i uciętych wierszy, które są analizowane ponownie. `--retry-failed`
analizuje ponownie także `FAILED`, `CANCELLED` i `REJECTED`; pomijane są wtedy tylko `COMPLETED`.
Przed wznowieniem CSV jest przepisywany tak, by każdy plik miał co najwyżej jeden wiersz.
Plik CSV z innym nagłówkiem nie jest nadpisywany (kod 2).

## Metryki (`--labels-from-folders`)

Etykieta pliku to najbliższy folder nadrzędny o nazwie `real` lub `fake` (wielkość liter bez
znaczenia) wewnątrz analizowanego folderu, np. `celeb-df-test/fake/id0_0001.mp4`. Metryki liczone
są dla klasy pozytywnej FAKE, tylko z plików z etykietą i werdyktem (`COMPLETED`):

- macierz pomyłek TP/FP/TN/FN, precision, recall, F1, accuracy, specificity, balanced accuracy;
  wartość niezdefiniowana (dzielenie przez zero) to `n/d` w konsoli i `null` w JSON;
- pliki bez werdyktu (`FAILED`, `REJECTED`, `ERROR`, `CANCELLED`) nie są predykcjami: są liczone
  osobno według kodu z `blad` (np. `NO_FACE_DETECTED`), łącznie i per etykieta;
- pliki bez etykiety są wypisywane i pomijane.

Metryki są drukowane po przebiegu i zapisywane do `--metrics-out` (JSON). `--metrics-only`
przelicza je z istniejącego CSV bez serwera.

## Testy

Bez Dockera i modeli (fałszywe API w wątku `http.server`); w CI w jobie `python (tools)`:

```bash
PYTHONUTF8=1 python -m unittest discover -s tools/batch/tests -v
cd tools && uvx ruff check .
```

Nie obsługuje archiwów ZIP; rozpakuj je przed uruchomieniem.
