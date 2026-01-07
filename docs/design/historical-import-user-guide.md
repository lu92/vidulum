# Historical Import - User Journey & Technical Guide

## Spis treści

1. [Cel dokumentu](#cel-dokumentu)
2. [Przegląd procesu](#przegląd-procesu)
3. [Kluczowe pola i ich znaczenie](#kluczowe-pola-i-ich-znaczenie)
4. [User Journey z przykładowymi requestami](#user-journey-z-przykładowymi-requestami)
5. [Stany i przejścia](#stany-i-przejścia)
6. [Obsługa błędów](#obsługa-błędów)
7. [Decyzje projektowe](#decyzje-projektowe)
8. [Archiwizacja kategorii](#archiwizacja-kategorii)

---

## Cel dokumentu

Ten dokument opisuje kompletny proces importu historycznych transakcji do CashFlow.
Zawiera:
- Przykładowe requesty API
- Wizualizację zmian stanu agregatów
- Opis możliwych błędów i ich obsługi
- Uzasadnienie podjętych decyzji projektowych

---

## Przegląd procesu

### Diagram wysokopoziomowy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HISTORICAL IMPORT FLOW                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. CREATE           2. IMPORT              3. ATTEST           4. USE     │
│  ───────────────     ──────────────────     ────────────────    ────────── │
│                                                                             │
│  createCashFlow      importHistorical       attestHistorical    appendCash │
│  WithHistory         CashChange (N razy)    Import              Change     │
│                                                                             │
│  Status: SETUP       Status: SETUP          Status: OPEN        Status:    │
│  Months:             Months:                Months:             OPEN       │
│  IMPORT_PENDING      IMPORT_PENDING         IMPORTED                       │
│  ACTIVE              (z transakcjami)       ACTIVE                         │
│  FORECASTED          ACTIVE                 FORECASTED                     │
│                      FORECASTED                                            │
│                                                                             │
│                      [opcjonalnie]                                         │
│                      rollbackImport                                        │
│                      (czyszczenie)                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Kluczowe pola i ich znaczenie

### CashFlow Aggregate

| Pole | Typ | Opis |
|------|-----|------|
| `status` | `CashFlowStatus` | SETUP (import w toku), OPEN (aktywny), CLOSED (zamknięty) |
| `startPeriod` | `YearMonth` | Pierwszy miesiąc historyczny (np. 2021-10) |
| `activePeriod` | `YearMonth` | Bieżący miesiąc ("teraz") |
| `initialBalance` | `Money` | Saldo otwarcia na początku startPeriod |
| `importCutoffDateTime` | `ZonedDateTime` | Znacznik czasowy attestacji - granica między danymi historycznymi a nowymi |

### CashFlowForecastStatement (Read Model)

| Pole | Typ | Opis |
|------|-----|------|
| `forecasts` | `Map<YearMonth, CashFlowMonthlyForecast>` | Mapa miesięcy z prognozami |
| `categoryStructure` | `CurrentCategoryStructure` | Aktualna struktura kategorii |

### CashFlowMonthlyForecast.Status

| Status | Opis | Dozwolone operacje |
|--------|------|-------------------|
| `IMPORT_PENDING` | Miesiąc historyczny oczekujący na import | importHistoricalCashChange |
| `IMPORTED` | Miesiąc historyczny po attestacji | Tylko odczyt |
| `ACTIVE` | Bieżący miesiąc | appendCashChange, confirmCashChange, editCashChange |
| `FORECASTED` | Przyszły miesiąc | appendExpectedCashChange |
| `ATTESTED` | Zamknięty miesiąc (miesięczne rozliczenie) | Tylko odczyt |

---

## User Journey z przykładowymi requestami

### Scenariusz: Import 3 miesięcy historii (Oct-Dec 2021), bieżący miesiąc: Jan 2022

#### Krok 1: Utworzenie CashFlow z historią

**Request:**
```http
POST /api/v1/cash-flow/with-history
Content-Type: application/json

{
  "userId": "user-123",
  "name": "Konto ING",
  "description": "Główne konto osobiste",
  "bankAccount": {
    "bankName": { "name": "ING" },
    "bankAccountNumber": {
      "number": "PL12345678901234567890123456",
      "denomination": { "id": "PLN" }
    },
    "balance": { "amount": 5000, "currency": "PLN" }
  },
  "startPeriod": "2021-10",
  "initialBalance": { "amount": 1000, "currency": "PLN" }
}
```

**Response:**
```json
"cf-abc-123"
```

**Stan po operacji:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlow Aggregate                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ cashFlowId: cf-abc-123                                                      │
│ status: SETUP                                                               │
│ startPeriod: 2021-10                                                        │
│ activePeriod: 2022-01                                                       │
│ initialBalance: 1000 PLN                                                    │
│ importCutoffDateTime: null  ← jeszcze nie ustawione                         │
│ cashChanges: {}  ← puste                                                    │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ forecasts:                                                                  │
│   2021-10: { status: IMPORT_PENDING, inflows: [], outflows: [] }           │
│   2021-11: { status: IMPORT_PENDING, inflows: [], outflows: [] }           │
│   2021-12: { status: IMPORT_PENDING, inflows: [], outflows: [] }           │
│   2022-01: { status: ACTIVE, inflows: [], outflows: [] }                   │
│   2022-02: { status: FORECASTED, inflows: [], outflows: [] }               │
│   ... (kolejne 10 miesięcy FORECASTED)                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Krok 2: Import transakcji historycznych

**Request 1 - Wypłata (October):**
```http
POST /api/v1/cash-flow/cf-abc-123/import-historical
Content-Type: application/json

{
  "category": "Uncategorized",
  "name": "Wypłata",
  "description": "Pensja październik",
  "money": { "amount": 5000, "currency": "PLN" },
  "type": "INFLOW",
  "dueDate": "2021-10-10T12:00:00Z",
  "paidDate": "2021-10-10T12:00:00Z"
}
```

**Response:**
```json
"cc-salary-oct"
```

**Request 2 - Czynsz (November):**
```http
POST /api/v1/cash-flow/cf-abc-123/import-historical
Content-Type: application/json

{
  "category": "Uncategorized",
  "name": "Czynsz",
  "description": "Czynsz za mieszkanie",
  "money": { "amount": 2000, "currency": "PLN" },
  "type": "OUTFLOW",
  "dueDate": "2021-11-05T12:00:00Z",
  "paidDate": "2021-11-05T12:00:00Z"
}
```

**Stan po imporcie 2 transakcji:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlow Aggregate                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ status: SETUP                                                               │
│ cashChanges: {                                                              │
│   cc-salary-oct: { name: "Wypłata", money: 5000 PLN, type: INFLOW }        │
│   cc-rent-nov: { name: "Czynsz", money: 2000 PLN, type: OUTFLOW }          │
│ }                                                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ forecasts:                                                                  │
│   2021-10: {                                                                │
│     status: IMPORT_PENDING,                                                 │
│     inflows: [{ name: "Wypłata", 5000 PLN, PAID }],                        │
│     outflows: []                                                            │
│   }                                                                         │
│   2021-11: {                                                                │
│     status: IMPORT_PENDING,                                                 │
│     inflows: [],                                                            │
│     outflows: [{ name: "Czynsz", 2000 PLN, PAID }]                         │
│   }                                                                         │
│   2021-12: { status: IMPORT_PENDING, ... }                                 │
│   2022-01: { status: ACTIVE, ... }                                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Kalkulacja salda:**
```
initialBalance:     1,000 PLN
+ Wypłata Oct:    + 5,000 PLN
- Czynsz Nov:     - 2,000 PLN
────────────────────────────
calculatedBalance:  4,000 PLN
```

---

#### Krok 3: Attestacja importu

##### Scenariusz A: Saldo się zgadza

**Request:**
```http
POST /api/v1/cash-flow/cf-abc-123/attest-historical-import
Content-Type: application/json

{
  "confirmedBalance": { "amount": 4000, "currency": "PLN" },
  "forceAttestation": false,
  "createAdjustment": false
}
```

**Response:**
```json
{
  "cashFlowId": "cf-abc-123",
  "confirmedBalance": { "amount": 4000, "currency": "PLN" },
  "calculatedBalance": { "amount": 4000, "currency": "PLN" },
  "difference": { "amount": 0, "currency": "PLN" },
  "forced": false,
  "adjustmentCreated": false,
  "adjustmentCashChangeId": null,
  "status": "OPEN"
}
```

##### Scenariusz B: Saldo się nie zgadza - użyj createAdjustment

Załóżmy, że użytkownik potwierdza saldo 4500 PLN (różnica +500 PLN).

**Request:**
```http
POST /api/v1/cash-flow/cf-abc-123/attest-historical-import
Content-Type: application/json

{
  "confirmedBalance": { "amount": 4500, "currency": "PLN" },
  "forceAttestation": false,
  "createAdjustment": true
}
```

**Response:**
```json
{
  "cashFlowId": "cf-abc-123",
  "confirmedBalance": { "amount": 4500, "currency": "PLN" },
  "calculatedBalance": { "amount": 4000, "currency": "PLN" },
  "difference": { "amount": 500, "currency": "PLN" },
  "forced": false,
  "adjustmentCreated": true,
  "adjustmentCashChangeId": "cc-adjustment-xyz",
  "status": "OPEN"
}
```

**Stan po attestacji z adjustment:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlow Aggregate                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ status: OPEN  ← zmiana z SETUP                                              │
│ importCutoffDateTime: 2022-01-15T10:30:00Z  ← ustawione!                   │
│ bankAccount.balance: 4500 PLN  ← potwierdzone saldo                        │
│ cashChanges: {                                                              │
│   cc-salary-oct: { ... },                                                   │
│   cc-rent-nov: { ... },                                                     │
│   cc-adjustment-xyz: {  ← NOWA transakcja korekty                          │
│     name: "Balance Adjustment",                                             │
│     money: 500 PLN,                                                         │
│     type: INFLOW,  ← dodatnia różnica = INFLOW                             │
│     status: CONFIRMED                                                       │
│   }                                                                         │
│ }                                                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ forecasts:                                                                  │
│   2021-10: { status: IMPORTED ← zmiana z IMPORT_PENDING }                  │
│   2021-11: { status: IMPORTED }                                            │
│   2021-12: { status: IMPORTED }                                            │
│   2022-01: {                                                                │
│     status: ACTIVE,                                                         │
│     inflows: [{                                                             │
│       category: "Uncategorized",                                            │
│       transactions: [{ name: "Balance Adjustment", 500 PLN, PAID }]        │
│     }]  ← korekta dodana do ACTIVE miesiąca                                │
│   }                                                                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

##### Scenariusz C: Negatywna różnica salda

Jeśli confirmedBalance < calculatedBalance (np. 3500 PLN vs 4000 PLN):

```json
{
  "difference": { "amount": -500, "currency": "PLN" },
  "adjustmentCreated": true,
  "adjustmentCashChangeId": "cc-adjustment-xyz"
}
```

Korekta zostanie utworzona jako **OUTFLOW** o wartości 500 PLN.

---

#### Krok 4: Rollback (opcjonalny)

Jeśli użytkownik chce zresetować import przed attestacją:

**Request:**
```http
POST /api/v1/cash-flow/cf-abc-123/rollback-import
Content-Type: application/json

{
  "deleteCategories": false
}
```

**Response:**
```json
{
  "cashFlowId": "cf-abc-123",
  "deletedTransactionsCount": 2,
  "deletedCategoriesCount": 0,
  "categoriesDeleted": false,
  "status": "SETUP"
}
```

**Stan po rollback:**
- Wszystkie cashChanges usunięte
- Status pozostaje SETUP
- Miesiące wracają do pustego stanu IMPORT_PENDING
- Można importować od nowa

---

## Stany i przejścia

### CashFlow Status Transitions

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│                    CashFlow Status State Machine                           │
│                                                                            │
│  ┌──────────┐    attestHistoricalImport    ┌──────────┐                   │
│  │  SETUP   │ ─────────────────────────────▶│   OPEN   │                   │
│  └──────────┘                               └──────────┘                   │
│       │                                          │                         │
│       │ createCashFlow                           │ closeCashFlow          │
│       │ WithHistory                              │ (future)               │
│       │                                          ▼                         │
│       │                                    ┌──────────┐                   │
│       │                                    │  CLOSED  │                   │
│       │                                    └──────────┘                   │
│       │                                                                    │
│       │ rollbackImport                                                    │
│       └───────────────┐                                                   │
│                       │ (pozostaje w SETUP,                               │
│                       ▼  tylko czyści dane)                               │
│                  ┌──────────┐                                             │
│                  │  SETUP   │                                             │
│                  └──────────┘                                             │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

### Monthly Forecast Status Transitions

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│              Monthly Forecast Status State Machine                         │
│                                                                            │
│   HISTORICAL IMPORT FLOW:                                                  │
│   ───────────────────────                                                  │
│                                                                            │
│   ┌────────────────┐      attestHistoricalImport     ┌──────────┐         │
│   │ IMPORT_PENDING │ ───────────────────────────────▶│ IMPORTED │         │
│   └────────────────┘                                 └──────────┘         │
│                                                                            │
│                                                                            │
│   NORMAL MONTHLY FLOW:                                                     │
│   ────────────────────                                                     │
│                                                                            │
│   ┌────────────┐    (calendar)    ┌────────┐    attestMonth   ┌──────────┐│
│   │ FORECASTED │ ────────────────▶│ ACTIVE │ ────────────────▶│ ATTESTED ││
│   └────────────┘                  └────────┘                  └──────────┘│
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Obsługa błędów

### Możliwe wyjątki podczas importu

| Wyjątek | Kiedy | HTTP Status | Rozwiązanie |
|---------|-------|-------------|-------------|
| `OperationNotAllowedInSetupModeException` | Próba appendCashChange w SETUP | 400 | Użyj importHistoricalCashChange |
| `ImportNotAllowedInNonSetupModeException` | Próba importu gdy status != SETUP | 400 | Można importować tylko w SETUP |
| `ImportDateOutsideSetupPeriodException` | paidDate >= activePeriod | 400 | paidDate musi być przed bieżącym miesiącem |
| `ImportDateBeforeStartPeriodException` | paidDate < startPeriod | 400 | paidDate musi być >= startPeriod |
| `ImportDateInFutureException` | paidDate > now() | 400 | paidDate nie może być w przyszłości |
| `AttestationNotAllowedInNonSetupModeException` | Attestacja gdy status != SETUP | 400 | Można attestować tylko w SETUP |
| `BalanceMismatchException` | Różnica salda bez forceAttestation i createAdjustment | 400 | Użyj forceAttestation=true lub createAdjustment=true |

### Przykład błędu

**Request z błędną datą:**
```http
POST /api/v1/cash-flow/cf-abc-123/import-historical
{
  "paidDate": "2022-02-15T12:00:00Z",  ← data w przyszłości!
  ...
}
```

**Response:**
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Import date [2022-02-15] is in the future. Current time: [2022-01-15T10:30:00Z]",
  "instance": "/api/v1/cash-flow/cf-abc-123/import-historical"
}
```

---

## Decyzje projektowe

### Dlaczego `importCutoffDateTime`?

**Problem:** Jak odróżnić dane zaimportowane z banku od ręcznie dodanych transakcji?

**Rozwiązanie:** Zapisujemy timestamp attestacji jako granicę.

**Korzyści:**
1. Łatwe filtrowanie: `transaction.created < importCutoffDateTime` = import
2. Możliwość różnych reguł walidacji dla danych historycznych
3. Audit trail - wiemy dokładnie kiedy zakończono import

**Alternatywy rozważane:**
- Flaga `isImported` na każdej transakcji → większy overhead, trudniejsze zapytania
- Osobna kolekcja dla importów → duplikacja danych, skomplikowana synchronizacja

### Dlaczego `createAdjustment` zamiast wymuszenia?

**Problem:** Co zrobić gdy saldo się nie zgadza?

**Opcje:**
1. `forceAttestation=true` - akceptujemy różnicę, ignorujemy
2. `createAdjustment=true` - tworzymy transakcję korygującą

**Dlaczego oba?**
- `forceAttestation` - dla przypadków gdy różnica jest akceptowalna (np. zaokrąglenia)
- `createAdjustment` - dla przypadków gdy chcemy śledzić różnicę jako "brakującą" transakcję

**Korzyści `createAdjustment`:**
- Zachowana pełna historia
- Raporty są spójne
- Użytkownik widzi skąd bierze się różnica

### Dlaczego korekta trafia do ACTIVE miesiąca?

**Problem:** Gdzie umieścić transakcję korekty?

**Rozważane opcje:**
1. Ostatni miesiąc IMPORT_PENDING → już zamknięty, nie powinniśmy modyfikować
2. Osobny "miesiąc korekt" → skomplikowana struktura
3. ACTIVE miesiąc → naturalnie widoczne w bieżącym bilansie

**Decyzja:** ACTIVE miesiąc

**Uzasadnienie:**
- Korekta wpływa na bieżące saldo (które właśnie potwierdzamy)
- Użytkownik widzi ją od razu w bieżącym miesiącu
- Prosta implementacja

### Dlaczego status IMPORTED zamiast zostawienia IMPORT_PENDING?

**Problem:** Jak oznaczyć miesiące po attestacji?

**Alternatywy:**
1. Zostawić IMPORT_PENDING → nie wiadomo czy attestowano
2. Nowy status IMPORTED → jasne rozróżnienie

**Decyzja:** Nowy status IMPORTED

**Uzasadnienie:**
- Wyraźna semantyka: IMPORT_PENDING = czeka na dane, IMPORTED = dane zaimportowane
- Możliwość różnych reguł dla obu stanów
- Lepszy UX - użytkownik widzi postęp

---

## Archiwizacja kategorii

### Przegląd

Kategorie mogą być archiwizowane, aby ukryć je przed nowymi transakcjami, zachowując jednocześnie pełną historię.

### Model danych kategorii

| Pole | Typ | Opis |
|------|-----|------|
| `categoryName` | `CategoryName` | Nazwa kategorii |
| `origin` | `CategoryOrigin` | Pochodzenie: SYSTEM, IMPORTED, USER_CREATED |
| `archived` | `boolean` | Czy kategoria jest zarchiwizowana |
| `validFrom` | `ZonedDateTime` | Data początkowa ważności (nullable = od zawsze) |
| `validTo` | `ZonedDateTime` | Data końcowa ważności (ustawiana przy archiwizacji) |
| `isModifiable` | `boolean` | Czy kategoria może być modyfikowana |

### CategoryOrigin

| Wartość | Opis | Możliwe operacje |
|---------|------|------------------|
| `SYSTEM` | Kategoria systemowa (np. "Uncategorized") | Tylko odczyt - nie można archiwizować ani usuwać |
| `IMPORTED` | Kategoria zaimportowana z wyciągu bankowego | Archiwizacja, zmiana nazwy |
| `USER_CREATED` | Kategoria stworzona ręcznie przez użytkownika | Pełna kontrola - archiwizacja, zmiana nazwy, usunięcie |

### Cykl życia kategorii

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CATEGORY LIFECYCLE                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌────────────┐                                    ┌────────────────┐       │
│  │  Created   │  ────── archiveCategory ─────────▶ │   Archived     │       │
│  │            │                                    │                │       │
│  │ archived:  │                                    │ archived: true │       │
│  │   false    │  ◀──── unarchiveCategory ───────── │ validTo: now() │       │
│  │ validTo:   │                                    │                │       │
│  │   null     │                                    │                │       │
│  └────────────┘                                    └────────────────┘       │
│                                                                             │
│  Kategoria aktywna:                              Kategoria zarchiwizowana:  │
│  - Widoczna w selekcji                          - Ukryta w selekcji        │
│  - Możliwa do użycia                            - Niedostępna dla nowych   │
│    w nowych transakcjach                          transakcji               │
│                                                 - Widoczna w historii      │
│                                                 - Można przywrócić         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Przykłady API

#### Tworzenie kategorii użytkownika

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category
Content-Type: application/json

{
  "category": "Groceries",
  "type": "OUTFLOW"
}
```

Nowa kategoria ma domyślnie:
- `origin: USER_CREATED`
- `archived: false`
- `validFrom: null` (ważna od zawsze)
- `validTo: null` (ważna bezterminowo)

#### Archiwizacja kategorii

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/archive
Content-Type: application/json

{
  "categoryName": "Groceries",
  "categoryType": "OUTFLOW"
}
```

Po archiwizacji:
- `archived: true`
- `validTo: <timestamp archiwizacji>`

#### Próba archiwizacji kategorii systemowej

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/archive
Content-Type: application/json

{
  "categoryName": "Uncategorized",
  "categoryType": "OUTFLOW"
}
```

**Response (400 Bad Request):**
```json
{
  "type": "CannotArchiveSystemCategoryException",
  "message": "Cannot archive system category: Uncategorized"
}
```

#### Przywracanie kategorii (unarchive)

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/unarchive
Content-Type: application/json

{
  "categoryName": "Groceries",
  "categoryType": "OUTFLOW"
}
```

Po przywróceniu:
- `archived: false`
- `validTo: null`

### Zachowanie historycznych transakcji

Zarchiwizowane kategorie pozostają widoczne w transakcjach, które ich używają:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Transakcja z zarchiwizowaną kategorią                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ cashChangeId: cc-123                                                        │
│ name: "Weekly groceries"                                                    │
│ categoryName: "Groceries"  ← kategoria zarchiwizowana, ale nadal widoczna  │
│ money: 150 PLN                                                              │
│ type: OUTFLOW                                                               │
│ status: CONFIRMED                                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ Lista kategorii OUTFLOW                                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│ - Uncategorized (origin: SYSTEM, archived: false)                           │
│ - Groceries (origin: USER_CREATED, archived: true)  ← widoczna, ale ukryta │
│   w selekcji przy tworzeniu nowych transakcji                               │
│ - Rent (origin: USER_CREATED, archived: false)                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Filtrowanie kategorii w UI

**Kategorie do wyboru (nowe transakcje):**
```java
categories.stream()
    .filter(c -> !c.isArchived())
    .collect(toList());
```

**Wszystkie kategorie (raporty, historia):**
```java
categories  // wszystkie, włącznie z zarchiwizowanymi
```

### Walidacja dat (validFrom/validTo)

Metoda `isValidAt(ZonedDateTime date)` sprawdza czy kategoria jest ważna dla danej daty:

```java
public boolean isValidAt(ZonedDateTime date) {
    if (archived) return false;
    if (validFrom != null && date.isBefore(validFrom)) return false;
    if (validTo != null && date.isAfter(validTo)) return false;
    return true;
}
```

**Przypadki użycia:**
- Filtrowanie kategorii dostępnych dla konkretnej daty transakcji
- Walidacja podczas edycji starych transakcji
- Raporty uwzględniające historyczne struktury kategorii

### Procesowanie eventów w CashFlowForecastProcessor

Zdarzenia archiwizacji kategorii są obsługiwane przez dedykowane handlery:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Event Flow dla archiwizacji kategorii                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  CashFlow.apply(CategoryArchivedEvent)                                      │
│            ↓                                                                │
│  CashFlowEventEmitter → Kafka (cash_flow topic)                            │
│            ↓                                                                │
│  CashFlowEventListener → CashFlowForecastProcessor                         │
│            ↓                                                                │
│  CategoryArchivedEventHandler.handle()                                      │
│     │                                                                       │
│     ├─→ Update CategoryNode in categoryStructure                           │
│     │      - archived = true                                               │
│     │      - validTo = archivedAt                                          │
│     │                                                                       │
│     └─→ Update CashCategory in all monthly forecasts                       │
│            - archived = true                                               │
│            - validTo = archivedAt                                          │
│            ↓                                                                │
│  CashFlowForecastStatementRepository.save()                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### JSON Response - kategorie z metadanymi archiwizacji

```json
{
  "categoryStructure": {
    "inflowCategoryStructure": [
      {
        "categoryName": "Salary",
        "nodes": [],
        "archived": false,
        "validFrom": null,
        "validTo": null,
        "origin": "USER_CREATED"
      },
      {
        "categoryName": "Royalties",
        "nodes": [],
        "archived": true,
        "validFrom": null,
        "validTo": "2024-06-15T10:30:00Z",
        "origin": "IMPORTED"
      }
    ],
    "outflowCategoryStructure": [
      {
        "categoryName": "Uncategorized",
        "nodes": [],
        "archived": false,
        "validFrom": null,
        "validTo": null,
        "origin": "SYSTEM"
      },
      {
        "categoryName": "Netflix",
        "nodes": [],
        "archived": true,
        "validFrom": "2023-01-01T00:00:00Z",
        "validTo": "2024-03-01T12:00:00Z",
        "origin": "USER_CREATED"
      }
    ]
  }
}
```

### Implementacja UI - toggle dla zarchiwizowanych kategorii

```typescript
// Komponent React - przykład
interface CategoryDropdownProps {
  categories: CashCategory[];
  showArchived: boolean;
  onToggleArchived: () => void;
}

const CategoryDropdown = ({ categories, showArchived, onToggleArchived }) => {
  const filteredCategories = showArchived
    ? categories
    : categories.filter(c => !c.archived);

  return (
    <div>
      <label>
        <input
          type="checkbox"
          checked={showArchived}
          onChange={onToggleArchived}
        />
        Pokaż zarchiwizowane
      </label>

      <select>
        {filteredCategories.map(category => (
          <option
            key={category.categoryName}
            disabled={category.archived}
            className={category.archived ? 'archived' : ''}
          >
            {category.categoryName}
            {category.archived && ' (zarchiwizowana)'}
          </option>
        ))}
      </select>
    </div>
  );
};
```

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2026-01-07 | Utworzenie dokumentu |
| 2026-01-07 | Dodanie sekcji o createAdjustment i importCutoffDateTime |
| 2026-01-07 | Dodanie sekcji o archiwizacji kategorii (VID-92) |
| 2026-01-07 | Dodanie procesowania eventów archiwizacji w ForecastProcessor |
