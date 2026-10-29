# Analiza wsadowa (CLI) i metryki jakości

`batch_analyze.py` analizuje wszystkie nagrania z folderu przez REST API (tak jak frontend: upload →
`POST /api/analysis` → odpytywanie `GET /api/analysis/{id}`) i zapisuje wynik do CSV, jeden wiersz
na plik. Przerwany przebieg wznawia się, uruchamiając tę samą komendę ponownie. Z folderami
`real`/`fake` liczy precision, recall, F1, accuracy i macierz pomyłek. Wymaga tylko Pythona 3.11+
(biblioteka standardowa) i działającego stosu z detektorami.

```bash
python tools/batch/batch_analyze.py recordings/ --out results.csv
python tools/batch/batch_analyze.py celeb-df-test/ --labels-from-folders --out celebdf.csv
python tools/batch/batch_analyze.py celeb-df-test/ --metrics-only --out celebdf.csv --metrics-out metrics.json
```

Na Windows ustaw `PYTHONUTF8=1`.

## Logowanie

Domyślnie logujesz się jako zwykły użytkownik (klient `deepfake-cli`, device authorization grant):
narzędzie wypisuje link i kod, otwierasz link w przeglądarce, logujesz się tym samym kontem co w
UI i potwierdzasz dostęp („Tak”), a analizy trafiają do Twojej historii. Token odświeża się sam przed wygaśnięciem; gdy sesja
Keycloak się skończy (30 min bezczynności, maks. 10 h), narzędzie poprosi o ponowne logowanie.
Wszystkie wątki współdzielą jedną próbę logowania: jej odrzucenie lub wygaśnięcie kończy przebieg
kodem 3, bez kolejnych kodów wyświetlanych przez pozostałe wątki. Tokenów nigdy nie wypisuje.

Do przebiegów bez człowieka (CI, noc) podaj `--client-secret` / `BATCH_CLIENT_SECRET`: wtedy
token jest pobierany przez `client_credentials` dla konta serwisowego `deepfake-loadtest` z realmu
dev (sekret `LOADTEST_CLIENT_SECRET` z `.env.example`, realm po ustawieniu w `.env`
`KEYCLOAK_REALM_CONFIG=dev/realm-export.json`, patrz `infra/keycloak/README.md`). Analizy trafiają
wtedy do historii tego konta.

| Flaga | Zmienna | Domyślnie |
|---|---|---|
| `--gateway` | `BATCH_GATEWAY` | `http://localhost:8080` |
| `--keycloak` | `BATCH_KEYCLOAK` | `http://localhost:8180` |
| `--realm` | `BATCH_REALM` | `deepfake` |
| `--client-id` | `BATCH_CLIENT_ID` | `deepfake-cli`, z sekretem `deepfake-loadtest` |
| `--client-secret` | `BATCH_CLIENT_SECRET` | brak (logowanie w przeglądarce) |

Lokalny `.env` z własnym `JWT_ALLOWED_CLIENTS` musi zawierać `deepfake-cli`, inaczej API odpowie
`401` (kod 3).

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
Plik `results.csv.meta.json` obok CSV (odpowiednio dla własnego `--out`) zapisuje pełną ścieżkę
katalogu źródłowego i identyfikatory uploadów/analiz potrzebne do sprzątania oraz odzyskiwania
prób. Przechowuj go razem z CSV; nie zawiera tokenów ani sekretów. Przepisywanie CSV i metadanych
używa pliku tymczasowego, `flush` i `fsync` przed atomowym `os.replace`.

## Limity i ponawianie

Limity backendu zostają bez zmian (10 uploadów/min, 2 równoległe uploady, 3 aktywne analizy,
5 GiB na użytkownika); narzędzie czeka:

- `--concurrency 3` analiz w toku naraz, najwyżej 2 uploady równocześnie, uploady co
  `--upload-interval 6` s (przy podniesionym `UPLOAD_RL_*` można dać `0`);
- `429` i `503` czekają `Retry-After` (co najmniej 1 s, najwyżej 300 s);
  odpowiedzi bez nagłówka (np. `503` gatewaya przy awarii
  Redis) i błędy sieci czekają 5 s, potem 10, 20, 40 i 60 s. `429` jest ponawiane bez limitu,
  przejściowe błędy 8 razy, potem wiersz `ERROR`, z wyjątkiem startu analizy opisanego poniżej;
- odpowiedź `4xx` (np. `422 INVALID_FILE`) to wiersz `REJECTED`, a `FAILED` z detektora to wiersz
  `FAILED`; przebieg idzie dalej;
- na wynik jednej analizy czeka się `--timeout 1800` s, potem narzędzie próbuje anulować ją przez
  `DELETE /api/analysis/{id}` i zapisuje `ERROR [CLIENT_TIMEOUT]` z wynikiem anulowania;
- przekroczenie limitu miejsca (`409 STORAGE_QUOTA_EXCEEDED`) albo odrzucony token kończy
  przebieg (kod 3) po dokończeniu plików w toku; bieżący plik nie dostaje wiersza.

`POST /api/analysis` nie jest idempotentny. Automatycznie ponawiane są tylko odmowy `429`, `503`
i odmowa połączenia. Po `502`, `504`, innym `5xx`, resecie lub timeout narzędzie przegląda strony
historii (`GET /api/analysis?size=100&page=…`) i szuka analizy o tym samym `fileId`. Znalezioną
analizę dalej odpytuje. Brak potwierdzenia daje `ERROR [START_UNCERTAIN]`, bez kolejnego POST;
przy wznowieniu najpierw ponawia odszukanie tej samej analizy, bez nowego uploadu. Gdy analiza
nadal nie jest widoczna, sprawdź historię i stan serwera przed rozpoczęciem nowego przebiegu
z osobnym `--out`. Lista analiz może chwilowo nie pokazać jeszcze niezakończonego commitu.

Po utrwaleniu wiersza `ERROR`, `REJECTED` lub `CANCELLED` CLI próbuje usunąć znany upload
przez `DELETE /api/files/{id}` (z wyjątkiem nierozstrzygniętego startu i nieudanej próby sprzątania).
Przed zastąpieniem poprzedniej próby, także przy `--retry-failed`, anuluje starą analizę,
jeśli jeszcze trwa, i usuwa jej upload. Nieudane sprzątanie zostaje w metadanych; wznowienie
ponawia je przed nowym uploadem, a błąd daje `ERROR [CLEANUP_PENDING]`. Bieżące wyniki
`COMPLETED` i `FAILED` zachowują plik dla podglądu do czasu zastąpienia próby lub usunięcia w UI.

Usunięcie uploadu jest soft-delete: plik znika z API od razu, ale nadal liczy się do limitu
5 GiB do fizycznego purge po 72 h (plus do godziny na skan). Ponowienia mogą więc czasowo
wyczerpać quota nawet przy automatycznym sprzątaniu; CLI nie obiecuje natychmiastowego
zwolnienia miejsca. Podgląd starszej, zastąpionej analizy nie będzie już dostępny.

Przepustowość wyznacza czas detektorów, nie narzędzie: przy 3 analizach w toku to mniej więcej
`3 / czas jednej analizy`, najwyżej 10 plików na minutę.

## Wznawianie

`Ctrl+C` przerywa przebieg (kod 130); zapisane wiersze zostają. Analizy w toku są anulowane na
serwerze (`DELETE /api/analysis/{id}`), a uploady potwierdzonych anulowań oraz prób,
które jeszcze nie wystartowały, są usuwane
(`DELETE /api/files/{id}`; dwa żądania po maks. 5 s, równolegle dla prób, bez gwarancji),
chyba że podasz `--no-cancel`
— wtedy kończą się na serwerze. Przerwane pliki nie dostają wiersza, więc wznowienie analizuje je
od nowa. Ponowne uruchomienie z tym samym folderem i `--out` pomija pliki, które mają już wiersz,
z wyjątkiem wierszy `ERROR` i uciętych wierszy, które są analizowane ponownie. `--retry-failed`
analizuje ponownie także `FAILED`, `CANCELLED` i `REJECTED`; pomijane są wtedy tylko `COMPLETED`.
Przed wznowieniem CSV jest przepisywany tak, by każdy plik miał co najwyżej jeden wiersz.
Plik CSV z innym nagłówkiem, metadanymi innego katalogu lub bez metadanych nie jest
nadpisywany (kod 2). Dla starszego CSV bez `.meta.json` użyj nowego `--out`; stare wyniki można
nadal odczytać przez `--metrics-only`. Przeniesienie katalogu wymaga osobnego `--out`, ponieważ
CLI nie potwierdzi tożsamości plików tylko po ścieżkach względnych.

## Metryki (`--labels-from-folders`)

Etykieta pliku to najbliższy folder nadrzędny o nazwie `real` lub `fake` (wielkość liter bez
znaczenia) wewnątrz analizowanego folderu, np. `celeb-df-test/fake/id0_0001.mp4`. Metryki liczone
są dla klasy pozytywnej FAKE, tylko z plików z etykietą i werdyktem (`COMPLETED`):

- macierz pomyłek TP/FP/TN/FN, precision, recall, F1, accuracy, specificity, balanced accuracy;
  wartość niezdefiniowana (dzielenie przez zero) to `n/d` w konsoli i `null` w JSON;
- pliki bez werdyktu (`FAILED`, `REJECTED`, `ERROR`, `CANCELLED`) nie są predykcjami: są liczone
  osobno według kodu z `blad` (np. `NO_FACE_DETECTED`), łącznie i per etykieta;
- pliki bez etykiety są wypisywane i pomijane.

Metryki po przebiegu obejmują tylko pliki odkryte w katalogu źródłowym. Stare wiersze dla
usuniętych plików pozostają w CSV, ale są pomijane w metrykach; liczba pominiętych jest
drukowana i zapisywana w JSON jako `stale_rows_ignored`. `--metrics-only` przelicza wyniki bez
serwera, odkrywając pliki w katalogu jawnie podanym jako `folder`; metadane jedynie potwierdzają
zgodność katalogu. Przy CSV z metadanymi brak `folder` daje kod 2. Katalog musi być dostępny.
Tylko dla starszego CSV bez metadanych i bez podanego katalogu
metryki obejmują cały CSV.

## Testy

Bez Dockera i modeli (fałszywe API w wątku `http.server`); w CI w jobie `python (tools)`:

```bash
PYTHONUTF8=1 python -m unittest discover -s tools/batch/tests -v
cd tools && uvx ruff check .
```

Nie obsługuje archiwów ZIP; rozpakuj je przed uruchomieniem.
