# 12 · Przepływ po stronie UI — co user widzi, co woła i kiedy

**Data:** 2026-10-24 · **Status:** projekt · **Mockupy:**
[`docs/design/ingestion-v2-ui-mockups.html`](../../design/ingestion-v2-ui-mockups.html) ·
**Zadania:** ścieżka **U** w [tablicy](../ingestion-v2-tasks.md)

Ten plik opisuje **jedną stronę kontraktu** — tę, którą widzi człowiek. Projekt backendu jest w
[dokumencie nadrzędnym](../2026-10-24-data-ingestion-conductor-saga.md); tu jest to, czego z niego
wynika dla ekranu: jakie stany istnieją, co w każdym pokazać, które endpointy są wtedy dostępne i
co wolno kliknąć.

---

## 1 · Dlaczego dzisiejsze UI jest trudne — policzone, nie odczute

Żeby wgrać jeden wyciąg, UI musi dziś wykonać **siedem** żądań do ingestion, w ustalonej kolejności,
i po każdym zinterpretować inny kształt odpowiedzi:

| # | żądanie | co UI musi z tego wyczytać |
|---|---|---|
| 1 | `POST /bank-data-adapter/{id}/import` | `stagingSessionId` |
| 2 | `POST …/staging/{s}/ai-categorize` | czy sugestie są gotowe |
| 3 | `POST …/staging/{s}/accept-ai` **albo** N× `POST …/mappings` + `POST …/revalidate` | która z trzech dróg |
| 4 | `GET …/staging/{s}` | czy status to `READY_FOR_IMPORT` |
| 5 | `POST …/import` | `jobId` |
| 6 | `GET …/import/{jobId}` (pętla) | postęp i koniec |
| 7 | `POST /cash-flow/cf={id}/attest-historical-import` | koniec całości |

Trzy rzeczy czynią to trudnym nie przez liczbę, a przez **kształt**:

- **UI musi znać kolejność i reguły procesu.** To ono decyduje, czy wołać `accept-ai`, czy
  `force-uncategorized`, czy konfigurować mapowania po jednym. Logika procesu żyje w przeglądarce.
- **Stan jest w dwóch encjach o dwóch różnych statusach.** Do importu `StagingSessionStatus`, od
  importu `ImportJobStatus`, a przy porażce sesja **gubi** `importJobId` — więc UI nie ma jak
  pokazać przyczyny.
- **Każdy wiersz bez kategorii blokuje całość.** `start-import` odmawia, dopóki cokolwiek jest
  `PENDING_MAPPING`, więc user musi rozstrzygnąć **wszystko** — na pliku Pekao to 165 grup.

---

## 2 · Nowy model: jeden stan, jedno źródło prawdy, dwa żądania zmieniające

```
   user                                UI                              backend
    │                                   │                                 │
    │  wybiera plik ─────────────────▶  │                                 │
    │                                   │  POST /api/v2/ingestion ──────▶ │  202 + sessionId
    │                                   │                                 │
    │  patrzy na postęp  ◀────────────  │  GET  …/{id}   (polling 2 s) ◀─ │  faza + kroki + liczniki
    │                                   │                                 │
    │  odpowiada na ≤7 pytań ────────▶  │  POST …/{id}/decisions ───────▶ │  nowy stan W ODPOWIEDZI
    │                                   │                                 │
    │  widzi podsumowanie ◀───────────  │  GET  …/{id}   (polling) ◀───── │  IMPORTED
    │                                   │                                 │
    │  atestuje (cashflow) ──────────▶  │  POST /cash-flow/…/attest ────▶ │
```

**Dwie zasady, z których wynika cała reszta:**

1. **Jedynym źródłem stanu jest `GET /api/v2/ingestion/{id}`.** UI nie wyprowadza fazy z niczego
   innego, nie trzyma jej u siebie i nie zgaduje, co będzie dalej. Każdy ekran jest funkcją
   `phase` z tej odpowiedzi.
2. **`POST …/decisions` zwraca nowy stan w tej samej odpowiedzi.** Nie ma okna, w którym ekran
   pokazuje `AWAITING_DECISION` po tym, jak user już odpowiedział — i dlatego decyzje **nie** idą
   przez kolejkę.

---

## 3 · Mapa ekranów: faza → co user widzi

| faza sesji | ekran | co dominuje na ekranie | akcje |
|---|---|---|---|
| `RECEIVED`, `PARSED` | **S1 Przetwarzanie** | „Odczytano 402 wiersze", lista kroków z ✓ i spinnerem | *Przerwij* |
| `VALIDATED` | S1 | + liczniki: ważne / duplikaty / odrzucone | *Przerwij*, *Pokaż odrzucone* |
| `CLASSIFIED`, `RESOLVED` | S1 | + „rozstrzygnięto 30 z 38 grup" i koszt klasyfikacji | j.w. |
| **`AWAITING_DECISION`** | **S2 Decyzje** | ≤7 pytań, każde z licznikiem, sumą, próbkami i rekomendacją | *Zatwierdź wszystkie*, *Zmień pojedyncze*, *Przerwij* |
| `PROVISIONING_CATEGORIES`, `IMPORTING` | **S3 Import** | pasek postępu (co 50 wierszy), „381 z 381" | — (import nie jest przerywalny) |
| `IMPORTED` | **S4 Sukces** | co weszło, co pominięte i **dlaczego**, rozbicie po miesiącach | *Atestuj*, *Wycofaj*, *Przejrzyj prowizoryczne* |
| `PARTIALLY_IMPORTED` | **S5 Częściowy** | to samo + wyróżniona lista wierszy, które nie weszły | *Ponów nieudane*, *Atestuj resztę*, *Wycofaj* |
| `FAILED` | **S6 Porażka** | przyczyna z `failure`, krok, na którym padło, i co jest w cash flow | *Wycofaj*, *Zacznij od nowa* |
| `COMPENSATED` | S6 | „cash flow wrócił do stanu przed importem" | *Zacznij od nowa* |
| `EXPIRED`, `ABANDONED` | S7 Zamknięta | dlaczego sesja się skończyła, bez straszenia | *Zacznij od nowa* |

**Czego na tej liście nie ma i to jest celowe:** osobnego ekranu mapowań, osobnego ekranu „AI
sugestie", osobnego przycisku „pomiń kategoryzację". W v1 to są trzy różne ścieżki, które user musi
wybrać, nie wiedząc, czym się różnią. W v2 jest jeden ekran decyzji z rekomendacją przy każdym
pytaniu — a „pomiń" to po prostu *Zatwierdź wszystkie* bez zmieniania niczego.

---

## 4 · Endpointy: kiedy dostępne i kto je woła

| endpoint | dostępny w fazie | kto woła | co UI z tym robi |
|---|---|---|---|
| `POST /api/v2/ingestion` | — (tworzy sesję) | user (przycisk) | zapisuje `sessionId`, przechodzi na S1 |
| `GET /api/v2/ingestion/{id}` | **zawsze** | UI, pollingiem | **renderuje wszystko** |
| `GET /api/v2/ingestion?cashFlowId=` | zawsze | UI (lista historii) | lista sesji cash flow |
| `GET /api/v2/ingestion/{id}/rows?status=` | od `VALIDATED` | user (rozwinięcie) | tabele odrzutów, duplikatów, prowizorycznych |
| `POST /api/v2/ingestion/{id}/decisions` | **tylko `AWAITING_DECISION`** | user (jedno kliknięcie) | renderuje **zwrócony** stan, bez dodatkowego GET |
| `POST /api/v2/ingestion/{id}/abandon` | każda faza **przed** `IMPORTING` | user (*Przerwij*) | przechodzi na S7 |
| `POST /api/v2/ingestion/{id}/compensate` | `IMPORTED`, `PARTIALLY_IMPORTED`, `FAILED` — i tylko gdy cash flow jest w SETUP oraz okno 1 h nie minęło | user (*Wycofaj*, z potwierdzeniem) | przechodzi na S6 |
| `POST /cash-flow/cf={id}/attest-historical-import` | po `IMPORTED` | user (*Atestuj*) | **kończy okno wycofania** — UI musi to powiedzieć wprost przed kliknięciem |

### Polling, bo websocketa jeszcze nie ma

Topic faktów i jego konsument to zadanie **X3** (faza P4). Do tego czasu:

- `GET …/{id}` co **2 s**, dopóki faza nie jest końcowa;
- co **1 s** w fazie `IMPORTING`, bo postęp przychodzi co 50 wierszy i rzadsze odpytywanie wygląda
  jak zawieszenie;
- **stop** na `IMPORTED`, `PARTIALLY_IMPORTED`, `FAILED`, `COMPENSATED`, `EXPIRED`, `ABANDONED`;
- `AWAITING_DECISION` jest stanem **oczekiwania na człowieka** — polling zwalnia do 30 s (sesja może
  się sama rozstrzygnąć po TTL, patrz §6).

### Odmowy wejściowe, które UI musi umieć pokazać

| kod | co znaczy | co UI pokazuje |
|---|---|---|
| **404** `CASHFLOW_NOT_FOUND` | nie ma takiego cash flow | „Nie znaleziono tego cash flow" + powrót do listy |
| **409** `INGESTION_SESSION_ALREADY_ACTIVE` | inna sesja tego cash flow jest w toku | link **do tej sesji**, nie komunikat o błędzie |
| **400** `CASHFLOW_CLOSED` | cash flow zamknięty | „Ten cash flow jest zamknięty i nie przyjmuje importów" |
| **400** `INGESTION_INVALID_CSV` | plik nie jest kanonicznym CSV | nazwa brakującej kolumny z komunikatu |
| **4xx** rachunek nie ten | wyciąg innego rachunku (**M8**) | **oba numery rachunków** obok siebie — to jedyny komunikat, który user potrafi sam zweryfikować |

---

## 5 · Co user musi zobaczyć, a dziś nie ma jak

Pięć rzeczy, których v1 nie wystawia, a które w v2 są w jednej odpowiedzi:

1. **Dlaczego wiersz nie wszedł — per wiersz.** `rejected[]` z numerem linii, `ErrorCode` i zdaniem.
   Dziś user dostaje status sesji `HAS_VALIDATION_ERRORS` i nic więcej.
2. **Z czym koliduje duplikat.** `duplicates[]` z `bankTransactionId` **i** id istniejącego cash
   change, żeby dało się kliknąć i sprawdzić. Na pliku Pekao jest jeden duplikat **od banku** — ten
   sam numer referencyjny na obu stronach przelewu wewnętrznego — i user musi móc to zrozumieć.
3. **Które wiersze są prowizoryczne.** Grupy rozstrzygnięte fallbackiem mają `provisional = true`.
   To jest lista „do przejrzenia później", bez której tryb „nie pytaj, zaimportuj" byłby kłamstwem.
4. **Ile wartości pokryły zadane pytania.** Na Nest 4 pytania to 90 % wartości; na Pekao
   7 pytań to **znacznie mniej**, bo 90 % wymagałoby 35. Ekran decyzji musi to powiedzieć liczbą
   („zadane pytania obejmują 61 % wartości wyciągu"), inaczej user myśli, że rozstrzygnął wszystko.
   Wymaga pola `valueCovered` — zadanie **S16**.
5. **Co zostało w cash flow po porażce.** `failure` + `compensations.importedCashChangeIds.size()`,
   żeby ekran porażki mówił „w cash flow jest 299 z 381 transakcji", a nie „coś się nie udało".

---

## 6 · Trzy ścieżki, które muszą wyglądać inaczej niż błąd

**Odrzucone wiersze nie są błędem.** Sześć wierszy spoza okna importu to informacja, nie awaria:
import idzie dalej, a ekran sukcesu pokazuje je jako „pominięte, z powodem". Czerwień należy się
tylko fazie `FAILED`.

**Decyzja rozstrzygnięta po TTL nie jest utratą.** Gdy user nie odpowie w 24 h, backend stosuje
rekomendację i zapisuje `autoResolved = TTL`. UI po powrocie pokazuje: „nie odpowiedziałeś w 24 h,
więc zastosowaliśmy rekomendacje — oto co wyszło, możesz to zmienić w cash flow". W v1 ta sytuacja
oznacza **skasowaną sesję i stracony upload**, czyli ponowną transformację AI za pieniądze.

**Częściowy import jest wynikiem, nie porażką.** 381 z 402 wierszy w cash flow to stan, w którym
user może atestować resztę albo ponowić nieudane. Ekran nie powinien sugerować, że trzeba wycofać
wszystko.

---

## 7 · Czego UI robić nie wolno

| nie wolno | dlaczego |
|---|---|
| liczyć salda ani różnicy do atestacji | saldo jest sprawą cashflow; UI, które je liczy, zacznie „poprawiać" różnicę — a to utrwala błąd waluty z §25.2a nieodwracalnie |
| zgadywać waluty wiersza ani przeliczać kursem | wyciąg Pekao **nie zawiera** kwoty w PLN dla wierszy EUR; nie ma czego przeliczyć |
| pokazywać `Inne` jako kategorię rozstrzygniętą | to kategoria generyczna banku; po **R9** takie wiersze idą do wzorców i AI, a nie do kosza, który udaje kategorię |
| trzymać fazę w stanie lokalnym i renderować z niej | źródłem jest odpowiedź; faza zmienia się też **bez** udziału usera (TTL, harmonogram odzysku) |
| blokować ekran na czas importu | import jedzie poza żądaniem HTTP; user może wyjść i wrócić do sesji z listy |

---

## 8 · Poprawa doświadczenia — w liczbach

| | v1 | v2 |
|---|---|---|
| żądania zmieniające stan, które UI musi wykonać | **7** | **2** (start + decyzje) |
| ekrany, przez które user musi przejść | 5 (upload, mapowania, sugestie, podgląd, import) | **3** (start, decyzje, podsumowanie) |
| punkty decyzyjne, Nest 402 wiersze | do 22 grup, **wszystkie blokujące** | **4 pytania** |
| punkty decyzyjne, Pekao 791 wierszy | do 165 grup, **wszystkie blokujące** | **≤7 pytań** |
| drugi wyciąg tego samego banku | tyle samo pytań | **0 pytań** |
| jeden zły wiersz | blokuje cały import | import idzie, wiersz w „pominięte z powodem" |
| brak odpowiedzi 24 h | **upload stracony**, transformacja AI do powtórzenia | rekomendacja zastosowana, sesja czytelna |
| przyczyna porażki | sesja gubi `importJobId`, UI nie ma czego pokazać | `failure` + krok + co zostało w cash flow |
| logika procesu | w przeglądarce (która droga, w jakiej kolejności) | w conductorze; UI renderuje `phase` |

Największa zmiana nie jest w liczbie żądań, a w tym, **o co user jest pytany**: w v1 odpowiada na
pytania, które odblokowują maszynę, w v2 — tylko na te, które poprawiają wynik.

---

## 9 · Do rozstrzygnięcia

1. **Czy *Przerwij* ma kasować wiersze od razu**, czy zostawić je do TTL (szybszy powrót, gdy user
   przerwał przez pomyłkę)? Proponuję: zostawić, faza `ABANDONED`, wiersze do TTL.
2. **Czy ekran decyzji ma pozwalać na „zapytaj o więcej grup"** (powiększenie limitu na życzenie)?
   Na Pekao to różnica między 7 i 35 pytaniami — przydałby się przycisk *Pokaż kolejne 7*.
3. **Gdzie żyje przegląd prowizorycznych** po imporcie: w ingestion (lista z sesji) czy w cash flow
   (lista transakcji z flagą)? Druga opcja jest trwalsza, ale wymaga `recategorize` (**X1**).
