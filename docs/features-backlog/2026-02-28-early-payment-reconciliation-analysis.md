# Early Payment Reconciliation - Analiza biznesowa

**Data:** 2026-02-28
**Status:** ANALIZA
**Kontekst:** VID-131, dyskusja o PAID transakcjach w przyszłych miesiącach

---

## Problem

Użytkownik zauważył, że w CashFlow "home budget" widoczne są PAID transakcje w miesiącach późniejszych niż `activePeriod`. Transakcje były dodawane ręcznie.

### Pytanie biznesowe

> "Mam EXPECTED inflow na maj, ale ktoś zapłacił w marcu. Albo mam EXPECTED outflow na ubezpieczenie za 2 miesiące, ale wykupiłem polisę teraz. Czy system powinien przenieść transakcję z przyszłości do bieżącego miesiąca?"

---

## Obecne zachowanie systemu

```
activePeriod = 2026-03 (marzec)

1. User dodaje EXPECTED inflow na maj (dueDate = 2026-05-15)
   → CashChange: status=PENDING, dueDate=2026-05-15, przypisany do miesiąca 2026-05

2. User potwierdza transakcję (confirm) z endDate = 2026-03-10
   → CashChange: status=CONFIRMED, endDate=2026-03-10

   ALE: transakcja pozostaje w miesiącu 2026-05! (na podstawie dueDate)
```

**Problem**: Transakcja jest CONFIRMED w maju, mimo że faktycznie została opłacona w marcu. To nie odzwierciedla rzeczywistego przepływu gotówki.

---

## Analiza opcji

### Opcja A: Transakcja "podąża za płatnością"

```
EXPECTED w maju → płatność w marcu → transakcja PRZENIESIONA do marca jako PAID
```

| Plusy | Minusy |
|-------|--------|
| Odzwierciedla rzeczywisty przepływ gotówki | Maj traci informację "miałem planowany wpływ" |
| Marzec pokazuje faktyczne wydatki/wpływy | Prognoza na maj się nie aktualizuje automatycznie |
| Maj jest "czysty" - bez artefaktów | Trudniejsze śledzenie "co poszło zgodnie z planem" |

### Opcja B: Dwa wpisy - rozliczenie vs plan

```
EXPECTED w maju → płatność w marcu →
  1. ORYGINALNA transakcja w maju: status=MATCHED/EARLY_PAID
  2. NOWA transakcja w marcu: status=PAID, matchedWithId=oryginał
```

| Plusy | Minusy |
|-------|--------|
| Zachowuje historię "co planowałem" | Bardziej skomplikowane |
| Marzec pokazuje faktyczny przepływ | Dwa wpisy za jedną transakcję |
| Można analizować "early/late payments" | Potencjalne zamieszanie w UI |
| Reconciliation jest pełny | |

### Opcja C: Jeden wpis z metadanymi (REKOMENDOWANA)

```
EXPECTED w maju → płatność w marcu →
  CashChange:
    - dueDate: 2026-05-15 (planowana data)
    - paidDate: 2026-03-10 (faktyczna płatność)
    - status: CONFIRMED
    - effectiveMonth: 2026-03 (do statystyk - kiedy faktycznie wpłynęło)
    - scheduledMonth: 2026-05 (do planowania - kiedy planowałem)
```

| Plusy | Minusy |
|-------|--------|
| Jeden wpis = jedna transakcja | Wymaga rozszerzenia modelu |
| Pełna informacja: plan vs rzeczywistość | Logika statystyk bardziej złożona |
| Statystyki po `paidInMonth` LUB `scheduledMonth` | |
| Idealne do reconciliation | |
| Zachowuje historię planowania | |

---

## Rekomendacja: Opcja C

### Rozszerzenie modelu CashChange

```java
class CashChange {
    // === ISTNIEJĄCE POLA ===
    ZonedDateTime dueDate;      // Kiedy PLANOWAŁEM zapłacić
    ZonedDateTime endDate;      // Data zakończenia (obecne pole)

    // === NOWE/ROZSZERZONE POLA ===
    ZonedDateTime paidDate;     // Kiedy FAKTYCZNIE zapłaciłem (już istnieje w docs)

    // Dla statystyk - w którym miesiącu liczyć jako actual?
    // Domyślnie = YearMonth.from(paidDate) jeśli CONFIRMED
    // Lub YearMonth.from(dueDate) jeśli PENDING/EXPECTED
    YearMonth effectiveMonth;
}
```

### Logika biznesowa

```java
// Przy tworzeniu EXPECTED/PENDING:
effectiveMonth = YearMonth.from(dueDate);

// Przy confirm (payment):
paidDate = confirmationDate;
effectiveMonth = YearMonth.from(paidDate);  // PRZENIESIENIE do właściwego miesiąca

// W statystykach:
// - actual: sum gdzie effectiveMonth = dany miesiąc AND status = CONFIRMED
// - expected: sum gdzie effectiveMonth = dany miesiąc AND status = PENDING
```

---

## Przykład: Scenariusz "Wczesna płatność ubezpieczenia"

### Stan początkowy

```
Data dzisiejsza: 2026-03-10
activePeriod: 2026-03

CashFlow "Home Budget":
├── 2026-03 (ACTIVE)
│   └── [puste]
├── 2026-04 (FORECASTED)
│   └── [puste]
└── 2026-05 (FORECASTED)
    └── EXPECTED: "Ubezpieczenie samochodu" -1200 PLN, dueDate=2026-05-01
```

### Akcja użytkownika

> "Wykupiłem polisę dziś (10 marca) bo była promocja. Chcę potwierdzić tę transakcję."

### Oczekiwane zachowanie (Opcja C)

```
Po potwierdzeniu z paidDate = 2026-03-10:

CashFlow "Home Budget":
├── 2026-03 (ACTIVE)
│   └── CONFIRMED: "Ubezpieczenie samochodu" -1200 PLN
│       ├── dueDate: 2026-05-01 (planowałem na maj)
│       ├── paidDate: 2026-03-10 (zapłaciłem w marcu)
│       ├── effectiveMonth: 2026-03 (liczy się w statystykach marca)
│       └── earlyPaymentDays: 52 dni wcześniej
├── 2026-04 (FORECASTED)
│   └── [puste]
└── 2026-05 (FORECASTED)
    └── [puste] ← transakcja została przeniesiona
```

### Statystyki po zmianie

```
Marzec 2026:
  Actual Outflow: 1200 PLN (ubezpieczenie)

Maj 2026:
  Expected Outflow: 0 PLN (ubezpieczenie już opłacone)

Raport "Plan vs Actual":
  - Ubezpieczenie: planowane 2026-05-01, zapłacone 2026-03-10 (52 dni wcześniej)
```

---

## Przykład: Scenariusz "Wcześniejsza płatność od kontrahenta"

### Stan początkowy

```
Data dzisiejsza: 2026-03-15
activePeriod: 2026-03

CashFlow "Freelance":
├── 2026-03 (ACTIVE)
│   └── [inne transakcje]
├── 2026-04 (FORECASTED)
│   └── [inne transakcje]
└── 2026-05 (FORECASTED)
    └── EXPECTED: "Faktura #2024/05 - Klient ABC" +5000 PLN, dueDate=2026-05-15
```

### Akcja: Bank import lub ręczne potwierdzenie

> "Klient ABC zapłacił wcześniej (15 marca) bo potrzebował faktury rozliczeniowej."

### Oczekiwane zachowanie

```
Po reconciliation/confirm z paidDate = 2026-03-15:

CashFlow "Freelance":
├── 2026-03 (ACTIVE)
│   └── CONFIRMED: "Faktura #2024/05 - Klient ABC" +5000 PLN
│       ├── dueDate: 2026-05-15 (termin płatności)
│       ├── paidDate: 2026-03-15 (data wpływu)
│       ├── effectiveMonth: 2026-03
│       └── earlyPaymentDays: 61 dni wcześniej
├── 2026-04 (FORECASTED)
│   └── [inne transakcje]
└── 2026-05 (FORECASTED)
    └── [puste] ← faktura przeniesiona do marca
```

---

## Walidacje do dodania

### 1. Blokada PAID w przyszłości (bez reconciliation)

```java
// Przy ręcznym dodawaniu PAID transakcji:
if (status == PAID && paidDate.isAfter(now)) {
    throw new InvalidOperationException(
        "Cannot mark transaction as PAID with future date. " +
        "Use EXPECTED status for planned transactions."
    );
}
```

### 2. Confirm z datą w przyszłości

```java
// Przy confirm:
if (paidDate.isAfter(now)) {
    throw new InvalidOperationException(
        "Cannot confirm transaction with future payment date."
    );
}
```

### 3. Informacja o przeniesieniu

```java
// Przy confirm gdzie paidDate jest w innym miesiącu niż dueDate:
if (!YearMonth.from(paidDate).equals(YearMonth.from(dueDate))) {
    log.info("Transaction {} moved from {} to {} due to early/late payment",
        cashChangeId,
        YearMonth.from(dueDate),
        YearMonth.from(paidDate));

    // Opcjonalnie: event dla UI
    emit(new TransactionMovedEvent(cashChangeId,
        YearMonth.from(dueDate),  // scheduledMonth
        YearMonth.from(paidDate)  // effectiveMonth
    ));
}
```

---

## Wpływ na Forecast Processor

### Obecna logika (do zmiany)

```java
// ExpectedCashChangeAppendedEventHandler.java
YearMonth month = YearMonth.from(event.dueDate());  // Używa dueDate
```

### Nowa logika

```java
// Dla EXPECTED/PENDING:
YearMonth month = YearMonth.from(event.dueDate());

// Dla CONFIRMED (CashChangeConfirmedEventHandler):
YearMonth scheduledMonth = YearMonth.from(event.dueDate());
YearMonth effectiveMonth = YearMonth.from(event.paidDate());

if (!scheduledMonth.equals(effectiveMonth)) {
    // Usuń z scheduledMonth
    forecast.removeTransaction(cashChangeId, scheduledMonth);
    // Dodaj do effectiveMonth
    forecast.addTransaction(cashChange, effectiveMonth);
}
```

---

## Powiązane dokumenty

- `docs/features-backlog/2026-02-07-intelligent-cashflow-reconciliation.md` - pełna architektura reconciliation
- `docs/features-backlog/2026-02-14-recurring-rule-engine-design.md` - reguły cykliczne (FORECASTED)
- `CLAUDE.md` - statusy CashChange i ich znaczenie

---

## Następne kroki

1. [ ] Rozszerzyć model `CashChange` o pole `effectiveMonth` (lub wykorzystać istniejące `paidDate`)
2. [ ] Zaktualizować `ConfirmCashChangeCommandHandler` o logikę przenoszenia
3. [ ] Zaktualizować `CashFlowForecastProcessor` o obsługę przeniesień
4. [ ] Dodać walidację blokującą PAID w przyszłości
5. [ ] Dodać testy dla scenariuszy early/late payment
6. [ ] Rozważyć UI: wizualizacja "early payment" / "late payment"

---

## Podsumowanie

**Odpowiedź na pytanie biznesowe:**

> Czy Vidulum powinien przenieść transakcję z przyszłości do bieżącego miesiąca i ją opłacić?

**TAK** - z następującą logiką:

1. **Zachowaj `dueDate`** - kiedy planowałeś zapłacić (historia planowania)
2. **Ustaw `paidDate`** - kiedy faktycznie zapłaciłeś
3. **Przenieś do `effectiveMonth = YearMonth.from(paidDate)`** - statystyki pokazują rzeczywisty przepływ
4. **W statystykach marzec** - pokazuje faktyczny wydatek/wpływ
5. **W raportach "Plan vs Actual"** - widać early/late payments

To jest właśnie **reconciliation** w pełnym tego słowa znaczeniu - pogodzenie planu z rzeczywistością.
