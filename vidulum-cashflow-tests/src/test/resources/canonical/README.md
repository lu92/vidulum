# Fixture: wyciągi w formacie kanonicznym

Dwa pliki w 17-kolumnowym formacie kanonicznym — tym, którego oczekuje
`CsvParserService.HEADERS` i którego nie było w repo w żadnej postaci:

| plik | bank | wiersze | zakres dat | waluty |
|---|---|---|---|---|
| `nestbank-canonical.csv` | Nest Bank | **402** | 2023-01-13 … 2025-12-31 | PLN |
| `pekao-canonical.csv` | Pekao SA | **791** | 2025-01-11 … 2026-01-09 | PLN (789) + **EUR (2)** |

Źródłem są surowe wyciągi w katalogu wyżej: `nestbank_lista_operacji_20260111.csv` (409 linii:
6 metadanych, nagłówek, 402 operacje) oraz `pekao_sa_Lista_operacji_20260111_013400.csv`
(792 linie, separator `;`).

## Jak powstały — i czym to nie jest

Wytworzył je **`tools/fixtures/make_canonical_csv.py`**, czyli reguły wypisane ręcznie, a nie
przebieg produkcyjnego pipeline'u. W adapterze te same dwie decyzje podejmuje model: najpierw
reguły mapowania kolumn (`MappingRules`, cache'owane i stosowane potem lokalnie przez
`LocalCsvTransformer`), potem enrichment (`merchant`, `classification`, `location`). Reguły w
skrypcie powstały po sprofilowaniu obu wyciągów — typy operacji, kategorie banku, najczęstsi
kontrahenci — czyli z tego samego materiału, który dostałby model.

**Zaleta dla fixture'u:** determinizm. Ten sam plik wejściowy daje bit w bit ten sam wynik, bez
klucza API i bez rachunku za tokeny.
**Koszt:** `merchant` i `location` są słabsze niż z modelu (pomiar niżej).

Gdy klucz będzie dostępny, `CanonicalCsvFixtureGenerator` w `vidulum-cashflow-tests` wytworzy te
same pliki **prawdziwym adapterem** (`@EnabledIfEnvironmentVariable(ANTHROPIC_API_KEY)`), a różnica
między wynikami jest miarą tego, co model wnosi ponad reguły.

## Czym te pliki są pilnowane

`CanonicalFixtureMatchesRawStatementTest` porównuje je z surowymi wyciągami przy każdym przebiegu
testów: liczba wierszy, **sumy uznań i obciążeń co do grosza**, zakres dat, zbiór walut i unikalność
identyfikatorów. Fixture, którego nikt nie weryfikuje, zaczyna kłamać po pierwszej ręcznej edycji.

Dla Nest sumy da się sprawdzić **wobec metadanych samego banku** — wyciąg deklaruje
`Suma uznań: 1217096.34 PLN` i `Suma obciążeń: -1141912.03 PLN`, i tyle wychodzi z pliku
kanonicznego. To najmocniejszy dowód, że przepisanie nie zgubiło ani nie zniekształciło pieniędzy.

## Co w tych danych jest i warto o tym wiedzieć przy pisaniu testów

| właściwość | Nest | Pekao | dlaczego ma znaczenie |
|---|---|---|---|
| wiersze | 402 | 791 | — |
| INFLOW / OUTFLOW | 37 / 365 | 74 / 717 | obie strony reprezentowane |
| `SELF_TRANSFER` | **111** | 30 | przelewy między rachunkami tej samej osoby: Nest → Pekao. Wykrycie po IBAN-ie kontrahenta ma na czym działać |
| `BANK_FEE` | 31 | 19 | grupa auto-kategorizowalna, pomijana przez model |
| `CASH_WITHDRAWAL` | 0 | 15 | tylko Pekao ma wypłaty kartą i BLIK-iem |
| `UNKNOWN` | 1 | 12 | wiersze, w których ani kontrahent, ani tytuł nie wskazują strony |
| metody płatności | TRANSFER 371, OTHER 30, CARD 1 | CARD 492, TRANSFER 173, BLIK 96, DIRECT_DEBIT 23, OTHER 7 | Pekao jest wyciągiem kartowym, Nest przelewowym — dwa różne kształty danych |
| kategoria banku | 4 wartości (rodzaj operacji) | **36 wartości semantycznych** | Pekao trafia bezpośrednio w kategorie użytkownika (priorytet 0 drabiny), Nest nie trafia wcale |
| duplikat `bankTransactionId` | brak | **1 para** | bank nadał **ten sam numer referencyjny obu stronom** przelewu wewnętrznego (`1342556624908662`, +30 i −30 PLN tego samego dnia). Prawdziwy przypadek do testu deduplikacji — i otwarte pytanie, czy idempotencja keyowana samym id nie zgubi drugiej strony |
| wiele walut | nie | **2 wiersze EUR** | `CLAUDE.AI SUBSCRIPTION` i `ETSY.COM`. Cash flow jest jednowalutowy, więc te dwa wiersze to gotowy przypadek graniczny |

## Co wyszło dopiero na Pekao — i co wymaga interwencji

Cały projekt był mierzony na Nest. Drugi wyciąg pokazał cztery rzeczy, których jeden bank pokazać
nie mógł (zadania **M7**, **M8**, **M9**, **K15**, **X7**, **R9**, **R10**):

| co | Nest | Pekao | konsekwencja |
|---|---|---|---|
| waluty | 1 | **2** (2 wiersze EUR) | `Money.plus` bierze kwotę drugiej strony i **swoją** walutę bez kontroli, a import w cashflow nie patrzy na walutę ani razu → 22,14 EUR wchodzi jako 22,14 PLN i wychodzi przy atestacji jako `CASHFLOW_BALANCE_MISMATCH` bez wyjaśnienia |
| `operationDate` ≠ `bookingDate` | **1** wiersz | **292** wiersze, z tego **24 przez granicę miesiąca** | wybór daty przesuwa 24 transakcje między miesiącami i decyduje, czy wpadną w miesiąc read-only |
| kategorie generyczne (`Inne`, `Bez kategorii`) | 0 | **265 z 791** | priorytet 0 drabiny dopasowałby je bezpośrednio, wrzucając 173 wiersze do kosza i mijając wzorce oraz AI |
| pozycje w planie kategorii | **4** | **38**, z czego 11 to grupy po 1–2 wiersze | jeden wyciąg kartowy zaśmieca drzewo kategorii na stałe — kategorie zostają nawet po wycofaniu importu |
| rachunek w cash flow | — | — | `CashFlow` ma **jeden** `bankAccount`, a okresy obu wyciągów nachodzą się na cały 2025 rok, więc import Pekao do cash flow Nest przechodzi po cichu. `CashFlowSkeletonView` nie wystawia rachunku, więc dziś nie da się tego sprawdzić przez kontrakt |

Dodatkowo oba wyciągi należą do tej samej osoby, a przelewy między nimi to **te same pieniądze**:
111 wierszy `SELF_TRANSFER` po stronie Nest i 30 po stronie Pekao są dwiema stronami tych samych
przelewów. W dwóch cash flow to jest poprawne, ale widok zbiorczy musiałby o tym wiedzieć.

## Zmierzone liczby, które zastępują szacunki z projektu

Dokumenty projektowe szacowały „~38 grup" i „≤7 pytań pokrywa 90 % wartości". Po zmierzeniu na
prawdziwych danych:

| | Nest | Pekao |
|---|---|---|
| grup (klucz: self-transfer / słowo klasyfikacji / merchant+kategoria+kierunek) | **22** | **165** |
| grup pokrywających 90 % wartości | **4** | **35** |

**Wniosek, który trzeba było zmierzyć, żeby go zobaczyć:** dla wyciągu przelewowego (Nest) filtr
istotności jest wręcz hojny — wystarczą **4 pytania**. Dla wyciągu kartowego (Pekao) **90 % wartości
wymaga 35 pytań**, więc `maxQuestions = 7` i `askOnlyTopShareOfValue = 0,9` są **niemożliwe do
spełnienia jednocześnie**: limit pytań wygrywa, a ogon 158 grup musi iść w fallback z flagą
`provisional`. To nie psuje projektu, ale przesuwa ciężar na możliwość poprawy kategorii **po**
imporcie — czyli na `recategorize` w `cashflow-api` (zadanie X1).

## Jakość enrichmentu — zmierzona, nie obiecana

| | Nest | Pekao |
|---|---|---|
| wiersze z wypełnionym `merchant` | 259 / 402 | 645 / 791 |
| wiersze z wypełnionym `location` | 95 | 446 |

Puste `merchant` to w większości przelewy własne i opłaty bankowe, gdzie żadnego merchanta **nie
powinno** być. Co wychodzi dobrze: `BADOO` (94), `ZABKA` (53), `ALLEGRO` (23), `JMP BIEDRONKA` (20),
`UBER` (13), a po stronie Nest `URZAD SKARBOWY` (51), `MINDBOX` (50), `ZUS` (37), `IFIRMA` (35).
Osobno obsłużone są wiersze rozliczeń kartowych, gdzie kontrahentem jest `BANK PEKAO S.A.`, a
prawdziwy merchant siedzi w tytule po słowie `WYKONANEJ:` — i gdzie lokalizacja **celowo nie** jest
brana z adresu banku, bo podstawiłaby Warszawę pod transakcję zrobioną w Dublinie.

Co wychodzi słabiej i zostaje świadomie: długie nazwy organizacji skracane do dwóch tokenów
(`STOWARZYSZENIE POLSKI` zamiast `UNICEF`, które model wyczytałby z tytułu) oraz nazwy terminali
parkingowych, gdzie marką zostaje miasto (`WARSZAWA SPPN`). Dla ingestion to tylko inne grupowanie,
nie błąd danych — a gdy fixture powstanie z modelu, te przypadki są pierwszym miejscem do porównania.

## Odtworzenie

```bash
python3 tools/fixtures/make_canonical_csv.py          # reguły, deterministycznie, bez kosztu
# albo, z kluczem, przez prawdziwy adapter:
export ANTHROPIC_API_KEY="sk-ant-..."
./mvnw test -pl :vidulum-cashflow-tests -Dtest=CanonicalCsvFixtureGenerator
```

Zadanie, z którego to wyszło: **K12** w `docs/features-backlog/ingestion-v2-tasks.md`.
Gdy powstanie `vidulum-ingestion-tests` (**T1**), oba pliki trafiają tam kopią — ten moduł nie
zależy od `vidulum-cashflow-tests` i nie powinien.
