# Prekonfigurowany CashFlow - Ładowanie danych historycznych

## Spis treści

1. [Problem](#problem)
2. [Rozwiązanie - Overview](#rozwiązanie---overview)
3. [Quick Start vs Advanced Setup](#quick-start-vs-advanced-setup)
4. [Statusy i stany](#statusy-i-stany)
5. [Flow życia CashFlow](#flow-życia-cashflow)
6. [Trzy endpointy do dodawania CashChange](#trzy-endpointy-do-dodawania-cashchange)
7. [System kategorii z okresem ważności](#system-kategorii-z-okresem-ważności)
8. [Import historyczny - pełny flow](#import-historyczny---pełny-flow)
9. [Operacje w trybie SETUP](#operacje-w-trybie-setup)
10. [Aktywacja CashFlow](#aktywacja-cashflow)
11. [Rollback importu](#rollback-importu)
12. [Korekty w miesiącach ATTESTED](#korekty-w-miesiącach-attested)
13. [Domain Events](#domain-events)
14. [Integracja UI Web App](#integracja-ui-web-app)
15. [Killer Features](#killer-features)
16. [Analiza konkurencji](#analiza-konkurencji)
17. [Model biznesowy](#model-biznesowy)
18. [Zagrożenia i ryzyka](#zagrożenia-i-ryzyka)
19. [Pytania otwarte](#pytania-otwarte)
20. [Następne kroki](#następne-kroki)

---

## Problem

Obecnie przy tworzeniu CashFlow:
- Tworzone jest tylko 12 miesięcy do przodu (bieżący + 11 przyszłych)
- Brak możliwości dodania danych historycznych (np. 2 lata wstecz)
- Próba dodania CashChange do nieistniejącego miesiąca spowoduje błąd
- Brak integracji z wyciągami bankowymi / API banków

**Cel:** Umożliwić import danych historycznych z banku (wyciąg lub API) z pełnym mapowaniem kategorii, walidacją i kontrolą przed aktywacją CashFlow.

---

## Rozwiązanie - Overview

### Kluczowe założenia

1. **Tryb SETUP** - dedykowany stan do konfiguracji i importu historii
2. **Import jako jedyne źródło danych historycznych** - w SETUP nie można dodawać transakcji ręcznie
3. **Mapowanie kategorii przed importem** - użytkownik decyduje jak zmapować kategorie bankowe
4. **Kategorie z okresem ważności** - ta sama nazwa może istnieć wielokrotnie z różnymi przedziałami czasowymi
5. **Rollback zamiast edycji** - błędy naprawiane przez wyczyszczenie i ponowny import
6. **Atestacja przy aktywacji** - potwierdzenie balance przed przejściem do OPEN

### Diagram stanów - wysokopoziomowy

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                           CASHFLOW LIFECYCLE                                  │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐                   │
│  │ CREATE  │───▶│  SETUP  │───▶│  OPEN   │───▶│ CLOSED  │                   │
│  └─────────┘    └────┬────┘    └─────────┘    └─────────┘                   │
│                      │                                                       │
│                      │ W trybie SETUP:                                       │
│                      ├─ configureCategoryMapping                             │
│                      ├─ importHistoricalCashChange                           │
│                      ├─ rollbackImport                                       │
│                      └─ activateCashFlow (wymaga atestacji balance)          │
│                                                                              │
│                      ❌ ZABLOKOWANE w SETUP:                                 │
│                      ├─ appendCashChange                                     │
│                      ├─ appendPaidCashChange                                 │
│                      ├─ editCashChange                                       │
│                      └─ confirmCashChange                                    │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start vs Advanced Setup

### Dwa tryby onboardingu

Aplikacja oferuje dwa sposoby rozpoczęcia pracy, dopasowane do różnych potrzeb użytkowników:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         WYBIERZ SPOSÓB STARTU                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────┐  ┌─────────────────────────────────┐  │
│  │                                 │  │                                 │  │
│  │  ⚡ QUICK START                 │  │  🔧 ADVANCED SETUP              │  │
│  │                                 │  │                                 │  │
│  │  Zacznij od zera               │  │  Import historii z banku       │  │
│  │  ~3 minuty                     │  │  ~15-30 minut                   │  │
│  │                                 │  │                                 │  │
│  │  ✓ Podaj obecne saldo          │  │  ✓ Pełna historia transakcji   │  │
│  │  ✓ Dodawaj transakcje ręcznie  │  │  ✓ Dokładne statystyki         │  │
│  │  ✓ Natychmiastowy start        │  │  ✓ Trendy i prognozy           │  │
│  │                                 │  │  ✓ Mapowanie kategorii         │  │
│  │  [Wybierz]                     │  │  [Wybierz]                      │  │
│  │                                 │  │                                 │  │
│  └─────────────────────────────────┘  └─────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Quick Start - szczegóły

**Dla kogo:**
- Użytkownicy chcący szybko zacząć
- Osoby bez dostępu do wyciągów bankowych
- Testowanie aplikacji

**Proces:**
1. Podaj nazwę konta i walutę
2. Wprowadź obecne saldo
3. Gotowe! → CashFlow w statusie OPEN

**Co dostaje użytkownik:**
- Bieżący miesiąc jako ACTIVE
- 11 miesięcy do przodu jako FORECASTED
- Domyślna kategoria "Uncategorized"
- Możliwość ręcznego dodawania transakcji

**Ograniczenia:**
- Brak historycznych danych
- Brak trendów z przeszłości
- Prognozy oparte tylko na przyszłych danych

### Advanced Setup - szczegóły

**Dla kogo:**
- Użytkownicy chcący pełnej kontroli
- Osoby z wyciągami bankowymi (CSV/MT940)
- Świadomi użytkownicy planujący budżet

**Proces (4 kroki):**
1. **Podstawowe info** - nazwa, bank, waluty, zakres dat, salda
2. **Import danych** - upload CSV lub połączenie z API banku
3. **Mapowanie kategorii** - przypisanie kategorii bankowych do systemowych
4. **Aktywacja** - weryfikacja salda i uruchomienie

**Co dostaje użytkownik:**
- Pełna historia (do 5 lat wstecz)
- Dokładne statystyki i trendy
- Zmapowane kategorie
- Prognozy oparte na rzeczywistych danych

### Macierz porównawcza

| Cecha | Quick Start | Advanced Setup |
|-------|-------------|----------------|
| Czas setup | ~3 min | ~15-30 min |
| Wymagania | Tylko saldo | Wyciąg bankowy |
| Historia | Brak | Do 5 lat |
| Kategorie | Tylko Uncategorized | Zmapowane z banku |
| Trendy historyczne | ❌ | ✅ |
| Prognozy AI | Ograniczone | Pełne |
| Insights | Podstawowe | Zaawansowane |
| Anomaly detection | ❌ | ✅ |

### Migracja Quick Start → Advanced Setup

Użytkownik który zaczął od Quick Start może później doładować historię:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    USTAWIENIA > IMPORT HISTORII                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Twoje konto "Główne ING" działa od: 2026-01-05                            │
│                                                                             │
│  Chcesz doładować dane historyczne z banku?                                │
│                                                                             │
│  • Zachowasz wszystkie ręcznie dodane transakcje                           │
│  • System zmapuje Twoje kategorie z historią                               │
│  • Dostaniesz pełne statystyki i prognozy                                  │
│                                                                             │
│  [📄 Importuj z pliku]  [🔗 Połącz z bankiem]                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Uwaga:** Import historyczny po aktywacji może wymagać:
- Rekoncyliacji istniejących transakcji z importowanymi
- Rozwiązania konfliktów kategorii
- Korekty salda początkowego

To jest funkcja "nice to have" na przyszłość - na start skupiamy się na czystym Advanced Setup.

---

## Statusy i stany

### CashFlow Status

```java
public enum CashFlowStatus {
    SETUP,  // tryb prekonfiguracji - tylko import historyczny
    OPEN,   // normalny tryb operacyjny
    CLOSED  // zamknięty
}
```

### Forecast (miesiąc) Status

```java
public enum Status {
    SETUP_PENDING,  // historyczny miesiąc w trakcie importu
    ATTESTED,       // zamknięty, dane finalne
    ACTIVE,         // bieżący miesiąc
    FORECASTED      // przyszłe miesiące
}
```

### Macierz: CashFlow Status × Forecast Status

| Utworzenie z datą | Miesiące historyczne | Bieżący miesiąc | Miesiące przyszłe |
|-------------------|---------------------|-----------------|-------------------|
| 2024-01-01 (2 lata temu) | SETUP_PENDING | ACTIVE | FORECASTED |
| Po aktywacji | ATTESTED | ACTIVE | FORECASTED |

---

## Flow życia CashFlow

### Pełny flow z importem historycznym

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ ETAP 1: UTWORZENIE CASHFLOW                                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ User wywołuje: createCashFlowWithHistory                                    │
│                                                                             │
│ Input:                                                                      │
│   - name: "Konto główne ING"                                                │
│   - startDate: 2024-01-01 (początek historii)                               │
│   - initialBalance: 3000 PLN (stan konta na startDate)                      │
│   - currentBalance: 5000 PLN (obecny stan konta - z banku)                  │
│   - bankAccount: { name: "ING", number: "PL123...", currency: "PLN" }       │
│                                                                             │
│ Efekt:                                                                      │
│   - Status: SETUP                                                           │
│   - Miesiące 2024-01 → 2025-12: SETUP_PENDING                               │
│   - Miesiąc 2026-01: ACTIVE                                                 │
│   - Miesiące 2026-02 → 2026-12: FORECASTED                                  │
│   - Domyślne kategorie: Uncategorized (INFLOW + OUTFLOW)                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ETAP 2: POBRANIE DANYCH Z BANKU                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ Źródło danych (jedno z):                                                    │
│   - Plik CSV/MT940 z wyciągiem bankowym                                     │
│   - API banku (PSD2, Open Banking)                                          │
│   - Ręczny eksport z bankowości internetowej                                │
│                                                                             │
│ System parsuje i zwraca:                                                    │
│   - Lista transakcji z banku                                                │
│   - Lista unikalnych kategorii bankowych                                    │
│   - Podsumowanie (ile transakcji, zakres dat, suma)                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ETAP 3: MAPOWANIE KATEGORII                                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ System pokazuje znalezione kategorie bankowe.                               │
│ User decyduje dla każdej:                                                   │
│                                                                             │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │ Bank Category: "Groceries" (45 transakcji, -3,450 PLN)           │      │
│   │                                                                  │      │
│   │ ○ Utwórz nową kategorię: [Jedzenie        ]                      │      │
│   │ ○ Utwórz jako subkategorię: [Dom] / [Jedzenie]                   │      │
│   │ ○ Mapuj do Uncategorized                                         │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                                                                             │
│ User wywołuje: configureCategoryMapping                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ETAP 4: IMPORT TRANSAKCJI                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ System wykonuje import:                                                     │
│   1. Tworzy kategorie według mapowania (z validFrom/validTo)                │
│   2. Importuje transakcje do odpowiednich miesięcy (SETUP_PENDING)          │
│   3. Przelicza balance dla każdego miesiąca                                 │
│                                                                             │
│ User może powtórzyć etapy 2-4 (np. import kolejnych miesięcy)               │
│                                                                             │
│ Jeśli błąd → rollbackImport i powrót do etapu 2                             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ETAP 5: AKTYWACJA (ATESTACJA)                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ User wywołuje: activateCashFlow                                             │
│                                                                             │
│ Input:                                                                      │
│   - confirmedCurrentBalance: 5000 PLN (user potwierdza stan konta)          │
│                                                                             │
│ Walidacja:                                                                  │
│   calculatedBalance = initialBalance + Σ(inflows) - Σ(outflows)             │
│                                                                             │
│   Jeśli calculatedBalance ≠ confirmedCurrentBalance:                        │
│     → Opcja 1: Warning + forceActivation: true                              │
│     → Opcja 2: Auto-adjustment transaction                                  │
│                                                                             │
│ Efekt:                                                                      │
│   - Status: SETUP → OPEN                                                    │
│   - Wszystkie SETUP_PENDING → ATTESTED                                      │
│   - Kategorie historyczne: archived = true                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ETAP 6: NORMALNA PRACA                                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ CashFlow w statusie OPEN - dostępne operacje:                               │
│   - appendCashChange (PENDING)                                              │
│   - appendPaidCashChange (CONFIRMED)                                        │
│   - confirmCashChange                                                       │
│   - editCashChange                                                          │
│   - rejectCashChange                                                        │
│   - attestMonth                                                             │
│   - createCategory (nowe kategorie z validFrom = now)                       │
│                                                                             │
│ ZABLOKOWANE:                                                                │
│   - importHistoricalCashChange                                              │
│   - rollbackImport                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Trzy endpointy do dodawania CashChange

### Podział odpowiedzialności

| Endpoint | Status CashChange | Dozwolone miesiące | CashFlow Status |
|----------|-------------------|--------------------|-----------------------------|
| `appendCashChange` | PENDING | ACTIVE, FORECASTED | tylko OPEN |
| `appendPaidCashChange` | CONFIRMED | ACTIVE, FORECASTED | tylko OPEN |
| `importHistoricalCashChange` | CONFIRMED | SETUP_PENDING | tylko SETUP |

### 1. `appendCashChange` (istniejący - zmodyfikowany)

Dodaje oczekiwaną transakcję (PENDING) do bieżącego lub przyszłego miesiąca.

```java
@Data
@Builder
public static class AppendCashChangeJson {
    private String cashFlowId;
    private String category;
    private String name;
    private String description;
    private Money money;
    private Type type;
    private ZonedDateTime dueDate;
}
```

**Walidacje:**
```java
// NOWA walidacja - tylko w OPEN
if (cashFlow.getStatus() != OPEN) {
    throw new OperationNotAllowedInSetupModeException(
        "Cannot append cash change in SETUP mode. " +
        "Please complete the setup and activate CashFlow first."
    );
}

// istniejąca walidacja - tylko ACTIVE/FORECASTED
if (targetMonth.isBefore(activeMonth)) {
    throw new CannotAppendToHistoricalMonthException();
}
```

### 2. `appendPaidCashChange` (nowy)

Dodaje już opłaconą transakcję (CONFIRMED) do bieżącego lub przyszłego miesiąca.

**Use cases:**
- Zapomniałem dodać transakcję która już została opłacona
- Przedpłata - zapłaciłem dziś za coś co ma dueDate w przyszłości

```java
@Data
@Builder
public static class AppendPaidCashChangeJson {
    private String cashFlowId;
    private String category;
    private String name;
    private String description;
    private Money money;
    private Type type;
    private ZonedDateTime dueDate;
    private ZonedDateTime paidDate;  // wymagane
}
```

**Walidacje:**
```java
// tylko w OPEN
if (cashFlow.getStatus() != OPEN) {
    throw new OperationNotAllowedInSetupModeException();
}

// tylko ACTIVE/FORECASTED
if (targetMonth.isBefore(activeMonth)) {
    throw new CannotAppendToHistoricalMonthException();
}

// paidDate nie może być w przyszłości
if (paidDate.isAfter(now)) {
    throw new PaidDateCannotBeInFutureException();
}
```

### 3. `importHistoricalCashChange` (nowy)

Importuje historyczną transakcję. **Tylko w trybie SETUP.**

```java
@Data
@Builder
public static class ImportHistoricalCashChangeJson {
    private String cashFlowId;
    private String categoryName;          // zmapowana kategoria
    private String name;
    private String description;
    private Money money;
    private Type type;
    private ZonedDateTime dueDate;
    private ZonedDateTime paidDate;
    private String bankTransactionId;     // opcjonalne - ID z banku dla deduplikacji
}
```

**Walidacje:**
```java
// tylko SETUP mode
if (cashFlow.getStatus() != SETUP) {
    throw new HistoricalImportOnlyInSetupModeException(
        "Cannot import historical data. CashFlow must be in SETUP mode. " +
        "Current status: " + cashFlow.getStatus()
    );
}

// tylko historyczne miesiące (SETUP_PENDING)
YearMonth targetMonth = YearMonth.from(dueDate);
YearMonth activeMonth = cashFlow.getActivePeriod();

if (targetMonth.compareTo(activeMonth) >= 0) {
    throw new CanOnlyImportToHistoricalMonthsException(
        "Cannot import to current or future months. " +
        "Target: " + targetMonth + ", Active: " + activeMonth
    );
}

// kategoria musi istnieć (utworzona przez mapowanie)
if (!categoryExists(categoryName, type)) {
    throw new CategoryNotFoundException(categoryName);
}
```

### Macierz dozwolonych operacji - pełna

| CashFlow | Miesiąc | `append` | `appendPaid` | `importHistorical` | `edit` | `confirm` |
|----------|---------|----------|--------------|---------------------|--------|-----------|
| SETUP | SETUP_PENDING | ❌ | ❌ | ✅ | ❌ | ❌ |
| SETUP | ACTIVE | ❌ | ❌ | ❌ | ❌ | ❌ |
| SETUP | FORECASTED | ❌ | ❌ | ❌ | ❌ | ❌ |
| OPEN | ATTESTED | ❌ | ❌ | ❌ | ❌ | ❌ |
| OPEN | ACTIVE | ✅ | ✅ | ❌ | ✅ | ✅ |
| OPEN | FORECASTED | ✅ | ✅ | ❌ | ✅ | ❌ |

---

## System kategorii z okresem ważności

### Problem do rozwiązania

```
Scenariusz:
- 2024-01: Import → kategoria "Samochód" (paliwo, ubezpieczenie)
- 2024-06: Sprzedaż auta
- 2025-01: Kupno nowego auta → nowa kategoria "Samochód" (leasing, inne subkategorie)

Pytanie: Jak obsłużyć dwie kategorie o tej samej nazwie?
Odpowiedź: Okres ważności (validFrom, validTo)
```

### Struktura kategorii

```java
public record Category(
    CategoryId id,                    // unikalny identyfikator
    CategoryName name,                // nazwa (może się powtarzać!)
    List<Category> subCategories,
    Type type,                        // INFLOW / OUTFLOW
    boolean userDefined,              // false = systemowa (Uncategorized)
    CategoryOrigin origin,            // SYSTEM, IMPORTED, USER_CREATED
    ZonedDateTime validFrom,          // od kiedy obowiązuje
    ZonedDateTime validTo,            // do kiedy (null = aktywna)
    boolean archived                  // czy zarchiwizowana
) {}

public enum CategoryOrigin {
    SYSTEM,         // Uncategorized - tworzona automatycznie
    IMPORTED,       // z importu bankowego
    USER_CREATED    // utworzona ręcznie przez użytkownika
}
```

### Reguły biznesowe kategorii

1. **Unikalna kombinacja:** `(name, type, validFrom)` musi być unikalna
2. **Okresy nie zachodzą:** Kategorie o tej samej nazwie nie mogą mieć nakładających się okresów
3. **Import tworzy archived:** Kategorie z importu mają `archived = true`
4. **Nowe kategorie:** `validFrom = now`, `validTo = null`, `archived = false`
5. **Uncategorized:** Zawsze istnieje, `origin = SYSTEM`, nie można usunąć/zarchiwizować

### Przykład - timeline kategorii

```
📁 Kategorie OUTFLOW - widok pełny (z archived)

├── 🏠 Dom (2024-01 → teraz) [active, IMPORTED]
│   ├── Czynsz (2024-01 → teraz)
│   └── Media (2024-01 → teraz)
│
├── 🚗 Samochód (2024-01 → 2024-06) [archived, IMPORTED]
│   ├── Paliwo
│   ├── Ubezpieczenie OC
│   └── Przegląd
│
├── 🚗 Samochód (2025-01 → teraz) [active, USER_CREATED]
│   ├── Leasing
│   ├── Paliwo
│   └── Myjnia
│
├── 🚌 Transport (2024-06 → teraz) [active, USER_CREATED]
│   ├── Bilet MPK
│   └── Veturilo
│
└── 📦 Uncategorized (zawsze) [active, SYSTEM]
```

### Wybór kategorii w UI - dropdown

```
Nowa transakcja OUTFLOW - wybierz kategorię:

┌─────────────────────────────────────────────────┐
│ 🔍 Szukaj kategorii...                          │
├─────────────────────────────────────────────────┤
│ Aktywne:                                        │
│   🏠 Dom                                        │
│      └─ Czynsz                                  │
│      └─ Media                                   │
│   🚗 Samochód (od 2025)                         │
│      └─ Leasing                                 │
│      └─ Paliwo                                  │
│   🚌 Transport                                  │
│   📦 Uncategorized                              │
├─────────────────────────────────────────────────┤
│ ☑️ Pokaż archiwalne                             │
│   🚗 Samochód (2024-01 → 2024-06) [archived]    │
└─────────────────────────────────────────────────┘
```

### Walidacja przy wyborze kategorii

```java
// Przy appendCashChange/appendPaidCashChange
Category category = findCategory(categoryName, type);

// Sprawdź czy kategoria jest aktywna w danym okresie
if (category.getValidTo() != null && dueDate.isAfter(category.getValidTo())) {
    throw new CategoryNotValidForDateException(
        "Category '" + categoryName + "' is not valid for date " + dueDate +
        ". Valid until: " + category.getValidTo()
    );
}

if (dueDate.isBefore(category.getValidFrom())) {
    throw new CategoryNotValidForDateException(
        "Category '" + categoryName + "' is not valid for date " + dueDate +
        ". Valid from: " + category.getValidFrom()
    );
}
```

---

## Import historyczny - pełny flow

### Etap 1: Parsowanie danych z banku

**Input:** Plik CSV lub odpowiedź z API banku

```java
@Data
@Builder
public static class BankDataParseResultJson {
    private List<BankTransactionJson> transactions;
    private List<BankCategoryJson> categories;
    private BankDataSummaryJson summary;
}

@Data
@Builder
public static class BankTransactionJson {
    private String bankTransactionId;     // ID z banku
    private ZonedDateTime date;
    private String description;
    private BigDecimal amount;
    private String bankCategoryName;      // kategoria z banku
    private String bankCategoryType;      // INFLOW/OUTFLOW lub kredyt/debet
}

@Data
@Builder
public static class BankCategoryJson {
    private String name;
    private Type type;                    // INFLOW / OUTFLOW
    private int transactionCount;
    private Money totalAmount;
}

@Data
@Builder
public static class BankDataSummaryJson {
    private int totalTransactions;
    private ZonedDateTime dateFrom;
    private ZonedDateTime dateTo;
    private Money totalInflows;
    private Money totalOutflows;
    private Money netChange;
}
```

### Etap 2: Konfiguracja mapowania kategorii

**Request:**

```java
@Data
@Builder
public static class ConfigureCategoryMappingJson {
    private String cashFlowId;
    private List<CategoryMappingJson> mappings;
}

@Data
@Builder
public static class CategoryMappingJson {
    private String bankCategoryName;       // "Groceries" - z banku
    private MappingAction action;          // co zrobić
    private String targetCategoryName;     // "Jedzenie" - docelowa nazwa
    private String parentCategoryName;     // opcjonalnie - dla subkategorii
    private Type type;                     // INFLOW / OUTFLOW
}

public enum MappingAction {
    CREATE_NEW,           // utwórz nową kategorię
    CREATE_SUBCATEGORY,   // utwórz jako subkategorię
    MAP_TO_UNCATEGORIZED  // wrzuć do Uncategorized
}
```

**Przykład:**

```json
{
  "cashFlowId": "cf-123",
  "mappings": [
    {
      "bankCategoryName": "Groceries",
      "action": "CREATE_NEW",
      "targetCategoryName": "Jedzenie",
      "type": "OUTFLOW"
    },
    {
      "bankCategoryName": "Bills",
      "action": "CREATE_SUBCATEGORY",
      "targetCategoryName": "Prąd",
      "parentCategoryName": "Rachunki",
      "type": "OUTFLOW"
    },
    {
      "bankCategoryName": "ATM",
      "action": "MAP_TO_UNCATEGORIZED",
      "type": "OUTFLOW"
    }
  ]
}
```

**Response:**

```java
@Data
@Builder
public static class CategoryMappingResultJson {
    private List<CreatedCategoryJson> createdCategories;
    private Map<String, String> mappingTable;  // bankCategory → systemCategory
    private int unmappedCategoriesCount;
}
```

### Etap 3: Batch import transakcji

**Request:**

```java
@Data
@Builder
public static class BatchImportHistoricalCashChangesJson {
    private String cashFlowId;
    private List<ImportHistoricalCashChangeJson> transactions;
}
```

**Response:**

```java
@Data
@Builder
public static class BatchImportResultJson {
    private int totalProcessed;
    private int successCount;
    private int failedCount;
    private int duplicatesSkipped;
    private List<ImportErrorJson> errors;
    private Map<YearMonth, Integer> transactionsPerMonth;
}

@Data
@Builder
public static class ImportErrorJson {
    private int index;
    private String bankTransactionId;
    private String errorCode;
    private String errorMessage;
}
```

### Deduplikacja

```java
// Przy imporcie sprawdź czy transakcja już istnieje
if (bankTransactionId != null) {
    boolean exists = cashChangeRepository.existsByBankTransactionId(
        cashFlowId, bankTransactionId
    );
    if (exists) {
        // Skip duplicate
        return ImportResult.DUPLICATE_SKIPPED;
    }
}
```

---

## Operacje w trybie SETUP

### Dozwolone operacje

| Operacja | Opis | Wielokrotnie? |
|----------|------|---------------|
| `configureCategoryMapping` | Konfiguracja mapowania kategorii | ✅ (nadpisuje poprzednie) |
| `importHistoricalCashChange` | Import pojedynczej transakcji | ✅ |
| `batchImportHistoricalCashChanges` | Import wielu transakcji | ✅ |
| `rollbackImport` | Wyczyszczenie zaimportowanych danych | ✅ |
| `activateCashFlow` | Przejście do OPEN | ✅ (jedna udana) |

### Zablokowane operacje

| Operacja | Komunikat błędu |
|----------|-----------------|
| `appendCashChange` | "Operation not allowed in SETUP mode. Complete setup first." |
| `appendPaidCashChange` | "Operation not allowed in SETUP mode. Complete setup first." |
| `editCashChange` | "Cannot edit transactions in SETUP mode. Use rollback instead." |
| `confirmCashChange` | "Operation not allowed in SETUP mode." |
| `rejectCashChange` | "Operation not allowed in SETUP mode." |
| `attestMonth` | "Cannot attest months in SETUP mode. Use activateCashFlow." |
| `createCategory` | "Cannot manually create categories in SETUP mode. Use category mapping." |

---

## Aktywacja CashFlow

### Request

```java
@Data
@Builder
public static class ActivateCashFlowJson {
    private String cashFlowId;
    private Money confirmedCurrentBalance;  // user potwierdza obecny stan konta
    private boolean forceActivation;        // opcja 2: wymuś mimo niezgodności
    private boolean createAdjustment;       // opcja 3: utwórz korektę
}
```

### Walidacja balance

```java
Money initialBalance = cashFlow.getInitialBalance();
Money calculatedBalance = calculateBalance(cashFlow);  // initial + inflows - outflows
Money confirmedBalance = request.getConfirmedCurrentBalance();

if (!calculatedBalance.equals(confirmedBalance)) {
    Money difference = confirmedBalance.minus(calculatedBalance);

    if (request.isForceActivation()) {
        // Opcja 2: Warning w response, ale aktywuj
        log.warn("Balance mismatch: calculated={}, confirmed={}, diff={}",
            calculatedBalance, confirmedBalance, difference);
        // kontynuuj aktywację

    } else if (request.isCreateAdjustment()) {
        // Opcja 3: Utwórz transakcję korygującą
        createAdjustmentTransaction(cashFlow, difference);
        // kontynuuj aktywację

    } else {
        // Domyślnie: błąd
        throw new BalanceMismatchException(
            calculatedBalance, confirmedBalance, difference,
            "Balance mismatch. Use forceActivation or createAdjustment to proceed."
        );
    }
}
```

### Efekt aktywacji

```java
// 1. Zmień status CashFlow
cashFlow.setStatus(CashFlowStatus.OPEN);

// 2. Zmień status wszystkich SETUP_PENDING → ATTESTED
for (CashFlowMonthlyForecast forecast : cashFlow.getForecasts().values()) {
    if (forecast.getStatus() == Status.SETUP_PENDING) {
        forecast.setStatus(Status.ATTESTED);
    }
}

// 3. Oznacz kategorie historyczne jako archived
for (Category category : cashFlow.getCategories()) {
    if (category.getOrigin() == CategoryOrigin.IMPORTED) {
        category.setArchived(true);
    }
}

// 4. Emit event
emit(new CashFlowActivatedEvent(
    cashFlowId,
    confirmedBalance,
    calculatedBalance,
    adjustmentCreated,
    activatedAt
));
```

---

## Rollback importu

### Request

```java
@Data
@Builder
public static class RollbackImportJson {
    private String cashFlowId;
    private boolean deleteCategories;  // true = usuń też kategorie (bez Uncategorized)
}
```

### Walidacja

```java
if (cashFlow.getStatus() != SETUP) {
    throw new RollbackOnlyInSetupModeException(
        "Rollback is only allowed in SETUP mode. Current status: " + cashFlow.getStatus()
    );
}
```

### Efekt rollbacku

```java
// 1. Usuń wszystkie zaimportowane transakcje
cashChangeRepository.deleteAllByCashFlowId(cashFlowId);

// 2. Resetuj statystyki miesięcy SETUP_PENDING
for (CashFlowMonthlyForecast forecast : cashFlow.getForecasts().values()) {
    if (forecast.getStatus() == Status.SETUP_PENDING) {
        forecast.resetToEmpty();
    }
}

// 3. Opcjonalnie usuń kategorie
if (deleteCategories) {
    categoryRepository.deleteAllByCashFlowIdExceptUncategorized(cashFlowId);
}

// 4. NIE zmieniaj: initialBalance, currentBalance, startDate

// 5. Emit event
emit(new ImportRolledBackEvent(
    cashFlowId,
    deletedTransactionsCount,
    deletedCategoriesCount,
    rolledBackAt
));
```

---

## Korekty w miesiącach ATTESTED

### Problem

Po aktywacji CashFlow, miesiące historyczne mają status ATTESTED - są zamknięte i nie można ich modyfikować.
Jednak w praktyce mogą wystąpić sytuacje wymagające korekty:

- Błędnie skategoryzowana transakcja
- Brakująca transakcja gotówkowa
- Błędna kwota (np. literówka w imporcie)
- Potrzeba podziału transakcji na subkategorie

### Filozofia: Korekta zamiast edycji

**NIE edytujemy** oryginalnych transakcji w ATTESTED - to zaburzyłoby audit trail i spójność danych.

**Tworzymy korekty** - nowe eventy które:
- Odwołują się do oryginalnej transakcji
- Dokumentują zmianę z uzasadnieniem
- Zachowują pełną historię zmian
- Są widoczne w raportach jako "korekta"

### Typy korekt

```java
public enum CorrectionType {
    CATEGORY_CHANGE,      // zmiana kategorii
    AMOUNT_ADJUSTMENT,    // korekta kwoty
    ADD_MISSING,          // dodanie brakującej transakcji
    VOID,                 // anulowanie błędnej transakcji
    SPLIT                 // podział na wiele transakcji
}
```

### Domain Events dla korekt

```java
// Korekta kategorii
record CashChangeCategoryCorrectedEvent(
    CashFlowId cashFlowId,
    CashChangeId originalCashChangeId,
    CashChangeId correctionId,
    CategoryName previousCategory,
    CategoryName newCategory,
    String reason,
    ZonedDateTime correctedAt
) implements CashFlowEvent

// Korekta kwoty
record CashChangeAmountCorrectedEvent(
    CashFlowId cashFlowId,
    CashChangeId originalCashChangeId,
    CashChangeId correctionId,
    Money previousAmount,
    Money newAmount,
    String reason,
    ZonedDateTime correctedAt
) implements CashFlowEvent

// Dodanie brakującej transakcji do zamkniętego miesiąca
record MissingCashChangeAddedEvent(
    CashFlowId cashFlowId,
    CashChangeId cashChangeId,
    YearMonth period,           // do którego miesiąca ATTESTED
    Name name,
    Description description,
    Money money,
    Type type,
    CategoryName categoryName,
    ZonedDateTime originalDate,  // kiedy transakcja faktycznie miała miejsce
    String reason,               // dlaczego brakowało
    ZonedDateTime addedAt
) implements CashFlowEvent

// Anulowanie transakcji
record CashChangeVoidedEvent(
    CashFlowId cashFlowId,
    CashChangeId cashChangeId,
    String reason,
    ZonedDateTime voidedAt
) implements CashFlowEvent
```

### UI - Panel korekt

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    MIESIĄC: GRUDZIEŃ 2024 [ATTESTED]                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  📋 TRANSAKCJE                                              [+ Dodaj korektę] │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 2024-12-05  Zakupy Biedronka         -247,50 PLN   🍎 Jedzenie     │   │
│  │             ⚠️ Skorygowano: była kategoria "Uncategorized"          │   │
│  │                                                       [📝 Historia] │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 2024-12-10  Netflix                   -52,99 PLN   📺 Rozrywka     │   │
│  │                                                    [⋮ Opcje]        │   │
│  │                                                    ├─ Zmień kategorię │
│  │                                                    ├─ Skoryguj kwotę │
│  │                                                    └─ Anuluj        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 2024-12-15  [KOREKTA] Dodana brakująca transakcja                  │   │
│  │             Wpłata gotówki            +500,00 PLN  💰 Wpływy inne  │   │
│  │             Powód: "Zapomniałem dodać wpłatę z urodzin"            │   │
│  │                                                       [📝 Historia] │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Modal: Dodawanie korekty

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        DODAJ KOREKTĘ                              [X]       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Typ korekty:                                                               │
│  ○ Zmiana kategorii istniejącej transakcji                                 │
│  ○ Korekta kwoty istniejącej transakcji                                    │
│  ● Dodanie brakującej transakcji                                           │
│  ○ Anulowanie błędnej transakcji                                           │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  Miesiąc:           [Grudzień 2024 ▼]  (tylko ATTESTED)                    │
│                                                                             │
│  Data transakcji:   [2024-12-15  📅]                                       │
│                                                                             │
│  Nazwa:             [Wpłata gotówki                      ]                 │
│                                                                             │
│  Kwota:             [500,00      ] PLN   ○ Wpływ  ● Wydatek                │
│                                                                             │
│  Kategoria:         [Wpływy inne ▼]                                        │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  ⚠️ Powód korekty (wymagany):                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Zapomniałem dodać wpłatę gotówkową z urodzin                        │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                                           [Anuluj]  [💾 Zapisz korektę]    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Walidacje korekt

```java
// Korekta możliwa tylko dla miesięcy ATTESTED
if (forecast.getStatus() != Status.ATTESTED) {
    throw new CorrectionOnlyForAttestedMonthsException(
        "Corrections are only allowed for ATTESTED months. " +
        "For ACTIVE/FORECASTED months, use regular edit operations."
    );
}

// Powód jest wymagany
if (reason == null || reason.isBlank()) {
    throw new CorrectionReasonRequiredException(
        "A reason must be provided for all corrections."
    );
}

// Data musi być w zakresie miesiąca
if (!YearMonth.from(originalDate).equals(targetMonth)) {
    throw new DateOutsideMonthRangeException(
        "Transaction date must be within the target month."
    );
}
```

### Wpływ korekt na statystyki

Korekty **aktualizują** statystyki miesiąca ATTESTED:
- Zmiana kategorii → przeliczenie sum per kategoria
- Korekta kwoty → przeliczenie balance i sum
- Dodanie transakcji → aktualizacja wszystkich sum
- Anulowanie → odjęcie od sum (transakcja widoczna jako "anulowana")

**Uwaga:** Korekta może wpłynąć na bilans końcowy miesiąca, co może spowodować kaskadową aktualizację kolejnych miesięcy. System powinien ostrzec użytkownika przed zapisaniem korekty o potencjalnym wpływie.

### Raportowanie korekt

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RAPORT KOREKT - ROK 2024                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  📊 PODSUMOWANIE                                                            │
│                                                                             │
│  Liczba korekt:         12                                                  │
│  Wartość netto:         +847,50 PLN (więcej wpływów niż pierwotnie)        │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📋 SZCZEGÓŁY                                                               │
│                                                                             │
│  │ Data       │ Typ          │ Oryginał          │ Po korekcie    │ Powód │
│  │────────────│──────────────│───────────────────│────────────────│───────│
│  │ 2024-12-15 │ ADD_MISSING  │ -                 │ +500 PLN       │ (...)│
│  │ 2024-11-20 │ CATEGORY     │ Uncategorized     │ Jedzenie       │ (...)│
│  │ 2024-11-05 │ AMOUNT       │ -100 PLN          │ -150 PLN       │ (...)│
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Domain Events

### Nowe eventy

```java
// Utworzenie CashFlow z historią
record CashFlowWithHistoryCreatedEvent(
    CashFlowId cashFlowId,
    UserId userId,
    Name name,
    Description description,
    BankAccount bankAccount,
    ZonedDateTime startDate,
    Money initialBalance,
    Money expectedCurrentBalance,
    ZonedDateTime created
) implements CashFlowEvent

// Konfiguracja mapowania
record CategoryMappingConfiguredEvent(
    CashFlowId cashFlowId,
    List<CategoryMapping> mappings,
    ZonedDateTime configuredAt
) implements CashFlowEvent

// Import pojedynczej transakcji historycznej
record HistoricalCashChangeImportedEvent(
    CashFlowId cashFlowId,
    CashChangeId cashChangeId,
    Name name,
    Description description,
    Money money,
    Type type,
    CategoryName categoryName,
    ZonedDateTime dueDate,
    ZonedDateTime paidDate,
    String bankTransactionId,
    ZonedDateTime importedAt
) implements CashFlowEvent

// Dodanie opłaconej transakcji (nie-historycznej)
record PaidCashChangeAppendedEvent(
    CashFlowId cashFlowId,
    CashChangeId cashChangeId,
    Name name,
    Description description,
    Money money,
    Type type,
    CategoryName categoryName,
    ZonedDateTime dueDate,
    ZonedDateTime paidDate,
    ZonedDateTime created
) implements CashFlowEvent

// Rollback importu
record ImportRolledBackEvent(
    CashFlowId cashFlowId,
    int deletedTransactionsCount,
    int deletedCategoriesCount,
    ZonedDateTime rolledBackAt
) implements CashFlowEvent

// Aktywacja CashFlow
record CashFlowActivatedEvent(
    CashFlowId cashFlowId,
    Money confirmedBalance,
    Money calculatedBalance,
    Money adjustmentAmount,        // null jeśli brak korekty
    boolean forceActivated,
    ZonedDateTime activatedAt
) implements CashFlowEvent

// Utworzenie kategorii z mapowania
record CategoryFromMappingCreatedEvent(
    CashFlowId cashFlowId,
    CategoryId categoryId,
    CategoryName name,
    CategoryName parentCategoryName,
    Type type,
    CategoryOrigin origin,
    ZonedDateTime validFrom,
    ZonedDateTime createdAt
) implements CashFlowEvent
```

### Aktualizacja sealed interface

```java
public sealed interface CashFlowEvent extends DomainEvent
    permits
        // istniejące
        CashFlowEvent.CashFlowCreatedEvent,
        CashFlowEvent.MonthAttestedEvent,
        CashFlowEvent.CashChangeAppendedEvent,
        CashFlowEvent.CashChangeConfirmedEvent,
        CashFlowEvent.CashChangeEditedEvent,
        CashFlowEvent.CashChangeRejectedEvent,
        CashFlowEvent.CategoryCreatedEvent,
        CashFlowEvent.BudgetingSetEvent,
        CashFlowEvent.BudgetingUpdatedEvent,
        CashFlowEvent.BudgetingRemovedEvent,
        // nowe
        CashFlowEvent.CashFlowWithHistoryCreatedEvent,
        CashFlowEvent.CategoryMappingConfiguredEvent,
        CashFlowEvent.HistoricalCashChangeImportedEvent,
        CashFlowEvent.PaidCashChangeAppendedEvent,
        CashFlowEvent.ImportRolledBackEvent,
        CashFlowEvent.CashFlowActivatedEvent,
        CashFlowEvent.CategoryFromMappingCreatedEvent
{
    // ...
}
```

---

## Integracja UI Web App

### Architektura integracji

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              WEB APP (React/Vue)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │   Wizard    │  │  Category   │  │   Import    │  │  Activate   │        │
│  │   Setup     │  │   Mapping   │  │   Progress  │  │   Review    │        │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘        │
│         │                │                │                │                │
│         └────────────────┴────────────────┴────────────────┘                │
│                                   │                                         │
│                                   ▼                                         │
│                          ┌───────────────┐                                  │
│                          │  API Client   │                                  │
│                          └───────┬───────┘                                  │
│                                  │                                          │
└──────────────────────────────────┼──────────────────────────────────────────┘
                                   │ REST API
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              BACKEND (Spring Boot)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │ CashFlowRest    │  │ ImportRest      │  │ CategoryRest    │             │
│  │ Controller      │  │ Controller      │  │ Controller      │             │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘             │
│           │                    │                    │                       │
│           └────────────────────┴────────────────────┘                       │
│                                │                                            │
│                                ▼                                            │
│                       ┌─────────────────┐                                   │
│                       │ Command Gateway │                                   │
│                       └─────────────────┘                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### REST API Endpoints

```
POST   /api/v1/cashflows/with-history              # createCashFlowWithHistory
GET    /api/v1/cashflows/{id}                      # getCashFlow (ze statusem)
POST   /api/v1/cashflows/{id}/activate             # activateCashFlow

POST   /api/v1/cashflows/{id}/import/parse         # parseBankData (CSV/JSON)
POST   /api/v1/cashflows/{id}/import/mapping       # configureCategoryMapping
POST   /api/v1/cashflows/{id}/import/execute       # batchImportHistoricalCashChanges
DELETE /api/v1/cashflows/{id}/import               # rollbackImport

GET    /api/v1/cashflows/{id}/categories           # getCategories (z filtrem archived)
GET    /api/v1/cashflows/{id}/import/status        # getImportStatus (progress)
```

### UI Flow - Wizard Setup

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        SETUP WIZARD - Step 1/4                              │
│                        Podstawowe informacje                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Nazwa konta:        [Konto główne ING                    ]                 │
│                                                                             │
│  Bank:               [ING Bank Śląski            ▼]                         │
│                                                                             │
│  Numer konta:        [PL 12 1050 0000 0000 0000 0000 0000 ]                 │
│                                                                             │
│  Waluta:             [PLN ▼]                                                │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📅 Zakres historii do importu:                                             │
│                                                                             │
│  Data początkowa:    [2024-01-01  📅]                                       │
│                      (stan konta na ten dzień)                              │
│                                                                             │
│  Saldo początkowe:   [3 000,00    ] PLN                                     │
│                                                                             │
│  Obecne saldo:       [5 247,83    ] PLN                                     │
│                      (aktualne saldo z banku)                               │
│                                                                             │
│                                                                             │
│                                    [Anuluj]  [Dalej →]                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        SETUP WIZARD - Step 2/4                              │
│                        Import danych z banku                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Wybierz źródło danych:                                                     │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  📄 Plik CSV / MT940                                                │   │
│  │     Wyeksportuj historię z bankowości internetowej                  │   │
│  │                                                                     │   │
│  │     [Wybierz plik...]  wyciag_2024.csv                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  🔗 Połącz z bankiem (PSD2)                  [Wkrótce dostępne]     │   │
│  │     Automatyczny import przez API banku                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📊 Podgląd zaimportowanych danych:                                         │
│                                                                             │
│  Transakcje:    247                                                         │
│  Okres:         2024-01-01 → 2024-12-31                                     │
│  Wpływy:        +96 000,00 PLN                                              │
│  Wydatki:       -93 752,17 PLN                                              │
│  Kategorie:     12 unikalnych                                               │
│                                                                             │
│                                                                             │
│                              [← Wstecz]  [Dalej →]                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        SETUP WIZARD - Step 3/4                              │
│                        Mapowanie kategorii                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Zmapuj kategorie z banku na kategorie w systemie:                          │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ WYDATKI (OUTFLOW)                                                   │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                     │   │
│  │ "Groceries" (45 trans., -3 450 PLN)                                 │   │
│  │ ○ Nowa kategoria: [Jedzenie           ]                             │   │
│  │ ○ Subkategoria:   [        ] / [           ]                        │   │
│  │ ● Bez kategorii (Uncategorized)                                     │   │
│  │                                                                     │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │                                                                     │   │
│  │ "Bills" (24 trans., -12 800 PLN)                                    │   │
│  │ ● Nowa kategoria: [Rachunki           ]                             │   │
│  │ ○ Subkategoria:   [        ] / [           ]                        │   │
│  │ ○ Bez kategorii (Uncategorized)                                     │   │
│  │                                                                     │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │                                                                     │   │
│  │ "Transport" (28 trans., -4 200 PLN)                                 │   │
│  │ ○ Nowa kategoria: [                   ]                             │   │
│  │ ● Subkategoria:   [Samochód  ] / [Paliwo     ]                      │   │
│  │ ○ Bez kategorii (Uncategorized)                                     │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ WPŁYWY (INFLOW)                                                     │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                     │   │
│  │ "Salary" (12 trans., +96 000 PLN)                                   │   │
│  │ ● Nowa kategoria: [Wynagrodzenie      ]                             │   │
│  │ ○ Bez kategorii (Uncategorized)                                     │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                              [← Wstecz]  [Dalej →]                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        SETUP WIZARD - Step 4/4                              │
│                        Podsumowanie i aktywacja                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  📋 PODSUMOWANIE IMPORTU                                                    │
│                                                                             │
│  Konto:              Konto główne ING                                       │
│  Okres:              2024-01-01 → 2024-12-31 (12 miesięcy)                  │
│  Transakcje:         247                                                    │
│  Kategorie:          8 (utworzono nowych)                                   │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  💰 WERYFIKACJA SALDA                                                       │
│                                                                             │
│  Saldo początkowe:           3 000,00 PLN                                   │
│  + Suma wpływów:           +96 000,00 PLN                                   │
│  - Suma wydatków:          -93 752,17 PLN                                   │
│  ────────────────────────────────────────                                   │
│  = Saldo wyliczone:          5 247,83 PLN                                   │
│                                                                             │
│  Saldo potwierdzone:         5 247,83 PLN  ✅ Zgodne!                       │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  ⚠️  Po aktywacji:                                                          │
│  • Dane historyczne zostaną zamknięte (ATTESTED)                            │
│  • Import historyczny nie będzie już możliwy                                │
│  • Kategorie z importu zostaną oznaczone jako archiwalne                    │
│                                                                             │
│                                                                             │
│           [← Wstecz]  [🔄 Resetuj import]  [✅ Aktywuj CashFlow]            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### UI States

```typescript
// Frontend state management
interface SetupWizardState {
  step: 'basic-info' | 'import-data' | 'category-mapping' | 'review';

  // Step 1
  basicInfo: {
    name: string;
    bankName: string;
    accountNumber: string;
    currency: string;
    startDate: Date;
    initialBalance: Money;
    currentBalance: Money;
  };

  // Step 2
  importData: {
    source: 'csv' | 'api' | null;
    file: File | null;
    parseResult: BankDataParseResult | null;
    isLoading: boolean;
    error: string | null;
  };

  // Step 3
  categoryMapping: {
    mappings: CategoryMapping[];
    isValid: boolean;
  };

  // Step 4
  review: {
    summary: ImportSummary;
    balanceCheck: BalanceCheckResult;
    isActivating: boolean;
  };
}

interface CategoryMapping {
  bankCategoryName: string;
  type: 'INFLOW' | 'OUTFLOW';
  transactionCount: number;
  totalAmount: Money;
  action: 'CREATE_NEW' | 'CREATE_SUBCATEGORY' | 'MAP_TO_UNCATEGORIZED';
  targetCategoryName: string;
  parentCategoryName?: string;
}

interface BalanceCheckResult {
  initialBalance: Money;
  totalInflows: Money;
  totalOutflows: Money;
  calculatedBalance: Money;
  confirmedBalance: Money;
  isMatch: boolean;
  difference: Money;
}
```

### API Response Examples

**GET /api/v1/cashflows/{id}**
```json
{
  "cashFlowId": "cf-123",
  "name": "Konto główne ING",
  "status": "SETUP",
  "bankAccount": {
    "bankName": "ING Bank Śląski",
    "accountNumber": "PL12105000000000000000000000",
    "currency": "PLN"
  },
  "startDate": "2024-01-01T00:00:00Z",
  "initialBalance": { "amount": 3000.00, "currency": "PLN" },
  "expectedCurrentBalance": { "amount": 5247.83, "currency": "PLN" },
  "setupProgress": {
    "categoriesMapped": true,
    "transactionsImported": 247,
    "monthsWithData": 12,
    "readyToActivate": true
  }
}
```

**POST /api/v1/cashflows/{id}/import/parse**
```json
// Request
{
  "fileContent": "base64-encoded-csv...",
  "fileType": "CSV",
  "bankFormat": "ING"
}

// Response
{
  "transactions": [...],
  "categories": [
    { "name": "Groceries", "type": "OUTFLOW", "transactionCount": 45, "totalAmount": { "amount": -3450.00, "currency": "PLN" } },
    { "name": "Bills", "type": "OUTFLOW", "transactionCount": 24, "totalAmount": { "amount": -12800.00, "currency": "PLN" } }
  ],
  "summary": {
    "totalTransactions": 247,
    "dateFrom": "2024-01-01",
    "dateTo": "2024-12-31",
    "totalInflows": { "amount": 96000.00, "currency": "PLN" },
    "totalOutflows": { "amount": -93752.17, "currency": "PLN" }
  }
}
```

---

## Killer Features

Funkcjonalności wyróżniające aplikację na tle konkurencji - oparte na danych historycznych i machine learning.

### 1. Insights - inteligentne spostrzeżenia

**Co to jest:**
System automatycznie analizuje dane i generuje kontekstowe spostrzeżenia dopasowane do sytuacji użytkownika.

**Przykłady:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            💡 INSIGHTS                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  🔥 HOT                                                     Grudzień 2025   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Wydatki na Jedzenie wzrosły o 23% w porównaniu do średniej z 2024. │   │
│  │ W grudniu 2024: 2 450 PLN. Teraz: 3 014 PLN.                       │   │
│  │                                                                     │   │
│  │ Czy to świąteczne zakupy? [Tak, ignoruj] [Chcę zmniejszyć]         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  📊 TREND                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Netflix podniósł cenę z 49,99 → 62,99 PLN (+26%).                  │   │
│  │ Rozważ przejście na plan z reklamami lub alternatywę.              │   │
│  │                                                                     │   │
│  │ [Zobacz alternatywy] [OK, zostaje]                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ✅ SUKCES                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Świetnie! Wydatki na Transport spadły 3. miesiąc z rzędu.          │   │
│  │ Oszczędzasz ~320 PLN miesięcznie od przesiadki na rower.           │   │
│  │ Roczna oszczędność: 3 840 PLN 🎉                                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  💰 OKAZJA                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Ubezpieczenie OC wygasa za 45 dni (15 lutego).                     │   │
│  │ W zeszłym roku zapłaciłeś 1 247 PLN. Porównaj oferty!              │   │
│  │                                                                     │   │
│  │ [🔗 Porównaj na mubi.pl] [Przypomnij za 30 dni]                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Typy insightów:**

| Typ | Opis | Trigger |
|-----|------|---------|
| SPENDING_SPIKE | Nagły wzrost wydatków | Kategoria > 120% średniej |
| TREND_UP/DOWN | Długoterminowy trend | 3+ miesiące w tym samym kierunku |
| PRICE_CHANGE | Zmiana ceny subskrypcji | Recurring payment amount changed |
| GOAL_PROGRESS | Postęp w celu | Savings milestone reached |
| BUDGET_WARNING | Przekroczenie budżetu | Budget > 80% wykorzystania |
| OPPORTUNITY | Okazja do oszczędności | Contract renewal approaching |
| ANOMALY | Nietypowa transakcja | Outlier detection |

### 2. Prediction - prognozowanie

**Co to jest:**
Predykcja przyszłych wydatków i sald na podstawie historycznych wzorców.

**UI:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        📈 PROGNOZA FINANSOWA                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  SALDO NA KONIEC MIESIĄCA                                                   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  7000 ┤                                    ╭─── Optymistyczny      │   │
│  │       │                               ╭───╯    6 847 PLN           │   │
│  │  6000 ┤                          ╭───╯                             │   │
│  │       │                     ╭───╯                                  │   │
│  │  5248 ┤ ●══════════════════●═══════════════● Realistyczny          │   │
│  │       │ Dziś                     │           5 412 PLN             │   │
│  │  5000 ┤                          │     ╰───╮                       │   │
│  │       │                          │         ╰───╮                   │   │
│  │  4000 ┤                          │             ╰─── Pesymistyczny  │   │
│  │       │                          │                  4 128 PLN      │   │
│  │       └──────────────────────────┴─────────────────────────────────│   │
│  │         Sty      Lut      Mar     Kwi      Maj      Cze            │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  📊 PRZEWIDYWANE WYDATKI - KWIECIEŃ 2026                                   │
│                                                                             │
│  │ Kategoria      │ Prognoza  │ Pewność │ Bazowane na               │   │
│  │────────────────│───────────│─────────│───────────────────────────│   │
│  │ 🏠 Czynsz      │ 2 100 PLN │ 99%     │ Recurring (stałe)         │   │
│  │ 🍎 Jedzenie    │ 2 650 PLN │ 82%     │ Średnia 6 mies. + sezon   │   │
│  │ 🚗 Transport   │   450 PLN │ 78%     │ Średnia 6 mies.           │   │
│  │ 📺 Subskrypcje │   312 PLN │ 95%     │ Suma aktywnych            │   │
│  │ 🎉 Rozrywka    │   400 PLN │ 45%     │ Wysoka zmienność          │   │
│  │                                                                     │   │
│  │ Σ RAZEM        │ 5 912 PLN │ avg 80% │                           │   │
│                                                                             │
│  ⚠️ Uwaga: W maju 2026 spodziewane ubezpieczenie OC ~1 200 PLN            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Algorytm predykcji:**

```java
public record PredictionResult(
    YearMonth month,
    Money predictedBalance,
    Money optimisticBalance,
    Money pessimisticBalance,
    double confidence,
    List<CategoryPrediction> categoryPredictions
) {}

public record CategoryPrediction(
    CategoryName category,
    Money predictedAmount,
    double confidence,
    PredictionBasis basis
) {}

public enum PredictionBasis {
    RECURRING,           // stałe płatności (czynsz, subskrypcje)
    SEASONAL,            // wzorce sezonowe (grudzień = +20%)
    ROLLING_AVERAGE,     // średnia krocząca
    TREND_EXTRAPOLATION, // ekstrapolacja trendu
    MANUAL_BUDGET        // ręczny budżet użytkownika
}
```

### 3. Anomaly Detection - wykrywanie anomalii

**Co to jest:**
Automatyczne wykrywanie nietypowych transakcji i wzorców.

**UI:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ⚠️ WYKRYTE ANOMALIE                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  🔴 PILNE                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Nieznany odbiorca: "FINTECH LTD CYPRUS"                             │   │
│  │ Kwota: -847,99 PLN | 2026-01-03                                     │   │
│  │                                                                     │   │
│  │ ⚠️ Pierwsza transakcja z tym odbiorcą                               │   │
│  │ ⚠️ Kwota powyżej Twojej typowej pojedynczej transakcji              │   │
│  │                                                                     │   │
│  │ [✅ To ja, OK] [🚨 Nie rozpoznaję - zgłoś do banku]                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  🟡 DO SPRAWDZENIA                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Podwójna płatność: "Allegro"                                        │   │
│  │ 2026-01-02: -149,99 PLN                                             │   │
│  │ 2026-01-02: -149,99 PLN (10 min później)                            │   │
│  │                                                                     │   │
│  │ Możliwe duplikat? [Tak, błąd] [Nie, dwa zamówienia]                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  🟢 INFO                                                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Niezwykle wysoka transakcja w kategorii "Rozrywka":                 │   │
│  │ "Ticketmaster" -1 247,00 PLN (bilety na koncert?)                   │   │
│  │                                                                     │   │
│  │ Typowy zakres: 50-200 PLN | Ta transakcja: 6x więcej                │   │
│  │                                                                     │   │
│  │ [OK, wiem o tym]                                                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Typy anomalii:**

```java
public enum AnomalyType {
    // Transakcyjne
    UNKNOWN_RECIPIENT,       // nieznany odbiorca
    UNUSUAL_AMOUNT,          // kwota > 3σ od średniej
    DUPLICATE_SUSPECTED,     // podejrzenie duplikatu
    UNUSUAL_TIME,            // nietypowa godzina (np. 3:00 AM)
    UNUSUAL_LOCATION,        // inny kraj niż zwykle

    // Wzorcowe
    MISSING_RECURRING,       // brak oczekiwanej płatności cyklicznej
    PATTERN_BREAK,           // nagłe odejście od wzorca
    VELOCITY_SPIKE,          // wiele transakcji w krótkim czasie
    CATEGORY_EXPLOSION       // nagły wzrost w kategorii
}

public record Anomaly(
    AnomalyType type,
    AnomalySeverity severity,  // HIGH, MEDIUM, LOW
    CashChangeId relatedTransaction,
    String description,
    List<String> reasons,
    List<AnomalyAction> suggestedActions
) {}
```

### 4. Smart Budgeting - inteligentne budżetowanie

**Co to jest:**
Automatyczne sugestie budżetów na podstawie historycznych wydatków.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    🎯 SUGEROWANE BUDŻETY NA 2026                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Na podstawie Twoich wydatków z 2024-2025 sugerujemy:                       │
│                                                                             │
│  │ Kategoria      │ Średnia   │ Sugestia  │ Potencjalna oszczędność │     │
│  │────────────────│───────────│───────────│─────────────────────────│     │
│  │ 🍎 Jedzenie    │ 2 847 PLN │ 2 600 PLN │ ~3 000 PLN/rok          │     │
│  │ 🚗 Transport   │   687 PLN │   600 PLN │ ~1 000 PLN/rok          │     │
│  │ 🎉 Rozrywka    │   523 PLN │   450 PLN │   ~900 PLN/rok          │     │
│  │ 📺 Subskrypcje │   312 PLN │   280 PLN │   ~400 PLN/rok          │     │
│  │                                                                     │   │
│  │ Razem potencjalne oszczędności: ~5 300 PLN/rok                      │   │
│                                                                             │
│  [Zastosuj sugerowane] [Dostosuj ręcznie] [Pomiń]                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Analiza konkurencji

### Główni konkurenci

| Aplikacja | Rynek | Model | Mocne strony | Słabe strony |
|-----------|-------|-------|--------------|--------------|
| **Monarch Money** | USA/Global | $99.99/rok | Best-in-class UX, flex budgeting, 13k+ instytucji | Brak wersji free, problemy z sync |
| **Copilot Money** | USA (iOS only) | $95/rok | AI kategoryzacja (90% accuracy), piękny design | Tylko Apple, brak joint accounts |
| **YNAB** | Global | $109/rok | Metodologia zero-based budgeting, edukacja | Drogi, stroma krzywa uczenia |
| **Wallet by BudgetBakers** | EU | Free + Pro €4.99 | PSD2, multi-currency | Ograniczone insights |
| **Spendee** | Global | Free + Pro $2.99 | Ładny UI, współdzielone portfele | Brak PSD2 |
| **Fintonic** | ES/MX | Free | AI scoring, lokalne banki | Tylko Hiszpania/Meksyk |
| **Kontomierz** | PL | Free | Polski, integracje | Przestarzały UI |

### Szczera analiza: Monarch Money i Copilot Money

#### Monarch Money - lider rynku

**Co robią lepiej od nas:**
- **13,000+ instytucji** - integracja z bankami, brokerami, crypto (Coinbase), nawet Zillow dla wyceny nieruchomości
- **Flex Budgeting** - innowacyjne podejście: zamiast 47 kategorii, 3 kubełki i jedna liczba do śledzenia
- **Couples-first** - zaprojektowane od początku dla par z shared expenses
- **Ocena 4.7/5** w App Store - doskonały UX
- **Web + iOS + Android** - pełna dostępność platform
- **Investment tracking** - śledzenie portfela inwestycyjnego w jednym miejscu

**Gdzie mamy szansę:**
- Brak wersji free (my mamy SIMPLE Free)
- Problemy z synchronizacją kont (user reviews: "accounts losing connection, duplicating")
- Brak głębokiej historii (max 1-2 lata vs nasze 5 lat)
- Brak event sourcing / audit trail
- Brak rynku polskiego / PL banków
- Brak consolidated view z agregacją na sektory (my będziemy mieć)
- Brak pełnej historii tradów w portfolio

**Uczciwa ocena:** Monarch to obecnie najlepsza aplikacja personal finance na rynku US. Ich UX jest world-class. Ale z Nordigen (PSD2) + AI kategoryzacją + multi-portfolio z sektorami możemy być konkurencyjni na rynku EU/PL.

#### Copilot Money - AI-first approach

**Co robią lepiej od nas:**
- **AI kategoryzacja 90% accuracy** - machine learning model per user, uczy się z każdą korektą
- **Apple Design Award Finalist 2024** - najpiękniejszy design w branży
- **Native Apple experience** - iPhone, Mac, iPad, Apple Watch
- **Recurring transactions detection** - automatyczne wykrywanie subskrypcji
- **Fraud alerts** - powiadomienia o podejrzanych transakcjach
- **Szybki development** - często nowe features

**Gdzie mamy szansę:**
- **Tylko iOS/Mac** - brak Android, brak web (my: web-first + mobile later)
- **Brak joint accounts** - wymaga 2 subskrypcji dla pary (my: shared accounts w roadmapie)
- **Niewidoczne reguły** - user musi kontaktować support żeby zmienić regułę kategoryzacji
- **Słabsze budżetowanie** - "light on budget vs actual tracking"
- **Tylko US** - brak PL/EU banków (my: Nordigen = cała EU)
- **Brak investment tracking** - tylko basic (my: multi-portfolio + sektory + trade history)

**Uczciwa ocena:** Copilot ma najlepsze AI w branży (90% accuracy). Z LLM-based kategoryzacją możemy osiągnąć podobny poziom. Ich ograniczenie do Apple + US to nasza szansa na rynku EU. Web-first approach + Nordigen + multi-portfolio to kombinacja której oni nie mają.

### Gdzie naprawdę wygrywamy (i gdzie nie)

#### ✅ Nasze rzeczywiste przewagi:

| Przewaga | Dlaczego to ważne | Czy konkurencja to ma? |
|----------|-------------------|------------------------|
| **5 lat historii** | Trendy długoterminowe, sezonowość | Monarch ~2 lata, Copilot ~1 rok |
| **Event sourcing** | Pełny audit trail, korekty bez utraty danych | Nikt |
| **Polski rynek** | PL banki, język, waluta | Tylko Kontomierz (słaby) |
| **Self-hosted option** | Prywatność, kontrola danych | Nikt z głównych graczy |
| **3 scenariusze prognozy** | Optymistyczny/realistyczny/pesymistyczny | Nikt |
| **Business tier** | Compliance, audit, eksport FK | YNAB częściowo |
| **Multi-portfolio + sektory** | Consolidated view, agregacja | Monarch ma basic, reszta nie |
| **Trade history** | Pełna historia transakcji inwestycyjnych | Nikt w pełni |
| **Web-first + EU focus** | Nordigen PSD2, cała Europa | Copilot: tylko US/Apple |

#### ❌ Gdzie jesteśmy z tyłu (szczerze):

| Obszar | My | Konkurencja | Status |
|--------|-----|-------------|--------|
| **AI kategoryzacja** | Brak | Copilot: 90% accuracy | 🔜 Łatwe do dodania - LLM API |
| **Bank integrations** | Brak | Monarch: 13k+, Wallet: PSD2 | 🔜 Nordigen (Gocardless) |
| **Mobile apps** | Brak | Wszyscy mają native apps | TODO |
| **UX/Design** | Funkcjonalny | Monarch/Copilot: world-class | Wymaga pracy |
| **Investment tracking** | Backend ready | Monarch: pełne portfolio | 🔜 CSV/Excel import, multi-portfolio |
| **Couples/shared** | Brak | Monarch: core feature | 🔜 Łatwe rozszerzenie architektury |

#### 🔜 W roadmapie (potwierdzone):

| Funkcja | Opis | Trudność |
|---------|------|----------|
| **Nordigen (GoCardless)** | PSD2 integracja z bankami EU/PL | Średnia - API ready |
| **AI kategoryzacja** | LLM-based, per-user learning | Łatwa - endpoint + prompt |
| **Investment tracking** | Multi-portfolio, CSV/Excel import | Backend istnieje |
| **Consolidated view** | Agregacja portfolios na sektory | Rozszerzenie istniejącego |
| **Trade history** | Monitoring historycznych transakcji | Backend istnieje |
| **Shared accounts** | Multi-user na CashFlow | Łatwe - architektura to wspiera |

#### ⚠️ Rzeczy do przemyślenia:

1. **Czy 5 lat historii to killer feature?**
   - Dla 80% userów prawdopodobnie nie - większość chce "zacząć od teraz"
   - Dla 20% power users - tak, to differentiator
   - Może być bardziej wartościowe dla B2B (compliance, audyt)

2. **Czy event sourcing ma wartość dla end-usera?**
   - Dla zwykłego usera: raczej nie, nie wie co to
   - Dla księgowych/firm: tak, audit trail = compliance
   - Marketing: "nigdy nie tracisz danych" - może działać

3. **Polski rynek - szansa czy ograniczenie?**
   - Plus: brak dobrej konkurencji
   - Minus: mały rynek (~38M ludzi), niższe ceny akceptowalne
   - Strategia: Polska jako test market, potem ekspansja EU

### Nasza pozycja

**Unikalny value proposition:**

1. **Pełna historia** - import do 5 lat wstecz (konkurencja zwykle max 1 rok)
2. **Event sourcing** - pełny audit trail, korekty bez utraty historii
3. **Smart insights** - kontekstowe, actionable spostrzeżenia
4. **Predykcja** - 3 scenariusze (optymistyczny/realistyczny/pesymistyczny)
5. **Prywatność** - dane na własnej infrastrukturze, brak reklam

**Macierz porównawcza (uczciwa):**

| Funkcja | Vidulum | Monarch | Copilot | YNAB | Wallet |
|---------|---------|---------|---------|------|--------|
| **Cena** | 0-299 PLN | $99/rok | $95/rok | $109/rok | €0-60/rok |
| **Free tier** | ✅ | ❌ | ❌ | ❌ | ✅ |
| Historia >2 lata | ✅ 5 lat | ⚠️ ~2 lata | ❌ ~1 rok | ⚠️ | ⚠️ |
| AI kategoryzacja | 🔜 LLM | ⚠️ | ✅ 90% | ❌ | ⚠️ |
| Bank integrations | 🔜 Nordigen | ✅ 13k+ | ✅ US only | ⚠️ | ✅ PSD2 |
| Web app | ✅ | ✅ | ❌ | ✅ | ✅ |
| iOS app | ❌ TODO | ✅ | ✅ | ✅ | ✅ |
| Android app | ❌ TODO | ✅ | ❌ | ✅ | ✅ |
| Couples/shared | 🔜 | ✅ core | ❌ | ⚠️ | ✅ |
| Investment tracking | 🔜 multi-portfolio | ✅ | ⚠️ | ❌ | ⚠️ |
| Consolidated view | 🔜 + sektory | ❌ | ❌ | ❌ | ❌ |
| Trade history | 🔜 | ⚠️ | ❌ | ❌ | ❌ |
| Audit trail | ✅ | ❌ | ❌ | ❌ | ❌ |
| Korekty w historii | ✅ | ❌ | ❌ | ❌ | ❌ |
| 3 scenariusze prognozy | ✅ | ❌ | ❌ | ❌ | ❌ |
| Business/compliance | ✅ | ❌ | ❌ | ⚠️ | ❌ |
| Polski rynek | ✅ | ❌ | ❌ | ❌ | ⚠️ |
| Self-hosted | ✅ | ❌ | ❌ | ❌ | ❌ |
| **UX/Design** | ⚠️ | ✅✅ | ✅✅✅ | ✅ | ✅ |

**Legenda:** ✅ = tak, 🔜 = w roadmapie, ⚠️ = częściowo, ❌ = nie

**Docelowy segment:**
- Świadomi finansowo użytkownicy (25-45 lat)
- Freelancerzy, self-employed
- Małe firmy (1-10 osób)
- Personal finance enthusiasts

---

## Model biznesowy

### Trzy plany cenowe

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         💳 WYBIERZ PLAN                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐        │
│  │                   │ │                   │ │                   │        │
│  │  📱 SIMPLE        │ │  🚀 PRO           │ │  🏢 BUSINESS      │        │
│  │                   │ │                   │ │                   │        │
│  │  Podstawowe       │ │  Pełne możliwości │ │  Dla firm         │        │
│  │  budżetowanie     │ │  + Insights       │ │  + Compliance     │        │
│  │                   │ │                   │ │                   │        │
│  │  0 PLN lub 9 PLN  │ │  29-49 PLN/mies   │ │  149-299 PLN/mies │        │
│  │  /miesiąc         │ │                   │ │                   │        │
│  │                   │ │                   │ │                   │        │
│  │  [Wybierz]        │ │  [Wybierz]        │ │  [Kontakt]        │        │
│  │                   │ │                   │ │                   │        │
│  └───────────────────┘ └───────────────────┘ └───────────────────┘        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Plan SIMPLE (Free / 9 PLN)

**Dla kogo:** Początkujący, testowanie, proste potrzeby

**Zawiera:**
- 1 konto bankowe (CashFlow)
- Quick Start (bez historii)
- Podstawowe kategorie
- Ręczne dodawanie transakcji
- Raporty miesięczne
- 6 miesięcy do przodu

**Ograniczenia:**
- Brak importu historii
- Brak insights
- Brak predykcji
- Brak anomaly detection
- Reklamy (wersja 0 PLN) lub bez reklam (9 PLN)

### Plan PRO (29-49 PLN/mies)

**Dla kogo:** Świadomi użytkownicy, freelancerzy

**Zawiera wszystko z SIMPLE plus:**
- Nielimitowane konta (CashFlows)
- Advanced Setup (import historii)
- Do 5 lat historii
- Pełne insights
- Predykcja 3 scenariusze
- Anomaly detection
- Smart budgeting
- Nieograniczone kategorie
- 24 miesiące do przodu
- Eksport do CSV/Excel
- API dostęp (read-only)
- Brak reklam

**Warianty cenowe:**
- 29 PLN/mies - plan roczny (348 PLN/rok)
- 49 PLN/mies - plan miesięczny

### Plan BUSINESS (149-299 PLN/mies)

**Dla kogo:** Małe firmy, księgowi, doradcy finansowi

**Zawiera wszystko z PRO plus:**
- Multi-user (5-20 użytkowników)
- Role i uprawnienia (Admin, Manager, Viewer)
- Compliance & Audit
  - Pełny log wszystkich zmian
  - Raport korekt
  - Export do audytu
- Eksport FK (Finanse-Księgowość)
  - Format XML dla programów księgowych
  - Mapowanie na plan kont
  - Integracja z popularnymi systemami
- Cash Flow Forecasting dla biznesu
  - Scenariusze "what-if"
  - Runway calculation
  - Break-even analysis
- Dedykowane wsparcie
- SLA 99.9%
- On-premise option

**Warianty cenowe:**
- 149 PLN/mies - do 5 użytkowników
- 299 PLN/mies - do 20 użytkowników
- Custom - enterprise

### Macierz funkcji

| Funkcja | SIMPLE | PRO | BUSINESS |
|---------|--------|-----|----------|
| Liczba CashFlows | 1 | ∞ | ∞ |
| Quick Start | ✅ | ✅ | ✅ |
| Advanced Setup | ❌ | ✅ | ✅ |
| Historia | 0 | 5 lat | 5 lat |
| Prognozy | 6 mies | 24 mies | 36 mies |
| Insights | ❌ | ✅ | ✅ |
| Predykcja | ❌ | ✅ | ✅ (rozszerzona) |
| Anomaly detection | ❌ | ✅ | ✅ |
| Smart budgeting | Podstawowe | ✅ | ✅ |
| Multi-user | ❌ | ❌ | ✅ |
| Roles & permissions | ❌ | ❌ | ✅ |
| Audit trail export | ❌ | ❌ | ✅ |
| Eksport FK | ❌ | ❌ | ✅ |
| API access | ❌ | Read-only | Full |
| Wsparcie | Community | Email | Dedykowane |
| SLA | - | 99% | 99.9% |
| Cena | 0/9 PLN | 29-49 PLN | 149-299 PLN |

### Business Features - szczegóły

#### 1. Compliance & Audit

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RAPORT AUDYTOWY - Q4 2025                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  📋 PODSUMOWANIE ZMIAN                                                      │
│                                                                             │
│  Transakcje utworzone:     247                                              │
│  Transakcje edytowane:      12                                              │
│  Korekty w ATTESTED:         4                                              │
│  Kategorie zmienione:       18                                              │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📝 LOG ZMIAN                                                               │
│                                                                             │
│  │ Timestamp           │ User      │ Action      │ Details              │  │
│  │─────────────────────│───────────│─────────────│──────────────────────│  │
│  │ 2025-12-15 14:23:01 │ jan.k     │ CORRECTION  │ Amount: 100→150 PLN  │  │
│  │ 2025-12-14 09:15:44 │ anna.m    │ APPROVE     │ Invoice #1234        │  │
│  │ 2025-12-13 16:42:11 │ jan.k     │ CREATE      │ New transaction      │  │
│                                                                             │
│  [📥 Export PDF] [📥 Export CSV] [📥 Export do audytora]                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 2. Role i uprawnienia

```java
public enum Role {
    ADMIN,      // Pełny dostęp, zarządzanie użytkownikami
    MANAGER,    // Tworzenie/edycja transakcji, zatwierdzanie
    ACCOUNTANT, // Pełny widok, eksport, korekty
    VIEWER      // Tylko odczyt
}

public enum Permission {
    TRANSACTION_CREATE,
    TRANSACTION_EDIT,
    TRANSACTION_APPROVE,
    CORRECTION_CREATE,
    REPORT_VIEW,
    REPORT_EXPORT,
    SETTINGS_MANAGE,
    USER_MANAGE
}
```

#### 3. Eksport FK (Finanse-Księgowość)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EKSPORT DO SYSTEMU FK                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Format eksportu:  [XML - Symfonia ▼]                                       │
│                                                                             │
│  Okres:            [2025-12-01] → [2025-12-31]                             │
│                                                                             │
│  Mapowanie kategorii → konta FK:                                            │
│                                                                             │
│  │ Kategoria Vidulum │ Konto FK    │ Nazwa konta                      │   │
│  │───────────────────│─────────────│──────────────────────────────────│   │
│  │ Wynagrodzenie     │ 750-01      │ Wynagrodzenia pracowników        │   │
│  │ Czynsz            │ 402-01      │ Usługi obce - najem              │   │
│  │ Media             │ 402-02      │ Usługi obce - media              │   │
│  │ Jedzenie          │ 461-01      │ Koszty reprezentacji             │   │
│                                                                             │
│  [📥 Generuj i pobierz]                                                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 4. Cash Flow Forecasting dla biznesu

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CASH FLOW FORECAST - FIRMA XYZ                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  💰 RUNWAY                                                                  │
│                                                                             │
│  Obecne saldo:           127 450 PLN                                        │
│  Średnie miesięczne burn: -23 000 PLN                                       │
│  Runway:                  5.5 miesiąca (do czerwca 2026)                    │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📊 SCENARIUSZE "WHAT-IF"                                                   │
│                                                                             │
│  Scenariusz 1: Obecny trend                                                 │
│  └─ Runway: 5.5 mies | Break-even: ❌ Nie                                  │
│                                                                             │
│  Scenariusz 2: +20% przychody (nowy klient)                                 │
│  └─ Runway: 9 mies | Break-even: ✅ Sierpień 2026                          │
│                                                                             │
│  Scenariusz 3: -15% koszty (optymalizacja)                                  │
│  └─ Runway: 7 mies | Break-even: ⚠️ Grudzień 2026                          │
│                                                                             │
│  [+ Nowy scenariusz] [Porównaj] [Eksport]                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Projekcja przychodów

**Założenia (rok 1):**
- 1000 użytkowników SIMPLE Free (0 PLN) - marketing/growth
- 500 użytkowników SIMPLE Paid (9 PLN) = 4 500 PLN/mies
- 300 użytkowników PRO (avg 39 PLN) = 11 700 PLN/mies
- 20 firm BUSINESS (avg 199 PLN) = 3 980 PLN/mies

**MRR rok 1:** ~20 000 PLN/mies = 240 000 PLN/rok

**Założenia (rok 3):**
- 10 000 użytkowników SIMPLE Free
- 5 000 użytkowników SIMPLE Paid = 45 000 PLN/mies
- 2 000 użytkowników PRO = 78 000 PLN/mies
- 100 firm BUSINESS = 19 900 PLN/mies

**MRR rok 3:** ~143 000 PLN/mies = 1 716 000 PLN/rok

### Strategia go-to-market

1. **Soft launch** - beta z grupą 100 użytkowników
2. **Product Hunt** - launch PRO
3. **Content marketing** - blog, YouTube (personal finance)
4. **Partnerstwa** - księgowi, doradcy finansowi
5. **Freemium funnel** - SIMPLE Free → SIMPLE Paid → PRO
6. **B2B outreach** - LinkedIn, cold outreach do startupów

---

## Analiza opłacalności: Nordigen + AI

### Słowniczek pojęć biznesowych

| Pojęcie | Wyjaśnienie | Przykład |
|---------|-------------|----------|
| **Churn** | Procent użytkowników którzy rezygnują z subskrypcji w danym okresie (zwykle miesiąc). Im niższy, tym lepiej. | Churn 10%/mies = z 1000 userów, 100 odchodzi każdego miesiąca |
| **LTV (Lifetime Value)** | Ile pieniędzy średnio zarabiamy na jednym użytkowniku przez cały czas jego subskrypcji. | User płaci 39 PLN/mies przez 16 miesięcy = LTV 624 PLN |
| **Konwersja Free → PRO** | Procent darmowych użytkowników którzy przechodzą na płatny plan. | 5% konwersja = z 1000 free userów, 50 kupuje PRO |
| **CAC (Customer Acquisition Cost)** | Ile kosztuje pozyskanie jednego płacącego użytkownika (reklamy, marketing). | Wydaliśmy 5000 PLN na reklamy i zdobyliśmy 100 userów = CAC 50 PLN |
| **LTV/CAC ratio** | Stosunek ile zarabiamy na userze do ile kosztowało jego pozyskanie. Powinno być >3x. | LTV 624 PLN / CAC 50 PLN = 12.5x (bardzo dobrze) |
| **MRR (Monthly Recurring Revenue)** | Miesięczny powtarzalny przychód ze wszystkich subskrypcji. | 1000 userów × 39 PLN = 39 000 PLN MRR |
| **Sticky product** | Produkt z którego trudno zrezygnować bo dane/nawyki są w nim "zablokowane". | Spotify z playlistami, bank z historią - nie chce się migrować |

**Dlaczego te metryki są ważne:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         JAK TO DZIAŁA W PRAKTYCE                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. Wydajesz 50 PLN na reklamę (CAC)                                       │
│                           ↓                                                 │
│  2. User rejestruje się (Free)                                             │
│                           ↓                                                 │
│  3. 5% kupuje PRO (konwersja) → płaci 39 PLN/mies                          │
│                           ↓                                                 │
│  4. Zostaje średnio 16 miesięcy (bo niski churn 5%)                        │
│                           ↓                                                 │
│  5. Zarabiasz 624 PLN (LTV)                                                │
│                           ↓                                                 │
│  6. LTV/CAC = 624/50 = 12.5x → OPŁACALNY BIZNES ✅                         │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  Gdyby churn był 10% (user zostaje tylko 4 mies):                          │
│  LTV = 4 × 39 = 156 PLN                                                    │
│  LTV/CAC = 156/50 = 3.1x → LEDWO OPŁACALNY ⚠️                              │
│                                                                             │
│  Gdyby churn był 20% (user zostaje tylko 2 mies):                          │
│  LTV = 2 × 39 = 78 PLN                                                     │
│  LTV/CAC = 78/50 = 1.6x → NIEOPŁACALNY ❌                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Kluczowy wniosek:** Nordigen (auto-sync) obniża churn, bo user nie musi pamiętać o ręcznym imporcie. Niższy churn = dłuższy czas życia = wyższy LTV = lepszy biznes.

---

## AI Kategoryzacja - Design

### Problem

Ręczne kategoryzowanie transakcji to główny powód frustracji użytkowników:
- Import 500 transakcji = 500 ręcznych decyzji
- "ALLEGRO*SELLER123" - co to? Elektronika? Ubrania? Jedzenie?
- Użytkownik rezygnuje po 2-3 sesjach kategoryzowania

### Rozwiązanie: LLM-based kategoryzacja

Wykorzystujemy Large Language Model (Claude/GPT) do automatycznej kategoryzacji na podstawie:
- Nazwy transakcji z banku
- Kwoty
- Kontekstu (kategorie użytkownika, historia)

### Architektura

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      AI KATEGORYZACJA - FLOW                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐                   │
│  │  Transakcja │     │   Cache     │     │    LLM      │                   │
│  │  z banku    │────▶│   Check     │────▶│   Request   │                   │
│  └─────────────┘     └──────┬──────┘     └──────┬──────┘                   │
│                             │                   │                           │
│        "BIEDRONKA 1234"     │ Hit?              │ Miss?                     │
│        -47.50 PLN           │                   │                           │
│                             ▼                   ▼                           │
│                      ┌─────────────┐     ┌─────────────┐                   │
│                      │  Zwróć      │     │  Prompt +   │                   │
│                      │  z cache    │     │  Kategorie  │                   │
│                      └─────────────┘     └──────┬──────┘                   │
│                             │                   │                           │
│                             │                   ▼                           │
│                             │            ┌─────────────┐                   │
│                             │            │  Response:  │                   │
│                             │            │  "Jedzenie" │                   │
│                             │            │  conf: 0.95 │                   │
│                             │            └──────┬──────┘                   │
│                             │                   │                           │
│                             ▼                   ▼                           │
│                      ┌─────────────────────────────────┐                   │
│                      │       Zapisz do cache           │                   │
│                      │  "BIEDRONKA*" → "Jedzenie"      │                   │
│                      └─────────────────────────────────┘                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Struktura danych

```java
// Request do kategoryzacji
public record CategorizationRequest(
    String transactionName,        // "BIEDRONKA WARSZAWA 1234"
    Money amount,                  // -47.50 PLN
    Type type,                     // OUTFLOW
    List<String> userCategories,   // ["Jedzenie", "Transport", "Dom", ...]
    List<RecentTransaction> history // ostatnie 10 podobnych transakcji
) {}

// Response z kategoryzacji
public record CategorizationResult(
    String suggestedCategory,      // "Jedzenie"
    double confidence,             // 0.95
    String reasoning,              // "Biedronka to sieć sklepów spożywczych"
    boolean needsUserConfirmation  // false (confidence > 0.8)
) {}

// Cache entry
public record CategoryRule(
    String pattern,                // "BIEDRONKA*"
    String category,               // "Jedzenie"
    RuleSource source,             // AI_GENERATED, USER_CONFIRMED, USER_CREATED
    int usageCount,                // 47
    double accuracy,               // 0.98 (ile razy user się zgodził)
    ZonedDateTime createdAt,
    ZonedDateTime lastUsedAt
) {}

public enum RuleSource {
    AI_GENERATED,      // LLM zaproponował
    USER_CONFIRMED,    // User potwierdził sugestię AI
    USER_CREATED,      // User ręcznie utworzył regułę
    USER_CORRECTED     // User poprawił błąd AI
}
```

### Prompt Engineering

```java
public class CategorizationPromptBuilder {

    public String buildPrompt(CategorizationRequest request) {
        return """
            Jesteś asystentem do kategoryzacji transakcji bankowych.

            TRANSAKCJA DO SKATEGORYZOWANIA:
            Nazwa: %s
            Kwota: %s
            Typ: %s

            DOSTĘPNE KATEGORIE UŻYTKOWNIKA:
            %s

            HISTORIA PODOBNYCH TRANSAKCJI (dla kontekstu):
            %s

            ZASADY:
            1. Wybierz JEDNĄ kategorię z listy powyżej
            2. Jeśli żadna nie pasuje, zaproponuj "Uncategorized"
            3. Odpowiedz w formacie JSON:
               {"category": "nazwa", "confidence": 0.0-1.0, "reasoning": "krótkie uzasadnienie"}

            PRZYKŁADY:
            - "ORLEN WARSZAWA" → Transport (stacja paliw)
            - "NETFLIX.COM" → Rozrywka (streaming)
            - "ZUS SKLADKA" → Podatki (składki ubezpieczeniowe)
            - "ALLEGRO*SELLER" → wymaga kontekstu (może być cokolwiek)

            Odpowiedz TYLKO JSON, bez dodatkowego tekstu.
            """.formatted(
                request.transactionName(),
                request.amount(),
                request.type(),
                formatCategories(request.userCategories()),
                formatHistory(request.history())
            );
    }
}
```

### Cache i uczenie się

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      SYSTEM UCZENIA SIĘ                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  POZIOM 1: Pattern Cache (per user)                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  "BIEDRONKA*"      → Jedzenie      (47 użyć, 100% accuracy)        │   │
│  │  "ORLEN*"          → Transport     (23 użycia, 96% accuracy)        │   │
│  │  "NETFLIX*"        → Rozrywka      (12 użyć, 100% accuracy)         │   │
│  │  "ALLEGRO*"        → ???           (nie cachujemy - zbyt różnorodne)│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  POZIOM 2: Global Knowledge (wszystkich userów, anonimowe)                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  "ŻABKA*"          → Jedzenie      (1247 userów, 99.2% accuracy)   │   │
│  │  "UBER*TRIP*"      → Transport     (892 userów, 98.7% accuracy)     │   │
│  │  "SPOTIFY*"        → Rozrywka      (2341 userów, 99.9% accuracy)    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  FLOW:                                                                      │
│  1. Sprawdź user cache (pattern match)                                     │
│  2. Sprawdź global knowledge                                               │
│  3. Jeśli brak → wywołaj LLM                                               │
│  4. Zapisz wynik do user cache                                             │
│  5. Jeśli user potwierdzi → zwiększ accuracy, dodaj do global             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### User feedback loop

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      NOWA TRANSAKCJA                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  BIEDRONKA WARSZAWA UL. MARSZALKOWSKA           -47.50 PLN                 │
│  2026-01-06                                                                 │
│                                                                             │
│  Sugerowana kategoria: 🍎 Jedzenie                          confidence: 95% │
│                                                                             │
│  [✓ Zgadzam się]  [✎ Zmień kategorię]                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Jeśli user kliknie "Zmień kategorię":

┌─────────────────────────────────────────────────────────────────────────────┐
│                      ZMIEŃ KATEGORIĘ                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  BIEDRONKA WARSZAWA UL. MARSZALKOWSKA           -47.50 PLN                 │
│                                                                             │
│  Wybierz kategorię:                                                         │
│  ○ 🍎 Jedzenie (sugestia AI)                                               │
│  ○ 🏠 Dom                                                                   │
│  ○ 🚗 Transport                                                             │
│  ● 🎁 Prezenty        ← user wybrał                                        │
│  ○ ...                                                                      │
│                                                                             │
│  ☑️ Zapamiętaj dla przyszłych transakcji "BIEDRONKA*"                      │
│                                                                             │
│  [Anuluj]  [Zapisz]                                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Batch kategoryzacja (import)

Przy imporcie historycznym mamy setki transakcji. Optymalizacja:

```java
public class BatchCategorizationService {

    public List<CategorizationResult> categorizeBatch(List<Transaction> transactions) {
        // 1. Grupuj transakcje po podobnych nazwach
        Map<String, List<Transaction>> grouped = groupByMerchant(transactions);

        // 2. Dla każdej grupy - jedno wywołanie LLM
        // "BIEDRONKA*" (15 transakcji) → 1 request
        // "ORLEN*" (8 transakcji) → 1 request
        // Zamiast 500 requestów → ~50 requestów

        // 3. Zastosuj wynik do wszystkich w grupie
        return applyGroupResults(grouped, llmResults);
    }

    private Map<String, List<Transaction>> groupByMerchant(List<Transaction> txns) {
        // "BIEDRONKA WARSZAWA 123" → "BIEDRONKA*"
        // "BIEDRONKA KRAKOW 456" → "BIEDRONKA*"
        // Grupujemy po pierwszym słowie lub znanym patternie
    }
}
```

**Optymalizacja kosztów:**

| Scenariusz | Transakcje | LLM Requests | Koszt (~$0.01/req) |
|------------|------------|--------------|---------------------|
| Bez grupowania | 500 | 500 | $5.00 |
| Z grupowaniem | 500 | ~50 | $0.50 |
| Z cache (kolejny miesiąc) | 500 | ~10 (nowi merchants) | $0.10 |

### API Endpoint

```java
@RestController
@RequestMapping("/api/v1/cashflows/{cashFlowId}/categorization")
public class CategorizationController {

    @PostMapping("/suggest")
    public CategorizationResult suggestCategory(
            @PathVariable String cashFlowId,
            @RequestBody CategorizationRequest request) {
        // Pojedyncza transakcja - real-time
    }

    @PostMapping("/batch")
    public BatchCategorizationResult categorizeBatch(
            @PathVariable String cashFlowId,
            @RequestBody List<Transaction> transactions) {
        // Import - batch processing
    }

    @PostMapping("/feedback")
    public void provideFeedback(
            @PathVariable String cashFlowId,
            @RequestBody CategorizationFeedback feedback) {
        // User potwierdził/poprawił - uczenie się
    }

    @GetMapping("/rules")
    public List<CategoryRule> getUserRules(
            @PathVariable String cashFlowId) {
        // Lista reguł użytkownika (do edycji)
    }

    @DeleteMapping("/rules/{ruleId}")
    public void deleteRule(
            @PathVariable String cashFlowId,
            @PathVariable String ruleId) {
        // Usuń regułę (Copilot tego nie ma!)
    }
}
```

### Przewaga nad Copilot

| Aspekt | Copilot | Vidulum |
|--------|---------|---------|
| Widoczność reguł | ❌ Niewidoczne | ✅ Pełna lista, edycja, usuwanie |
| Edycja reguł | ❌ Trzeba pisać do supportu | ✅ Self-service |
| Wyjaśnienie decyzji | ❌ Brak | ✅ "reasoning" w response |
| Confidence score | ❌ Brak | ✅ User widzi pewność AI |
| Bulk actions | ❌ Brak | ✅ "Zmień wszystkie ALLEGRO* na..." |

### Koszty

**Claude API (przykładowe):**
- Input: $3 / 1M tokens
- Output: $15 / 1M tokens
- Średni request: ~500 tokens in, ~50 tokens out
- Koszt per request: ~$0.002

**Miesięczny koszt per user:**

| Użycie | Requests | Koszt |
|--------|----------|-------|
| Light (50 txn/mies, 80% cache hit) | 10 | $0.02 |
| Medium (200 txn/mies, 70% cache hit) | 60 | $0.12 |
| Heavy (500 txn/mies, 60% cache hit) | 200 | $0.40 |

**Przy 1000 userów PRO:** ~$50-200/mies = 200-800 PLN/mies

### Implementacja - fazy

**Faza 1 (MVP):**
- [ ] Pojedynczy endpoint `/suggest`
- [ ] Prosty prompt bez historii
- [ ] User cache per cashflow
- [ ] UI: sugestia + potwierdź/zmień

**Faza 2 (Learning):**
- [ ] Feedback loop - uczenie z poprawek
- [ ] Batch kategoryzacja dla importu
- [ ] Grupowanie transakcji (optymalizacja kosztów)
- [ ] Global knowledge base

**Faza 3 (Advanced):**
- [ ] Reguły widoczne i edytowalne
- [ ] Bulk actions ("wszystkie X → kategoria Y")
- [ ] Confidence threshold (auto-accept > 0.9)
- [ ] Anomaly detection ("ta transakcja wygląda inaczej niż zwykle")

---

## Przykład: Quick Start + AI kategoryzacja (user journey)

### Kontekst

Ania właśnie zainstalowała Vidulum. Chce szybko zacząć śledzić wydatki bez importowania historii.

---

### Krok 1: Quick Start - utworzenie CashFlow

**Co widzi Ania:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         WITAJ W VIDULUM!                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Jak chcesz zacząć?                                                         │
│                                                                             │
│  ┌─────────────────────────────┐  ┌─────────────────────────────┐          │
│  │  ⚡ SZYBKI START            │  │  🔧 IMPORT Z BANKU          │          │
│  │                             │  │                             │          │
│  │  Zacznij od zera            │  │  Wgraj historię transakcji  │          │
│  │  ~2 minuty                  │  │  ~15 minut                  │          │
│  │                             │  │                             │          │
│  │  [Wybieram]  ← Ania klika   │  │  [Wybieram]                 │          │
│  └─────────────────────────────┘  └─────────────────────────────┘          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Co robi Ania:**
1. Wpisuje nazwę: "Moje ING"
2. Wybiera walutę: PLN
3. Wpisuje obecne saldo: 4 250 PLN
4. Klika "Utwórz"

**Co się dzieje w systemie:**
- Tworzony jest CashFlow w statusie OPEN (nie SETUP, bo Quick Start)
- Bieżący miesiąc (styczeń 2026) = ACTIVE
- Następne 11 miesięcy = FORECASTED
- Domyślna kategoria "Uncategorized" dla inflow i outflow
- Saldo początkowe = 4 250 PLN

**Co widzi Ania:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  MOJE ING                                           Saldo: 4 250,00 PLN    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  STYCZEŃ 2026                                                    [ACTIVE]  │
│                                                                             │
│  Wpływy:        0,00 PLN                                                   │
│  Wydatki:       0,00 PLN                                                   │
│  Transakcje:    0                                                          │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  Brak transakcji. Dodaj pierwszą!                                          │
│                                                                             │
│                    [+ Dodaj wydatek]  [+ Dodaj wpływ]                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Krok 2: Ania dodaje pierwszą transakcję

**Co robi Ania:**
Klika "Dodaj wydatek" i wpisuje:
- Nazwa: "Biedronka zakupy"
- Kwota: 127,50 PLN
- Data: dzisiaj

**Co widzi Ania (przed zapisaniem):**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         NOWY WYDATEK                                   [X]  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Nazwa:    [Biedronka zakupy                    ]                          │
│  Kwota:    [127,50    ] PLN                                                │
│  Data:     [2026-01-06  📅]                                                │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  🤖 Sugerowana kategoria:                                                   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  🍎 Jedzenie                                         pewność: 94%   │   │
│  │                                                                     │   │
│  │  "Biedronka to sieć sklepów spożywczych w Polsce"                  │   │
│  │                                                                     │   │
│  │  [✓ Użyj tej kategorii]  [✎ Wybierz inną]                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  💡 Nie masz jeszcze kategorii "Jedzenie". Utworzymy ją automatycznie.     │
│                                                                             │
│                                              [Anuluj]  [💾 Zapisz]         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Co się stało w tle:**
1. Gdy Ania wpisała "Biedronka zakupy", system wysłał request do AI
2. AI rozpoznał "Biedronka" i zasugerował kategorię "Jedzenie" z 94% pewnością
3. System sprawdził, że Ania nie ma jeszcze takiej kategorii - zaproponuje utworzenie

**Co robi Ania:**
Klika "Użyj tej kategorii", potem "Zapisz"

**Co się dzieje w systemie:**
1. Tworzona jest nowa kategoria "Jedzenie" (OUTFLOW, origin: AI_SUGGESTED)
2. Tworzony jest CashChange:
   - Status: PENDING (bo to przyszły wydatek, jeszcze nie zapłacony)
   - Kategoria: Jedzenie
   - Kwota: -127,50 PLN
3. Zapisywana jest reguła AI: "BIEDRONKA*" → "Jedzenie"
4. Aktualizowane są statystyki miesiąca:
   - Wydatki expected: +127,50 PLN

**Co widzi Ania:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  MOJE ING                                           Saldo: 4 250,00 PLN    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  STYCZEŃ 2026                                                    [ACTIVE]  │
│                                                                             │
│  Wpływy:        0,00 PLN                                                   │
│  Wydatki:       127,50 PLN (oczekiwane)                                    │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📋 TRANSAKCJE                                                              │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ⏳ Biedronka zakupy              -127,50 PLN   🍎 Jedzenie        │   │
│  │     2026-01-06                    [OCZEKUJE]                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                    [+ Dodaj wydatek]  [+ Dodaj wpływ]                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Krok 3: Ania dodaje więcej transakcji - AI się uczy

**Ania dodaje kolejne transakcje przez tydzień:**

| Nazwa wpisana | AI sugestia | Pewność | Akcja Ani | Wynik |
|---------------|-------------|---------|-----------|-------|
| "Orlen paliwo" | 🚗 Transport | 96% | ✓ Akceptuje | Nowa kategoria "Transport" |
| "Netflix" | 📺 Rozrywka | 98% | ✓ Akceptuje | Nowa kategoria "Rozrywka" |
| "Żabka" | 🍎 Jedzenie | 92% | ✓ Akceptuje | Użyje istniejącej |
| "Allegro laptop" | 📦 Zakupy | 67% | ✎ Zmienia na "Elektronika" | Nowa kategoria "Elektronika" |
| "Allegro ubrania" | 💻 Elektronika | 71% | ✎ Zmienia na "Ubrania" | Nowa kategoria "Ubrania" |
| "Lidl" | 🍎 Jedzenie | 95% | ✓ Akceptuje | Cache hit |
| "Biedronka" | 🍎 Jedzenie | 99% | Auto-accept | Cache hit (reguła z kroku 2) |

**Co się nauczył system:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      REGUŁY KATEGORYZACJI (Ania)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Pattern          │ Kategoria    │ Źródło          │ Użycia │ Trafność    │
│  ─────────────────│──────────────│─────────────────│────────│─────────────│
│  BIEDRONKA*       │ Jedzenie     │ AI + potwierdz. │ 3      │ 100%        │
│  ORLEN*           │ Transport    │ AI + potwierdz. │ 1      │ 100%        │
│  NETFLIX*         │ Rozrywka     │ AI + potwierdz. │ 1      │ 100%        │
│  ŻABKA*           │ Jedzenie     │ AI + potwierdz. │ 1      │ 100%        │
│  LIDL*            │ Jedzenie     │ AI + potwierdz. │ 1      │ 100%        │
│  ALLEGRO*         │ (brak)       │ -               │ -      │ zbyt różne  │
│                                                                             │
│  💡 "Allegro" nie ma reguły - zbyt różnorodne zakupy                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Kluczowe obserwacje:**
- "Biedronka" - następnym razem auto-accept (99% pewność z cache)
- "Allegro" - AI nie tworzy reguły, bo Ania poprawiła 2 razy na różne kategorie
- System rozumie, że Allegro to marketplace z różnymi produktami

---

### Krok 4: Ania potwierdza płatność

Następnego dnia Ania zapłaciła za zakupy w Biedronce.

**Co widzi Ania:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  📋 TRANSAKCJE                                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ⏳ Biedronka zakupy              -127,50 PLN   🍎 Jedzenie        │   │
│  │     2026-01-06                    [OCZEKUJE]                        │   │
│  │                                                                     │   │
│  │     [✓ Zapłacone]  [✎ Edytuj]  [🗑 Usuń]                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Ania klika "Zapłacone"**

**Co się dzieje w systemie:**
1. CashChange zmienia status: PENDING → CONFIRMED
2. Aktualizowane są statystyki:
   - Wydatki expected: -127,50 PLN
   - Wydatki actual: +127,50 PLN
3. Saldo prognozowane na koniec miesiąca się aktualizuje

**Co widzi Ania:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  MOJE ING                                           Saldo: 4 122,50 PLN    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  STYCZEŃ 2026                                                    [ACTIVE]  │
│                                                                             │
│  Wpływy:        0,00 PLN                                                   │
│  Wydatki:       127,50 PLN (zapłacone)                                     │
│                 + 847,00 PLN (oczekujące)                                  │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📋 TRANSAKCJE                                                              │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ✅ Biedronka zakupy              -127,50 PLN   🍎 Jedzenie        │   │
│  │     2026-01-06                    [ZAPŁACONE]                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ⏳ Netflix                        -52,99 PLN   📺 Rozrywka        │   │
│  │     2026-01-15                    [OCZEKUJE]                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ... więcej transakcji ...                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Krok 5: Szybkie dodawanie - AI auto-accept

Po miesiącu używania, Ania ma już wiele reguł. Teraz dodawanie jest błyskawiczne.

**Ania wpisuje:** "Biedronka"

**Co widzi Ania (natychmiast, bez czekania na AI):**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         NOWY WYDATEK                                   [X]  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Nazwa:    [Biedronka                           ]                          │
│  Kwota:    [          ] PLN                                                │
│  Data:     [2026-02-03  📅]                                                │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  ⚡ Automatycznie przypisano:                                               │
│                                                                             │
│  🍎 Jedzenie                                              [✎ Zmień]        │
│                                                                             │
│  (na podstawie 15 poprzednich transakcji "Biedronka")                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Różnica:**
- Krok 2 (pierwszy raz): AI request, 94% pewność, "Sugerowana kategoria"
- Krok 5 (po miesiącu): Cache hit, 99% pewność, "Automatycznie przypisano"
- Brak opóźnienia, brak kosztów AI

---

### Krok 6: Ania przegląda swoje kategorie

**Co widzi Ania w ustawieniach:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MOJE KATEGORIE                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  WYDATKI (OUTFLOW)                                                          │
│                                                                             │
│  │ Kategoria     │ Transakcji │ Suma       │ Reguły AI              │     │
│  │───────────────│────────────│────────────│────────────────────────│     │
│  │ 🍎 Jedzenie   │ 23         │ -1 847 PLN │ BIEDRONKA*, ŻABKA*,    │     │
│  │               │            │            │ LIDL*, CARREFOUR*      │     │
│  │ 🚗 Transport  │ 8          │ -520 PLN   │ ORLEN*, BP*, UBER*     │     │
│  │ 📺 Rozrywka   │ 3          │ -159 PLN   │ NETFLIX*, SPOTIFY*     │     │
│  │ 💻 Elektronika│ 2          │ -2 340 PLN │ (brak - różne sklepy)  │     │
│  │ 👕 Ubrania    │ 4          │ -680 PLN   │ RESERVED*, HM*         │     │
│  │ 📦 Uncateg.   │ 5          │ -234 PLN   │ -                      │     │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  WPŁYWY (INFLOW)                                                            │
│                                                                             │
│  │ Kategoria     │ Transakcji │ Suma       │ Reguły AI              │     │
│  │───────────────│────────────│────────────│────────────────────────│     │
│  │ 💰 Wynagrodzenie│ 1        │ +8 500 PLN │ FIRMA XYZ*             │     │
│  │ 📦 Uncateg.   │ 2          │ +350 PLN   │ -                      │     │
│                                                                             │
│                                              [+ Nowa kategoria]            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Krok 7: Ania edytuje reguły AI

Ania zauważyła, że "UBER*" ląduje w Transport, ale chce rozdzielić Uber Eats od Uber przejazdów.

**Co robi Ania:**
Klika na kategorię "Transport" → "Zarządzaj regułami"

**Co widzi Ania:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   REGUŁY KATEGORYZACJI: 🚗 Transport                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Pattern          │ Trafność │ Użycia │ Źródło          │ Akcje           │
│  ─────────────────│──────────│────────│─────────────────│─────────────────│
│  ORLEN*           │ 100%     │ 5      │ AI + Ty         │ [✎] [🗑]        │
│  BP*              │ 100%     │ 2      │ AI + Ty         │ [✎] [🗑]        │
│  UBER*            │ 75%      │ 4      │ AI + Ty         │ [✎] [🗑]   ⚠️  │
│                                                                             │
│  ⚠️ "UBER*" ma niską trafność - może obejmować różne typy zakupów          │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  [+ Dodaj nową regułę]                                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Ania klika "✎" przy UBER* i widzi:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EDYTUJ REGUŁĘ                                  [X]  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Pattern:    [UBER*                    ]                                   │
│  Kategoria:  [🚗 Transport ▼]                                              │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  💡 Sugestia: Rozdziel na bardziej szczegółowe reguły:                      │
│                                                                             │
│  ○ UBER*EATS*    → 🍎 Jedzenie                                             │
│  ○ UBER*TRIP*    → 🚗 Transport                                            │
│                                                                             │
│  [Zastosuj sugestię]                                                        │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  Ostatnie transakcje pasujące do "UBER*":                                   │
│                                                                             │
│  • UBER EATS WARSZAWA        -47,00 PLN   → teraz: Transport               │
│  • UBER TRIP 1234            -23,50 PLN   → teraz: Transport ✓             │
│  • UBER EATS MCDONALDS       -35,00 PLN   → teraz: Transport               │
│  • UBER TRIP 5678            -31,00 PLN   → teraz: Transport ✓             │
│                                                                             │
│                                    [Anuluj]  [Usuń regułę]  [Zapisz]       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Ania klika "Zastosuj sugestię"**

**Co się dzieje:**
1. Reguła "UBER*" zostaje usunięta
2. Tworzone są dwie nowe reguły:
   - "UBER*EATS*" → Jedzenie
   - "UBER*TRIP*" → Transport
3. Historyczne transakcje "UBER EATS*" zostają przekategoryzowane na "Jedzenie"
4. Statystyki kategorii się aktualizują

---

### Podsumowanie: Wpływ na CashFlow

Po miesiącu używania Quick Start + AI:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         STYCZEŃ 2026 - PODSUMOWANIE                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Saldo początkowe:     4 250,00 PLN                                        │
│  Wpływy:              +8 850,00 PLN                                        │
│  Wydatki:             -5 780,00 PLN                                        │
│  Saldo końcowe:        7 320,00 PLN                                        │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  WYDATKI PO KATEGORIACH                                                     │
│                                                                             │
│  🍎 Jedzenie        ████████████████████          32%    -1 847 PLN        │
│  💻 Elektronika     ████████████████              28%    -2 340 PLN        │
│  👕 Ubrania         ████████                      12%      -680 PLN        │
│  🚗 Transport       ██████                         9%      -520 PLN        │
│  📺 Rozrywka        ███                            3%      -159 PLN        │
│  📦 Inne            ████                           4%      -234 PLN        │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📊 STATYSTYKI AI                                                           │
│                                                                             │
│  Transakcji ogółem:      45                                                │
│  Auto-kategoryzowane:    38 (84%)                                          │
│  Poprawione przez Ciebie: 7 (16%)                                          │
│  Utworzone reguły:       12                                                │
│  Requests do AI:         15 (oszczędność: 30 dzięki cache)                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Kluczowe metryki:**
- **84% auto-kategoryzacja** - Ania poprawiła tylko 7 z 45 transakcji
- **12 reguł** - system nauczył się preferencji Ani
- **Oszczędność kosztów** - 15 requestów AI zamiast 45 (66% mniej)
- **Czas** - po pierwszym tygodniu, dodawanie transakcji zajmuje sekundy

---

### Koszty Nordigen (GoCardless)

Nordigen oferuje PSD2 API do łączenia z bankami w EU:

| Plan | Limit | Cena | Use case |
|------|-------|------|----------|
| Free | 10 połączeń/mies | €0 | Development, testy |
| Premium | Unlimited | ~€0.10-0.30/połączenie/mies | Produkcja |

**Kalkulacja kosztów dla różnych skal:**

| Userzy PRO | Koszt Nordigen/mies | Przychód PRO/mies | Margin |
|------------|---------------------|-------------------|--------|
| 100 | ~€10-30 (43-130 PLN) | 3 900 PLN | 97% |
| 1 000 | ~€100-300 (430-1300 PLN) | 39 000 PLN | 97% |
| 10 000 | ~€1000-3000 (4300-13000 PLN) | 390 000 PLN | 97% |

**Wniosek:** Koszt Nordigen to ~1-3% przychodu - marginalny wpływ na margin.

### Wpływ na Unit Economics

#### Scenariusz PRZED (ręczny import CSV)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    UNIT ECONOMICS - PRZED NORDIGEN                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Konwersja Free → PRO:     2-3%                                            │
│  Powód: "Muszę ręcznie importować? Nie, dziękuję..."                       │
│                                                                             │
│  Monthly Churn PRO:        8-10%                                           │
│  Powód: "Zapomniałem importować 2 miesiące, dane nieaktualne, rezygnuję"  │
│                                                                             │
│  Średni czas życia:        4-5 miesięcy                                    │
│                                                                             │
│  LTV PRO:                  4.5 × 39 PLN = 175 PLN                          │
│                                                                             │
│  CAC (założenie):          ~50-100 PLN                                     │
│                                                                             │
│  LTV/CAC ratio:            1.75-3.5x (słabe/akceptowalne)                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Scenariusz PO (Nordigen + AI kategoryzacja)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    UNIT ECONOMICS - PO NORDIGEN + AI                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Konwersja Free → PRO:     5-8%                                            │
│  Powód: "Łączy się z moim bankiem automatycznie? Biorę!"                   │
│                                                                             │
│  Monthly Churn PRO:        3-5%                                            │
│  Powód: Dane aktualizują się same → sticky product                         │
│                                                                             │
│  Średni czas życia:        12-20 miesięcy                                  │
│                                                                             │
│  LTV PRO:                  16 × 39 PLN = 624 PLN                           │
│                                                                             │
│  CAC (założenie):          ~50-100 PLN                                     │
│                                                                             │
│  LTV/CAC ratio:            6-12x (bardzo dobre)                            │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  📈 WZROST LTV: 3.5x (175 PLN → 624 PLN)                                   │
│  📈 WZROST KONWERSJI: 2-3x                                                 │
│  📉 SPADEK CHURN: 2-3x                                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Dlaczego auto-sync zmienia wszystko

| Czynnik | Ręczny import | Auto-sync (Nordigen) |
|---------|---------------|----------------------|
| **Onboarding** | 15-30 min (eksport CSV, upload) | 2-3 min (wybierz bank, zaloguj) |
| **Codzienna praca** | Pamiętaj o imporcie co tydzień | Zero effort - dane same się aktualizują |
| **Dokładność** | Zależy od usera | 100% - dane z banku |
| **Friction** | Wysoki | Minimalny |
| **Retencja** | Niska (zapomniał = porzucił) | Wysoka (sticky) |

**Główny powód porzucania aplikacji budżetowych:**
> "Zapomniałem aktualizować dane przez miesiąc, teraz się nie chce nadrabiać, rezygnuję"

Auto-sync eliminuje ten problem całkowicie.

### Wpływ poszczególnych funkcji na konwersję

| Funkcja | Wpływ na konwersję Free→PRO | Wpływ na retencję |
|---------|----------------------------|-------------------|
| **Nordigen (auto-sync)** | +++ krytyczny | +++ krytyczny |
| **AI kategoryzacja** | ++ wysoki | ++ wysoki |
| **Multi-portfolio** | + średni (power users) | ++ wysoki |
| **Shared accounts** | ++ wysoki (couples = 2x userów) | ++ wysoki |
| **5 lat historii** | + średni | + średni |
| **Insights/Predictions** | ++ wysoki | ++ wysoki |

### Ryzyka integracji Nordigen

| Ryzyko | Prawdopodobieństwo | Wpływ | Mitygacja |
|--------|-------------------|-------|-----------|
| Nordigen podniesie ceny | Średnie | Margin ↓ | Alternatywy: Tink, Plaid EU |
| PSD2 wymaga compliance | Wysokie | Koszty prawne | Konsultacja prawna przed launch |
| Userzy nie ufają łączeniu | Średnie | Niższa adopcja | Edukacja, security badges |
| Banki blokują API | Niskie | Frustracja | Fallback na CSV import |
| Awarie Nordigen | Niskie | Brak danych | Cache + graceful degradation |

### ROI integracji Nordigen + AI

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ROI CALCULATION                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  KOSZTY JEDNORAZOWE:                                                        │
│  ├─ Development Nordigen:        2-3 tygodnie × 15k PLN = 30-45k PLN       │
│  ├─ Development AI kategoryzacji: 1 tydzień × 15k PLN = 15k PLN            │
│  ├─ Testy, QA, dokumentacja:     1 tydzień = 15k PLN                       │
│  └─ RAZEM:                       60-75k PLN                                │
│                                                                             │
│  KOSZTY OPERACYJNE (miesięcznie przy 1000 PRO users):                       │
│  ├─ Nordigen API:                ~1 000 PLN                                │
│  ├─ LLM API (kategoryzacja):     ~500 PLN (Claude/GPT)                     │
│  └─ RAZEM:                       ~1 500 PLN/mies                           │
│                                                                             │
│  WZROST PRZYCHODÓW:                                                         │
│  ├─ Konwersja 2x:                +39 000 PLN/mies (1000 → 2000 PRO)        │
│  ├─ Retencja 2x:                 +19 500 PLN/mies (dłuższy LTV)            │
│  └─ RAZEM:                       +58 500 PLN/mies                          │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  NET BENEFIT:                    +57 000 PLN/mies                          │
│  PAYBACK PERIOD:                 ~1.3 miesiąca                             │
│  ROCZNY ROI:                     ~900%                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Wnioski

**Nordigen + AI kategoryzacja = konieczność, nie opcja.**

**Bez tych funkcji:**
- Zostajemy niszową aplikacją dla ~5% power users którzy lubią CSV
- Konkurujemy z Excelem, nie z Monarch/Copilot
- Churn 8-10%/mies = większość userów odchodzi w ciągu pół roku
- LTV ~175 PLN - słaby business case

**Z tymi funkcjami:**
- Możemy realnie konkurować na rynku EU
- Sticky product - dane auto-sync = user nie chce migrować
- Churn 3-5%/mies = zdrowy SaaS
- LTV ~624 PLN - solidny business case
- Realne szanse na 5-10% rynku PL personal finance

**Priorytet implementacji:**
1. 🔴 **Nordigen** - highest impact, eliminuje główną barierę
2. 🔴 **AI kategoryzacja** - drugi highest impact
3. 🟡 **Shared accounts** - couples market
4. 🟡 **Multi-portfolio** - power users, wyższy tier
5. 🟢 **Pozostałe features** - nice to have

---

## Zagrożenia i ryzyka

### 1. Ryzyko: Błędne mapowanie kategorii

**Problem:** User źle zmapuje kategorię, np. "Bills" → "Rozrywka"

**Skutek:** Błędne raporty, statystyki, budżety

**Mitygacja:**
- Pokazuj przykładowe transakcje dla każdej kategorii bankowej
- Podgląd przed importem: "Te transakcje trafią do kategorii X"
- Możliwość rollbacku i ponownego importu

### 2. Ryzyko: Duplikaty przy wielokrotnym imporcie

**Problem:** User importuje ten sam plik dwukrotnie

**Skutek:** Podwójne transakcje, błędne saldo

**Mitygacja:**
- `bankTransactionId` jako klucz deduplikacji
- Warning przy próbie importu już istniejących transakcji
- Podsumowanie: "X transakcji pominięto (duplikaty)"

### 3. Ryzyko: Niezgodność salda

**Problem:** Suma transakcji ≠ różnica sald (początkowe vs końcowe)

**Przyczyny:**
- Brak niektórych transakcji w eksporcie
- Błędny format danych
- Prowizje bankowe nie ujęte

**Mitygacja:**
- Wyraźne pokazanie różnicy w UI
- Opcja `forceActivation` z ostrzeżeniem
- Opcja `createAdjustment` - automatyczna korekta

### 4. Ryzyko: Utrata danych przy rollback

**Problem:** User robi rollback i traci wszystkie zaimportowane dane

**Skutek:** Frustracja, strata czasu

**Mitygacja:**
- Potwierdzenie przed rollbackiem: "Czy na pewno? To usunie X transakcji"
- Opcja zachowania kategorii przy rollbacku
- (Przyszłość) Eksport przed rollbackiem

### 5. Ryzyko: Performance przy dużym imporcie

**Problem:** Import 10 000+ transakcji (5 lat historii)

**Skutek:** Timeout, błędy, zła UX

**Mitygacja:**
- Batch processing z progress barem
- Async import z polling statusu
- Limity: max 5 lat historii, max 50 000 transakcji
- Paginacja w UI

### 6. Ryzyko: Konflikt kategorii historycznych z nowymi

**Problem:** Kategoria "Samochód" z 2024 vs nowa "Samochód" z 2025

**Skutek:** Niejednoznaczność, błędy w raportach

**Mitygacja:**
- Okres ważności (validFrom/validTo)
- UI pokazuje okres przy konflikcie nazw
- Walidacja: okresy nie mogą się nakładać

### 7. Ryzyko: Brak obsługi formatu bankowego

**Problem:** User ma eksport z banku X którego nie obsługujemy

**Skutek:** Brak możliwości importu

**Mitygacja:**
- Dokumentacja obsługiwanych formatów
- Generic CSV parser z konfiguracją kolumn
- (Przyszłość) Ręczne dodawanie transakcji w trybie SETUP?

### 8. Ryzyko: Przerwany import

**Problem:** Połączenie zerwane w trakcie batch importu

**Skutek:** Częściowo zaimportowane dane

**Mitygacja:**
- Transakcyjność na poziomie batch
- Możliwość wznowienia importu
- Clear status: "Zaimportowano X z Y"

---

## Pytania otwarte

### Do decyzji przed implementacją

1. **Ręczne dodawanie w SETUP mode?**
   - Czy pozwolić na `appendCashChange` w SETUP dla edge cases?
   - Np. transakcja gotówkowa której nie ma w wyciągu

2. **Edycja po aktywacji?**
   - Czy można edytować transakcje w miesiącach ATTESTED?
   - Obecnie: NIE - ale może być potrzebne dla korekt

3. **Limit historii**
   - Max 5 lat? 10 lat?
   - Max liczba transakcji?

4. **Format importu**
   - Jakie banki/formaty obsługujemy na start?
   - CSV generic? MT940? JSON?

5. **Kategorie - kto je tworzy?**
   - Tylko przez mapowanie w SETUP?
   - Czy można tworzyć ręcznie w SETUP (przed importem)?

6. **Archiwizacja kategorii**
   - Automatyczna po aktywacji (wszystkie IMPORTED → archived)?
   - Czy user może ręcznie archiwizować/przywracać?

7. **Multi-currency**
   - Czy CashFlow może mieć transakcje w różnych walutach?
   - Jak obsłużyć import z banku multi-currency?

### Do przemyślenia w przyszłości

8. **Recurring transactions**
   - Wykrywanie powtarzających się transakcji z importu?
   - Auto-tworzenie scheduled transactions?

9. **Bank API integration (PSD2)**
   - Kiedy wdrożyć?
   - Które banki na start?

10. **Machine learning kategoryzacji**
    - Auto-kategoryzacja na podstawie opisu transakcji?
    - Uczenie się z wyborów użytkownika?

---

## Następne kroki

### Faza 1: Core (MVP)
1. [ ] Nowy status SETUP dla CashFlow
2. [ ] Nowy status SETUP_PENDING dla Forecast
3. [ ] Command: `createCashFlowWithHistory`
4. [ ] Command: `importHistoricalCashChange` (pojedyncza)
5. [ ] Command: `activateCashFlow`
6. [ ] Command: `rollbackImport`
7. [ ] Walidacje w istniejących commandach (blokada w SETUP)
8. [ ] Testy integracyjne

### Faza 2: Categories & Mapping
9. [ ] Struktura kategorii z validFrom/validTo
10. [ ] Command: `configureCategoryMapping`
11. [ ] Event handlers dla nowych eventów
12. [ ] Forecast processor - obsługa SETUP_PENDING

### Faza 3: Batch & UI
13. [ ] Command: `batchImportHistoricalCashChanges`
14. [ ] CSV Parser (generic + ING, mBank, PKO)
15. [ ] REST API endpoints
16. [ ] UI Wizard (frontend)

### Faza 4: Polish
17. [ ] `appendPaidCashChange` command
18. [ ] Balance validation przy aktywacji
19. [ ] Adjustment transaction
20. [ ] Progress tracking dla batch import
21. [ ] Deduplikacja (bankTransactionId)

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2026-01-05 | Initial design - podstawowy koncept SETUP mode |
| 2026-01-05 | Dodano trzy endpointy do CashChange |
| 2026-01-05 | Dodano system kategorii z okresem ważności |
| 2026-01-05 | Dodano pełny flow mapowania kategorii |
| 2026-01-05 | Dodano integrację UI, zagrożenia, pytania otwarte |
| 2026-01-05 | Dodano Quick Start vs Advanced Setup - dwa tryby onboardingu |
| 2026-01-05 | Dodano sekcję korekt dla miesięcy ATTESTED (korekta zamiast edycji) |
| 2026-01-05 | Dodano Killer Features: Insights, Prediction, Anomaly Detection, Smart Budgeting |
| 2026-01-05 | Dodano analizę konkurencji (YNAB, Mint, Wallet, Spendee, Fintonic) |
| 2026-01-05 | Dodano model biznesowy: SIMPLE/PRO/BUSINESS z cenami i macierzą funkcji |
| 2026-01-05 | Dodano business features: Compliance & Audit, Roles, Eksport FK, Cash Flow Forecasting |
| 2026-01-05 | Dodano projekcję przychodów i strategię go-to-market |
| 2026-01-05 | Dodano szczerą analizę Monarch Money i Copilot Money - gdzie wygrywamy, gdzie przegrywamy |
| 2026-01-05 | Zaktualizowano roadmap: Nordigen (PSD2), AI kategoryzacja (LLM), multi-portfolio + sektory, trade history, shared accounts |
| 2026-01-05 | Dodano analizę opłacalności Nordigen + AI: unit economics, ROI, priorytety implementacji |
| 2026-01-06 | Dodano pełny design AI kategoryzacji: architektura, cache, prompt engineering, API, fazy implementacji |
| 2026-01-06 | Dodano przykład user journey: Quick Start + AI kategoryzacja (7 kroków, UI mockupy, wpływ na system) |
