# Recurring Rules API Guide

Przewodnik po API do zarządzania regułami cyklicznymi (Recurring Rules) dla integracji z UI.

## Spis treści

1. [Przegląd](#przegląd)
2. [Wymagania wstępne](#wymagania-wstępne)
3. [Tworzenie reguły](#tworzenie-reguły)
4. [Typy wzorców (Patterns)](#typy-wzorców-patterns)
5. [Operacje na regułach](#operacje-na-regułach)
6. [Wpływ na CashFlow](#wpływ-na-cashflow)
7. [Stany reguły](#stany-reguły)
8. [Obsługa błędów](#obsługa-błędów)
9. [Przykłady dla UI](#przykłady-dla-ui)

---

## Przegląd

Recurring Rules pozwalają definiować cykliczne transakcje (np. wypłata, czynsz, subskrypcje), które automatycznie generują expected cash changes w CashFlow na 12 miesięcy do przodu.

**Kluczowe cechy:**
- Automatyczne generowanie expected cash changes
- Obsługa różnych wzorców: dzienne, tygodniowe, miesięczne, roczne
- Możliwość pauzowania i wznawiania
- Powiązanie z kategoriami CashFlow

---

## Wymagania wstępne

Przed utworzeniem Recurring Rule:

1. **CashFlow musi być AKTYWNY** (status: `OPEN`)
   - Nie można tworzyć reguł dla CashFlow w statusie `SETUP`

2. **Kategoria musi istnieć** w CashFlow
   - Dla INFLOW: kategoria musi być w `inflowCategories`
   - Dla OUTFLOW: kategoria musi być w `outflowCategories`

3. **Token JWT** wymagany w nagłówku `Authorization: Bearer {token}`

---

## Tworzenie reguły

### Endpoint

```
POST /api/v1/recurring-rules
```

### Request Body

```json
{
  "userId": "U10000001",
  "cashFlowId": "CF10000001",
  "name": "Miesięczna wypłata",
  "description": "Wypłata z pracy na etacie",
  "baseAmount": {
    "amount": 8000.00,
    "currency": "PLN"
  },
  "category": "Wynagrodzenie",
  "pattern": {
    "type": "MONTHLY",
    "dayOfMonth": 5,
    "intervalMonths": 1,
    "adjustForMonthEnd": false
  },
  "startDate": "2026-01-01",
  "endDate": "2026-12-31"
}
```

### Pola

| Pole | Typ | Wymagane | Opis |
|------|-----|----------|------|
| `userId` | string | Tak | ID użytkownika |
| `cashFlowId` | string | Tak | ID CashFlow |
| `name` | string | Tak | Nazwa reguły (wyświetlana w UI) |
| `description` | string | Nie | Opis reguły |
| `baseAmount` | Money | Tak | Kwota transakcji |
| `category` | string | Tak | Nazwa kategorii (musi istnieć w CashFlow) |
| `pattern` | Pattern | Tak | Wzorzec powtarzania |
| `startDate` | date | Tak | Data rozpoczęcia (YYYY-MM-DD) |
| `endDate` | date | Nie | Data zakończenia (null = bez końca) |

### Kwota (baseAmount)

```json
// INFLOW (przychód) - kwota dodatnia
"baseAmount": {"amount": 8000.00, "currency": "PLN"}

// OUTFLOW (wydatek) - kwota ujemna
"baseAmount": {"amount": -2500.00, "currency": "PLN"}
```

**Znak kwoty określa typ transakcji:**
- `amount > 0` → INFLOW (przychód)
- `amount < 0` → OUTFLOW (wydatek)

### Response

```json
{
  "ruleId": "RR00000001"
}
```

**HTTP Status:** `201 Created`

---

## Typy wzorców (Patterns)

### DAILY - Codziennie

```json
{
  "type": "DAILY",
  "intervalDays": 1
}
```

| Pole | Opis |
|------|------|
| `intervalDays` | Co ile dni (1 = codziennie, 2 = co drugi dzień) |

**Przykład:** Codzienna kawa
```json
{
  "name": "Poranna kawa",
  "baseAmount": {"amount": -15.00, "currency": "PLN"},
  "pattern": {"type": "DAILY", "intervalDays": 1}
}
```

---

### WEEKLY - Co tydzień

```json
{
  "type": "WEEKLY",
  "dayOfWeek": "FRIDAY",
  "intervalWeeks": 1
}
```

| Pole | Opis |
|------|------|
| `dayOfWeek` | Dzień tygodnia: `MONDAY`, `TUESDAY`, `WEDNESDAY`, `THURSDAY`, `FRIDAY`, `SATURDAY`, `SUNDAY` |
| `intervalWeeks` | Co ile tygodni (1 = co tydzień, 2 = co dwa tygodnie) |

**Przykład:** Cotygodniowe zakupy w sobotę
```json
{
  "name": "Zakupy spożywcze",
  "baseAmount": {"amount": -400.00, "currency": "PLN"},
  "pattern": {"type": "WEEKLY", "dayOfWeek": "SATURDAY", "intervalWeeks": 1}
}
```

---

### MONTHLY - Co miesiąc

```json
{
  "type": "MONTHLY",
  "dayOfMonth": 10,
  "intervalMonths": 1,
  "adjustForMonthEnd": false
}
```

| Pole | Opis |
|------|------|
| `dayOfMonth` | Dzień miesiąca (1-31) |
| `intervalMonths` | Co ile miesięcy (1 = co miesiąc, 3 = kwartalnie) |
| `adjustForMonthEnd` | Czy dostosować dla krótszych miesięcy (true/false) |

**Przykład:** Czynsz 10. każdego miesiąca
```json
{
  "name": "Czynsz",
  "baseAmount": {"amount": -2500.00, "currency": "PLN"},
  "pattern": {"type": "MONTHLY", "dayOfMonth": 10, "intervalMonths": 1, "adjustForMonthEnd": false}
}
```

**adjustForMonthEnd:**
- `false`: Dla `dayOfMonth: 31` w lutym → brak transakcji
- `true`: Dla `dayOfMonth: 31` w lutym → ostatni dzień miesiąca (28/29)

---

### YEARLY - Co rok

```json
{
  "type": "YEARLY",
  "month": 12,
  "yearlyDayOfMonth": 25
}
```

| Pole | Opis |
|------|------|
| `month` | Miesiąc (1-12) |
| `yearlyDayOfMonth` | Dzień miesiąca (1-31) |

**Przykład:** Ubezpieczenie samochodu (1 marca)
```json
{
  "name": "Ubezpieczenie OC/AC",
  "baseAmount": {"amount": -2400.00, "currency": "PLN"},
  "pattern": {"type": "YEARLY", "month": 3, "yearlyDayOfMonth": 1}
}
```

---

## Operacje na regułach

### Pobierz regułę

```
GET /api/v1/recurring-rules/{ruleId}
```

**Response:**
```json
{
  "ruleId": "RR00000001",
  "userId": "U10000001",
  "cashFlowId": "CF10000001",
  "name": "Miesięczna wypłata",
  "description": "Wypłata z pracy",
  "baseAmount": {"amount": 8000.0, "currency": "PLN"},
  "category": "Wynagrodzenie",
  "pattern": {
    "type": "MONTHLY",
    "dayOfMonth": 5,
    "intervalMonths": 1
  },
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "status": "ACTIVE",
  "generatedCashChangeIds": ["CC1000000001", "CC1000000002", ...],
  "pauseInfo": null,
  "createdAt": "2026-02-26T15:00:00Z",
  "updatedAt": "2026-02-26T15:00:00Z"
}
```

---

### Pobierz reguły dla CashFlow

```
GET /api/v1/recurring-rules/cash-flow/{cashFlowId}
```

**Response:** Lista reguł (jak wyżej)

---

### Pobierz moje reguły

```
GET /api/v1/recurring-rules/me
```

Zwraca wszystkie reguły zalogowanego użytkownika.

---

### Aktualizuj regułę

```
PUT /api/v1/recurring-rules/{ruleId}
```

**Request Body:**
```json
{
  "name": "Zaktualizowana nazwa",
  "description": "Nowy opis",
  "baseAmount": {"amount": -500.00, "currency": "PLN"},
  "category": "Utilities",
  "pattern": {
    "type": "WEEKLY",
    "dayOfWeek": "FRIDAY",
    "intervalWeeks": 1
  },
  "startDate": "2026-02-01",
  "endDate": "2026-08-31"
}
```

**Efekt:**
- Stare expected cash changes są usuwane
- Nowe są generowane według zaktualizowanego wzorca

---

### Pauzuj regułę

```
POST /api/v1/recurring-rules/{ruleId}/pause
```

**Request Body:**
```json
{
  "resumeDate": "2026-05-01",
  "reason": "Urlop bezpłatny"
}
```

| Pole | Wymagane | Opis |
|------|----------|------|
| `resumeDate` | Nie | Data wznowienia (null = pauza bezterminowa) |
| `reason` | Nie | Powód pauzy |

**Efekt:** Status zmienia się na `PAUSED`, expected cash changes pozostają.

---

### Wznów regułę

```
POST /api/v1/recurring-rules/{ruleId}/resume
```

**Brak body.**

**Efekt:** Status zmienia się na `ACTIVE`, expected cash changes są regenerowane.

---

### Usuń regułę

```
DELETE /api/v1/recurring-rules/{ruleId}
```

**Request Body (opcjonalnie):**
```json
{
  "reason": "Koniec umowy"
}
```

**Efekt:**
- Status zmienia się na `DELETED` (soft delete)
- Powiązane expected cash changes są usuwane z CashFlow

---

### Regeneruj expected cash changes

```
POST /api/v1/recurring-rules/{ruleId}/regenerate
```

**Brak body.**

**Kiedy używać:**
- Po ręcznej modyfikacji cash changes
- Do odświeżenia prognoz

---

## Wpływ na CashFlow

### Co się dzieje po utworzeniu reguły?

1. **Walidacja:**
   - Sprawdzenie czy CashFlow istnieje i jest AKTYWNY
   - Sprawdzenie czy kategoria istnieje

2. **Generowanie expected cash changes:**
   - System generuje transakcje na **12 miesięcy do przodu**
   - Każda transakcja ma status `PENDING`
   - Transakcje są przypisane do odpowiednich miesięcy

3. **Śledzenie:**
   - `generatedCashChangeIds` zawiera listę wygenerowanych ID
   - Można je zobaczyć w CashFlow w polu `cashChanges`

### Przykład

Reguła: Wypłata 5. każdego miesiąca, 8000 PLN

**Wygenerowane cash changes (luty-grudzień 2026):**
```
CC1000000001: 2026-03-05 | Monthly Salary | +8000 PLN | PENDING
CC1000000002: 2026-04-05 | Monthly Salary | +8000 PLN | PENDING
CC1000000003: 2026-05-05 | Monthly Salary | +8000 PLN | PENDING
... (10 transakcji)
```

### Widok w UI

W widoku CashFlow użytkownik widzi:
- **Obecny miesiąc:** Aktualne transakcje + expected z reguł
- **Przyszłe miesiące:** Prognoza oparta na regułach
- **Kategorie:** Sumy uwzględniające expected cash changes

---

## Stany reguły

```
     ┌─────────┐
     │ ACTIVE  │ ←────────────────┐
     └────┬────┘                  │
          │                       │
    pause │               resume  │
          ▼                       │
     ┌─────────┐                  │
     │ PAUSED  │ ─────────────────┘
     └────┬────┘
          │
   delete │
          ▼
     ┌─────────┐
     │ DELETED │
     └─────────┘
```

| Status | Opis | Generuje cash changes? |
|--------|------|------------------------|
| `ACTIVE` | Reguła aktywna | Tak |
| `PAUSED` | Reguła wstrzymana | Nie |
| `DELETED` | Reguła usunięta (soft delete) | Nie |

---

## Obsługa błędów

### 400 Bad Request

**CATEGORY_NOT_FOUND:**
```json
{
  "error": "CATEGORY_NOT_FOUND",
  "message": "Category [Nieistniejąca] not found in CashFlow [CF10000001]",
  "cashFlowId": "CF10000001",
  "category": "Nieistniejąca"
}
```
→ Utwórz kategorię przed utworzeniem reguły

**INVALID_DATE_RANGE:**
```json
{
  "error": "INVALID_DATE_RANGE",
  "message": "Start date must be before end date",
  "startDate": "2026-12-31",
  "endDate": "2026-01-01"
}
```

### 404 Not Found

**RULE_NOT_FOUND:**
```json
{
  "error": "RULE_NOT_FOUND",
  "message": "Recurring rule [RR99999999] not found",
  "ruleId": "RR99999999"
}
```

**CASHFLOW_NOT_FOUND:**
```json
{
  "error": "CASHFLOW_NOT_FOUND",
  "message": "CashFlow [CF99999999] not found",
  "cashFlowId": "CF99999999"
}
```

### 409 Conflict

**INVALID_RULE_STATE:**
```json
{
  "error": "INVALID_RULE_STATE",
  "message": "Cannot pause rule [RR00000001] in status PAUSED",
  "ruleId": "RR00000001",
  "currentStatus": "PAUSED",
  "operation": "pause"
}
```

---

## Przykłady dla UI

### Formularz tworzenia reguły

```
┌─────────────────────────────────────────────────────────────┐
│  Nowa reguła cykliczna                                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Nazwa:        [Czynsz za mieszkanie_____________]          │
│                                                             │
│  Opis:         [Opłata do spółdzielni____________]          │
│                                                             │
│  Kwota:        [-2500.00] [PLN ▼]                           │
│                                                             │
│  Kategoria:    [Mieszkanie ▼]                               │
│                                                             │
│  ─── Wzorzec powtarzania ───                                │
│                                                             │
│  Typ:          (•) Miesięcznie  ( ) Tygodniowo              │
│                ( ) Codziennie   ( ) Rocznie                 │
│                                                             │
│  Dzień miesiąca: [10 ▼]                                     │
│                                                             │
│  ─── Okres obowiązywania ───                                │
│                                                             │
│  Od:           [2026-03-01]                                 │
│  Do:           [2026-12-31] ☐ Bez daty końcowej             │
│                                                             │
│                           [Anuluj]  [Utwórz regułę]         │
└─────────────────────────────────────────────────────────────┘
```

### Lista reguł

```
┌─────────────────────────────────────────────────────────────┐
│  Reguły cykliczne                           [+ Nowa reguła] │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ 💰 Wypłata                              ● ACTIVE    │    │
│  │ +8,000.00 PLN | Miesięcznie, 5. dnia               │    │
│  │ Kategoria: Wynagrodzenie                           │    │
│  │                        [Edytuj] [Pauzuj] [Usuń]    │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ 🏠 Czynsz                               ● ACTIVE    │    │
│  │ -2,500.00 PLN | Miesięcznie, 10. dnia              │    │
│  │ Kategoria: Mieszkanie                              │    │
│  │                        [Edytuj] [Pauzuj] [Usuń]    │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ 🛒 Zakupy tygodniowe                    ⏸ PAUSED    │    │
│  │ -400.00 PLN | Co tydzień, sobota                   │    │
│  │ Wznowienie: 2026-05-01                             │    │
│  │                        [Edytuj] [Wznów] [Usuń]     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Widok kalendarza z expected cash changes

```
┌─────────────────────────────────────────────────────────────┐
│  Marzec 2026                                    [< Luty]    │
├─────────────────────────────────────────────────────────────┤
│  Pn   Wt   Śr   Cz   Pt   So   Nd                          │
│                                                             │
│                          1                                  │
│   2    3    4   [5]   6    7    8                          │
│                 └─ +8000 Wypłata                            │
│   9   [10]  11   12   13  [14]  15                         │
│        └─ -2500 Czynsz    └─ -400 Zakupy                   │
│  16   17   18   19   20  [21]  22                          │
│                           └─ -400 Zakupy                   │
│  23   24   25   26   27  [28]  29                          │
│                           └─ -400 Zakupy                   │
│  30   31                                                    │
│                                                             │
│  ─────────────────────────────────────────                  │
│  Prognoza:  +8000  -2500  -1200 = +4300 PLN                │
└─────────────────────────────────────────────────────────────┘
```

---

## Uwagi implementacyjne dla UI

1. **Walidacja przed wysłaniem:**
   - Sprawdź czy `startDate <= endDate`
   - Sprawdź czy wybrana kategoria istnieje
   - Upewnij się że kwota ma odpowiedni znak (+/-)

2. **Odświeżanie danych:**
   - Po utworzeniu/edycji reguły odśwież listę cash changes
   - Expected cash changes pojawiają się asynchronicznie (Kafka)

3. **UX dla pauzowania:**
   - Pokaż datę wznowienia jeśli ustawiona
   - Rozważ dialog z kalendarzem do wyboru daty

4. **Obsługa błędów:**
   - Wyświetl przyjazne komunikaty z `message` z response
   - Dla 409 Conflict pokaż aktualny stan reguły

5. **Soft delete:**
   - Usunięte reguły mają status `DELETED`
   - Możesz je ukryć lub pokazać w osobnej sekcji "Historia"
