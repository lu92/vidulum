# VID-151: Analiza Przepływu i Transformacji Danych

**Data:** 2026-04-15
**Cel:** Prześledzenie danych od pliku CSV do CashFlow Forecast z identyfikacją traconych pól

---

## Spis Treści

1. [Diagram Przepływu (ASCII)](#1-diagram-przepływu-ascii)
2. [Krok 1: Plik CSV Banku](#2-krok-1-plik-csv-banku)
3. [Krok 2: AI Transform → BankCsvRow](#3-krok-2-ai-transform--bankcsvrow)
4. [Krok 3: BankCsvRow → BankTransaction](#4-krok-3-bankcsvrow--banktransaction)
5. [Krok 4: StagedTransaction → PatternGroup](#5-krok-4-stagedtransaction--patterngroup)
6. [Krok 5: AI Categorization Prompt](#6-krok-5-ai-categorization-prompt)
7. [Krok 6: Import do CashFlow](#7-krok-6-import-do-cashflow)
8. [Krok 7: CashFlow Forecast](#8-krok-7-cashflow-forecast)
9. [Podsumowanie Traconych Danych](#9-podsumowanie-traconych-danych)
10. [Case Study: Transakcje Mindbox](#10-case-study-transakcje-mindbox)
11. [Wpływ na Kategoryzację](#11-wpływ-na-kategoryzację)

---

## 1. Diagram Przepływu (ASCII)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        PRZEPŁYW DANYCH: CSV → FORECAST                      │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────┐
│  1. BANK CSV FILE    │  lista_operacji_20260111.csv (Nest Bank)
│  (surowe dane)       │  402 transakcji, format specyficzny dla banku
└──────────┬───────────┘
           │
           │ AiBankCsvTransformService.transform()
           │ lub LocalCsvTransformer.transform()
           ▼
┌──────────────────────┐
│  2. BankCsvRow       │  Canonical CSV format (13 pól)
│  (format kanoniczny) │  + merchant, merchantConfidence (AI extracted)
└──────────┬───────────┘
           │
           │ UploadCsvCommandHandler / StageTransactionsCommand
           │ ⚠️ TRACONE: sourceAccountNumber, targetAccountNumber, bookingDate
           ▼
┌──────────────────────┐
│  3. BankTransaction  │  StageTransactionsCommand.BankTransaction (10 pól)
│  (command payload)   │  Brak numerów kont kontrahentów!
└──────────┬───────────┘
           │
           │ StageTransactionsCommandHandler.processTransaction()
           ▼
┌──────────────────────┐
│  4. StagedTransaction│  OriginalTransactionData + MappedTransactionData
│  (domain object)     │  Przechowuje merchant, ale nie ma accountNumber
└──────────┬───────────┘
           │
           │ PatternDeduplicator.deduplicate()
           │ Grupuje po: effectiveMerchant() = merchant || name
           │ ⚠️ NIE GRUPUJE po numerze konta!
           ▼
┌──────────────────────┐
│  5. PatternGroup     │  Zgrupowane transakcje (45 grup z 402 txn)
│  (for AI prompt)     │  pattern, sampleTransaction, transactionCount, etc.
└──────────┬───────────┘
           │
           │ AiCategorizationPromptBuilder.buildUserPrompt()
           ▼
┌──────────────────────┐
│  6. AI PROMPT        │  Tekst wysyłany do Claude/GPT
│  (categorization)    │  Zawiera: pattern, sample, description, bankCategory
│                      │  ⚠️ NIE ZAWIERA: counterpartyAccount
└──────────┬───────────┘
           │
           │ AiCategorizationResponseParser.parse()
           ▼
┌──────────────────────┐
│  7. PatternMapping   │  Cached mapping: pattern → category
│  (cache)             │  ⚠️ NIE ZAWIERA: counterpartyAccount
└──────────┬───────────┘
           │
           │ StartImportJobCommandHandler.processImportTransactionsPhase()
           │ ⚠️ TRACONE: merchant, merchantConfidence, bankTransactionId
           ▼
┌──────────────────────┐
│  8. CashChange       │  Domain aggregate w CashFlow
│  (domain entity)     │  name, description, money, categoryName, dates
│                      │  ⚠️ NIE MA: merchant, accountNumber, bankTxnId
└──────────┬───────────┘
           │
           │ Kafka Events → CashFlowForecastProcessor
           ▼
┌──────────────────────┐
│  9. TransactionDet.  │  Minimalne dane w Forecast
│  (forecast)          │  name, money, dueDate, endDate
│                      │  ⚠️ NIE MA: description, category (tylko jako key)
└──────────────────────┘
```

---

## 2. Krok 1: Plik CSV Banku

**Źródło:** `src/test/resources/lista_operacji_20260111.csv`
**Bank:** Nest Bank
**Format:** Specyficzny dla banku (separator `,`, polskie kodowanie)

### Struktura pliku CSV (Nest Bank):

```
Kolumna 0: Data operacji        (DD-MM-YYYY)
Kolumna 1: Data księgowania     (DD-MM-YYYY)
Kolumna 2: Typ operacji         ("Przelewy przychodzące", "Przelewy wychodzące", etc.)
Kolumna 3: Kwota                (z separatorem dziesiętnym)
Kolumna 4: Waluta               (PLN)
Kolumna 5: Tytuł operacji       (nazwa z pipe separatorami |)
Kolumna 6: Numer rachunku       ← KLUCZOWE POLE (26 cyfr bez PL)
Kolumna 7: Opis dodatkowy       (opcjonalny)
Kolumna 8: Saldo po operacji    (informacyjne)
```

### Przykładowy rekord Mindbox:

```csv
14-11-2025,14-11-2025,Przelewy przychodzące,37670.78,PLN,"MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWI|EDZIALNOŚCIĄ UL. ZŁOTA 59 00-120 WA|RSZAWA",82109018830000000109874194,"PBD9CH21 10/2025",67049.58,
```

**Pola dostępne:**
- Data operacji: `14-11-2025`
- Typ: `Przelewy przychodzące` → INFLOW
- Kwota: `37670.78 PLN`
- Tytuł: `MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ...`
- **Numer rachunku: `82109018830000000109874194`** ← ten sam dla wszystkich 51 transakcji Mindbox!
- Opis: `PBD9CH21 10/2025`

---

## 3. Krok 2: AI Transform → BankCsvRow

**Klasa:** `AiBankCsvTransformService` lub `LocalCsvTransformer`
**Wynik:** `BankCsvRow` (canonical format)

### Transformacja AI:

```java
// AiPromptBuilder.USER_PROMPT_TEMPLATE definiuje output format:
"bankTransactionId,name,description,bankCategory,amount,currency,type,
 operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,
 merchant,merchantConfidence"
```

### Struktura BankCsvRow:

```java
public record BankCsvRow(
    String bankTransactionId,        // ✓ generowany: NEST_2025-11-14_001
    String name,                     // ✓ z "Tytuł operacji"
    String description,              // ✓ z "Opis dodatkowy"
    String bankCategory,             // ✓ z "Typ operacji"
    BigDecimal amount,               // ✓ z "Kwota" (abs value)
    String currency,                 // ✓ z "Waluta"
    Type type,                       // ✓ INFLOW/OUTFLOW
    LocalDate operationDate,         // ✓ z "Data operacji"
    LocalDate bookingDate,           // ✓ z "Data księgowania"
    String sourceAccountNumber,      // ✓ dla INFLOW: numer nadawcy
    String targetAccountNumber,      // ✓ dla OUTFLOW: numer odbiorcy
    String merchant,                 // + AI extracted (opcjonalne)
    Double merchantConfidence        // + AI confidence 0.0-1.0
)
```

### Przykład po transformacji:

```
bankTransactionId:     NEST_2025-11-14_001
name:                  MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ UL. ZŁOTA 59 00-120 WARSZAWA
description:           PBD9CH21 10/2025
bankCategory:          Przelewy przychodzące
amount:                37670.78
currency:              PLN
type:                  INFLOW
operationDate:         2025-11-14
bookingDate:           2025-11-14
sourceAccountNumber:   PL82109018830000000109874194  ← ZNORMALIZOWANY IBAN
targetAccountNumber:   (puste dla INFLOW)
merchant:              (null - nie wyekstrahowane)
merchantConfidence:    (null)
```

**Status pól:** Wszystkie 13 pól dostępne ✓

---

## 4. Krok 3: BankCsvRow → BankTransaction

**Klasa źródłowa:** `UploadCsvCommandHandler.java` (linie 89-105)
**Klasa docelowa:** `StageTransactionsCommand.BankTransaction`

### Mapowanie pól:

```java
// UploadCsvCommandHandler.java:89-105
private StageTransactionsCommand.BankTransaction toBankTransaction(BankCsvRow row) {
    return new StageTransactionsCommand.BankTransaction(
        row.bankTransactionId(),           // ✓ przekazane
        row.name(),                        // ✓ przekazane
        row.effectiveDescription(),        // ✓ przekazane
        row.effectiveBankCategory(),       // ✓ przekazane
        Money.of(row.amount(), row.currency()),  // ✓ skonwertowane
        row.type(),                        // ✓ przekazane
        toZonedDateTime(row.operationDate()),    // ⚠️ operationDate → paidDate
        row.merchant(),                    // ✓ przekazane
        row.merchantConfidence()           // ✓ przekazane
    );
    // ✗ sourceAccountNumber - NIE PRZEKAZANE!
    // ✗ targetAccountNumber - NIE PRZEKAZANE!
    // ✗ bookingDate - NIE PRZEKAZANE!
}
```

### Struktura BankTransaction:

```java
public record BankTransaction(
    String bankTransactionId,       // ✓ z BankCsvRow
    String name,                    // ✓ z BankCsvRow
    String description,             // ✓ z BankCsvRow
    String bankCategory,            // ✓ z BankCsvRow
    Money money,                    // ✓ z BankCsvRow (amount + currency)
    Type type,                      // ✓ z BankCsvRow
    ZonedDateTime paidDate,         // ⚠️ z operationDate (bookingDate tracone)
    String merchant,                // ✓ z BankCsvRow
    Double merchantConfidence       // ✓ z BankCsvRow
    // ✗ BRAK: sourceAccountNumber
    // ✗ BRAK: targetAccountNumber
)
```

### ⚠️ TRACONE POLA:

| Pole | Powód utraty | Konsekwencja |
|------|--------------|--------------|
| `sourceAccountNumber` | Nie ma w BankTransaction record | Brak możliwości grupowania po koncie nadawcy |
| `targetAccountNumber` | Nie ma w BankTransaction record | Brak możliwości grupowania po koncie odbiorcy |
| `bookingDate` | Skonwertowane na paidDate | Utrata informacji o dacie księgowania vs operacji |

---

## 5. Krok 4: StagedTransaction → PatternGroup

**Klasa:** `PatternDeduplicator.java`
**Metoda:** `deduplicate(List<StagedTransaction> transactions)`

### Logika grupowania:

```java
// PatternDeduplicator.java:47-57
for (StagedTransaction transaction : transactions) {
    String originalName = transaction.originalData().name();
    String merchant = transaction.originalData().merchant();

    // KLUCZOWE: używa effectiveMerchant() do grupowania
    String patternSource = transaction.originalData().effectiveMerchant();
    // effectiveMerchant() = merchant != null ? merchant : name

    String normalizedPattern = normalizer.normalize(patternSource);
    Type type = transaction.originalData().type();

    PatternKey key = new PatternKey(normalizedPattern, type);
    // Grupuje transakcje po (normalizedPattern, type)
}
```

### TransactionNameNormalizer - jak normalizuje:

```java
// TransactionNameNormalizer.java:112-178
public String normalize(String name) {
    String normalized = name.toUpperCase().trim();

    // 1. Sprawdź known single-word patterns (BIEDRONKA, NETFLIX, ZUS, etc.)
    for (String pattern : KNOWN_SINGLE_WORD_PATTERNS) {
        if (normalized.startsWith(pattern + " ") || normalized.equals(pattern)) {
            return pattern;  // np. "BIEDRONKA WARSZAWA 123" → "BIEDRONKA"
        }
    }

    // 2. Sprawdź known two-word patterns (MEDIA EXPERT, BURGER KING, etc.)
    // ...

    // 3. Usuń noise (numery kont, daty, adresy, miasta, kody pocztowe)
    // ...

    // 4. Weź pierwsze 3 znaczące słowa
    // "MINDBOX SPÓŁKA Z OGRANICZONĄ..." → "MINDBOX SPÓŁKA OGRANICZONĄ"
}
```

### Problem z Mindbox:

```
MINDBOX nie jest w KNOWN_SINGLE_WORD_PATTERNS!

Wejście:
  "MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ UL. ZŁOTA 59..."

Normalizacja:
  1. Uppercase: "MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ UL. ZŁOTA 59..."
  2. Nie znaleziono w known patterns
  3. Usuń adresy: "MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ..."
  4. Pierwsze 3 słowa: "MINDBOX SPÓŁKA OGRANICZONĄ"

Wynik: "MINDBOX SPÓŁKA OGRANICZONĄ"
```

### Struktura PatternGroup:

```java
public record PatternGroup(
    String pattern,                    // ✓ znormalizowany pattern
    String sampleTransaction,          // ✓ longest original name
    String sampleMerchant,             // ✓ first merchant with highest confidence
    Double averageMerchantConfidence,  // ✓ średnia confidence
    String sampleDescription,          // ✓ longest non-blank description
    Type type,                         // ✓ INFLOW/OUTFLOW
    int transactionCount,              // + wyliczone (count)
    BigDecimal totalAmount,            // + wyliczone (sum)
    String bankCategory,               // ✓ most common bank category
    List<String> transactionIds        // + lista ID
    // ✗ BRAK: counterpartyAccountNumber - nigdy nie było!
)
```

### ⚠️ KONSEKWENCJA: Mindbox tworzy 3 grupy

```
Transakcje Mindbox w CSV (51 rekordów, 1 numer konta):
├── "MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ..." (nowsze)
├── "MINDBOX SPÓŁKA AKCYJNA UL. ZŁOTA 59..."            (starsze)
└── "Mindbox S.A."                                       (faktury)

Po PatternDeduplicator:
├── PatternGroup 1: "MINDBOX SPÓŁKA OGRANICZONĄ" (23 txn, INFLOW)
├── PatternGroup 2: "MINDBOX SPÓŁKA AKCYJNA"     (25 txn, INFLOW)
└── PatternGroup 3: "MINDBOX S.A."               (3 txn, OUTFLOW)

Gdyby grupował po counterpartyAccount:
└── PatternGroup 1: account=PL82109018830000000109874194 (51 txn)
    ├── INFLOW:  48 txn
    └── OUTFLOW: 3 txn
```

---

## 6. Krok 5: AI Categorization Prompt

**Klasa:** `AiCategorizationPromptBuilder.java`
**Metoda:** `formatPatternGroup(PatternGroup pg)`

### Format wysyłany do AI:

```java
// AiCategorizationPromptBuilder.java:343-378
private String formatPatternGroup(PatternDeduplicator.PatternGroup pg) {
    StringBuilder sb = new StringBuilder();

    // Linia 1: count, amount, pattern
    sb.append(String.format("  [%d txns, %s] %s\n",
            pg.transactionCount(),      // np. "23"
            formatAmount(pg.totalAmount()),  // np. "750.5k"
            pg.pattern()));             // np. "MINDBOX SPÓŁKA OGRANICZONĄ"

    // Linia 2: sample name
    sb.append(String.format("    | name: \"%s\"\n",
            truncate(pg.sampleTransaction(), 50)));

    // Linia 3: merchant (jeśli wyekstrahowany)
    if (pg.sampleMerchant() != null && !pg.sampleMerchant().isBlank()) {
        sb.append(String.format("    | merchant: \"%s\" (%.0f%%)\n",
                pg.sampleMerchant(), pg.averageMerchantConfidence() * 100));
    }

    // Linia 4: description
    if (pg.sampleDescription() != null && !pg.sampleDescription().isBlank()) {
        sb.append(String.format("    | title: \"%s\"\n",
                truncate(pg.sampleDescription(), 70)));
    }

    // Linia 5: bank category
    sb.append(String.format("    | bank: %s\n",
            pg.bankCategory()));

    // ✗ BRAK: counterpartyAccount - nie jest wysyłany do AI!

    return sb.toString();
}
```

### Przykład promptu dla Mindbox (obecny stan):

```
INFLOW PATTERNS (3 unique):

  [23 txns, 750.5k] MINDBOX SPÓŁKA OGRANICZONĄ
    | name: "MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ UL. ZŁOTA 59..."
    | title: "PBD9CH21 10/2025"
    | bank: Przelewy przychodzące

  [25 txns, 812.3k] MINDBOX SPÓŁKA AKCYJNA
    | name: "MINDBOX SPÓŁKA AKCYJNA UL. ZŁOTA 59 00-120 WARSZAWA"
    | title: "PAD8EI23 9/2025"
    | bank: Przelewy przychodzące

OUTFLOW PATTERNS (1 unique):

  [3 txns, 414] MINDBOX S.A.
    | name: "Mindbox S.A."
    | title: "kod klienta 2112 numer faktury NR 24/010200064"
    | bank: Przelewy wychodzące
```

### ⚠️ Problem: AI nie wie że to ten sam kontrahent!

AI widzi 3 różne patterny i może zasugerować:
- "MINDBOX SPÓŁKA OGRANICZONĄ" → Wynagrodzenie
- "MINDBOX SPÓŁKA AKCYJNA" → Przychody z działalności
- "MINDBOX S.A." → Opłaty/Prowizje

---

## 7. Krok 6: Import do CashFlow

**Klasa:** `StartImportJobCommandHandler.java`
**Metoda:** `processImportTransactionsPhase()` (linie 290-352)

### Tworzenie ImportTransactionRequest:

```java
// StartImportJobCommandHandler.java:300-310
CashFlowServiceClient.ImportTransactionRequest request =
    new CashFlowServiceClient.ImportTransactionRequest(
        st.mappedData().categoryName().name(),  // ✓ z mapping
        st.mappedData().name(),                  // ✓ name
        st.mappedData().description(),           // ✓ description
        st.mappedData().money().getAmount(),     // ✓ amount
        st.mappedData().money().getCurrency(),   // ✓ currency
        st.mappedData().type(),                  // ✓ type
        st.mappedData().paidDate().toLocalDate(), // ✓ dueDate
        st.mappedData().paidDate().toLocalDate()  // ✓ paidDate
    );
    // ✗ NIE PRZEKAZUJE: merchant
    // ✗ NIE PRZEKAZUJE: merchantConfidence
    // ✗ NIE PRZEKAZUJE: bankTransactionId
    // ✗ NIE PRZEKAZUJE: bankCategory
```

### Struktura ImportTransactionRequest:

```java
record ImportTransactionRequest(
    String categoryName,    // ✓ przekazane
    String name,            // ✓ przekazane
    String description,     // ✓ przekazane
    double amount,          // ✓ przekazane
    String currency,        // ✓ przekazane
    Type type,              // ✓ przekazane
    LocalDate dueDate,      // ✓ przekazane
    LocalDate paidDate      // ✓ przekazane
    // ✗ BRAK: merchant
    // ✗ BRAK: merchantConfidence
    // ✗ BRAK: bankTransactionId
    // ✗ BRAK: counterpartyAccount
)
```

### ⚠️ TRACONE POLA:

| Pole | Status w MappedTransactionData | Status w ImportRequest |
|------|--------------------------------|------------------------|
| merchant | ✓ Dostępne | ✗ Nie przekazane |
| merchantConfidence | ✓ Dostępne | ✗ Nie przekazane |
| bankTransactionId | ✓ W OriginalTransactionData | ✗ Nie przekazane |
| bankCategory | ✓ W OriginalTransactionData | ✗ Nie przekazane |

### Struktura CashChange (docelowa):

```java
// CashChange.java:16-28
public class CashChange {
    private CashChangeId cashChangeId;      // + generowane
    private Name name;                      // ✓ z request
    private Description description;        // ✓ z request
    private Money money;                    // ✓ z request
    private Type type;                      // ✓ z request
    private CategoryName categoryName;      // ✓ z request
    private CashChangeStatus status;        // + CONFIRMED (dla historycznych)
    private ZonedDateTime created;          // + timestamp
    private ZonedDateTime dueDate;          // ✓ z request
    private ZonedDateTime endDate;          // ✓ z request (paidDate)
    private String sourceRuleId;            // + null (nie z rule)

    // ✗ BRAK: merchant
    // ✗ BRAK: merchantConfidence
    // ✗ BRAK: bankTransactionId
    // ✗ BRAK: counterpartyAccountNumber
    // ✗ BRAK: bankCategory
}
```

---

## 8. Krok 7: CashFlow Forecast

**Klasa:** `CashFlowForecastStatement.java`
**Struktura:** `TransactionDetails`

### Struktura TransactionDetails:

```java
// TransactionDetails.java:15-22
public class TransactionDetails {
    private CashChangeId cashChangeId;  // ✓ z CashChange
    private Name name;                  // ✓ z CashChange
    private Money money;                // ✓ z CashChange
    private ZonedDateTime created;      // ✓ z CashChange
    private ZonedDateTime dueDate;      // ✓ z CashChange
    private ZonedDateTime endDate;      // ✓ z CashChange

    // ✗ BRAK: description
    // ✗ BRAK: categoryName (jest jako klucz w mapie, nie w obiekcie)
    // ✗ BRAK: merchant
    // ✗ BRAK: counterpartyAccount
}
```

### Dane w Forecast Statement:

```
CashFlowForecastStatement:
├── categorizedOutFlows: Map<CategoryName, List<TransactionDetails>>
│   ├── "Wynagrodzenie": [TransactionDetails, ...]
│   └── "Opłaty": [TransactionDetails, ...]
├── categorizedInFlows: Map<CategoryName, List<TransactionDetails>>
├── totalInflows: Money
├── totalOutflows: Money
└── balanceEvolution: List<BalancePoint>
```

---

## 9. Podsumowanie Traconych Danych

### Tabela: Przepływ pól przez system

```
Pole                    │ CSV │ BankCsvRow │ BankTxn │ Original │ Mapped │ Import │ CashChange │ Forecast
────────────────────────┼─────┼────────────┼─────────┼──────────┼────────┼────────┼────────────┼─────────
bankTransactionId       │  -  │     ✓      │    ✓    │    ✓     │   -    │   ✗    │     ✗      │    ✗
name                    │  ✓  │     ✓      │    ✓    │    ✓     │   ✓    │   ✓    │     ✓      │    ✓
description             │  ✓  │     ✓      │    ✓    │    ✓     │   ✓    │   ✓    │     ✓      │    ✗
bankCategory            │  ✓  │     ✓      │    ✓    │    ✓     │   -    │   ✗    │     ✗      │    ✗
amount                  │  ✓  │     ✓      │    ✓    │    ✓     │   ✓    │   ✓    │     ✓      │    ✓
currency                │  ✓  │     ✓      │    ✓    │    ✓     │   ✓    │   ✓    │     ✓      │    ✓
type                    │  ✓  │     ✓      │    ✓    │    ✓     │   ✓    │   ✓    │     ✓      │    -
operationDate           │  ✓  │     ✓      │    →    │    →     │   →    │   →    │     →      │    →
bookingDate             │  ✓  │     ✓      │    ✗    │    ✗     │   ✗    │   ✗    │     ✗      │    ✗
sourceAccountNumber     │  ✓  │     ✓      │    ✗    │    ✗     │   ✗    │   ✗    │     ✗      │    ✗
targetAccountNumber     │  ✓  │     ✓      │    ✗    │    ✗     │   ✗    │   ✗    │     ✗      │    ✗
merchant                │  -  │     ✓      │    ✓    │    ✓     │   ✓    │   ✗    │     ✗      │    ✗
merchantConfidence      │  -  │     ✓      │    ✓    │    ✓     │   ✓    │   ✗    │     ✗      │    ✗
categoryName            │  -  │     -      │    -    │    -     │   ✓    │   ✓    │     ✓      │   (key)

Legenda:
✓  = pole dostępne
✗  = pole tracone (było dostępne wcześniej)
-  = pole nie istnieje na tym etapie
→  = pole przekształcone (np. operationDate → paidDate)
```

### Krytyczne punkty utraty danych:

```
PUNKT 1: BankCsvRow → BankTransaction (StageTransactionsCommand)
─────────────────────────────────────────────────────────────────
Tracone: sourceAccountNumber, targetAccountNumber, bookingDate

Lokalizacja: UploadCsvCommandHandler.java:89-105
Powód: BankTransaction record nie zawiera tych pól
Impact: Brak możliwości grupowania po koncie kontrahenta


PUNKT 2: MappedTransactionData → ImportTransactionRequest
─────────────────────────────────────────────────────────────────
Tracone: merchant, merchantConfidence, bankTransactionId, bankCategory

Lokalizacja: StartImportJobCommandHandler.java:300-310
Powód: ImportTransactionRequest nie zawiera tych pól
Impact: Utrata metadanych o transakcji w CashFlow
```

---

## 10. Case Study: Transakcje Mindbox

### Dane wejściowe (CSV):

```
51 transakcji od Mindbox:
├── Numer konta: 82109018830000000109874194 (ZAWSZE TEN SAM)
├── Typ: 48x INFLOW (wynagrodzenie), 3x OUTFLOW (faktury)
├── Suma INFLOW: ~1.5M PLN
├── Suma OUTFLOW: ~400 PLN
└── Różne nazwy firmowe:
    ├── "MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ..." (2024-2025)
    ├── "MINDBOX SPÓŁKA AKCYJNA..." (2023-2024)
    └── "Mindbox S.A." (faktury)
```

### Obecny przepływ (bez counterpartyAccount):

```
CSV (51 txn, 1 account)
        │
        ▼
BankCsvRow (sourceAccountNumber = "PL82109018830000000109874194")
        │
        │ ⚠️ TRACONE: sourceAccountNumber
        ▼
BankTransaction (brak numeru konta)
        │
        ▼
PatternDeduplicator
        │
        │ Grupuje po: normalize(name)
        │
        ├─► "MINDBOX SPÓŁKA OGRANICZONĄ" (23 txn)
        ├─► "MINDBOX SPÓŁKA AKCYJNA"     (25 txn)
        └─► "MINDBOX S.A."               (3 txn)
        │
        ▼
AI Categorization
        │
        │ AI widzi 3 różne patterny
        │
        ├─► "Wynagrodzenie" ?
        ├─► "Przychody z działalności" ?
        └─► "Opłaty" ?
        │
        ▼
CashFlow
        │
        │ 3 różne kategorie dla tego samego kontrahenta!
        │
        └─► Niespójne raportowanie
```

### Pożądany przepływ (z counterpartyAccount):

```
CSV (51 txn, 1 account)
        │
        ▼
BankCsvRow (sourceAccountNumber = "PL82109018830000000109874194")
        │
        │ ✓ PRZEKAZANE: counterpartyAccount
        ▼
BankTransaction (counterpartyAccount = "PL82109018830000000109874194")
        │
        ▼
PatternDeduplicator
        │
        │ Grupuje po: (counterpartyAccount, type) lub (pattern, type)
        │
        ├─► account=PL821090..., INFLOW  (48 txn) → 1 grupa
        └─► account=PL821090..., OUTFLOW (3 txn)  → 1 grupa
        │
        ▼
AI Categorization
        │
        │ AI widzi 2 grupy (ten sam kontrahent, różne typy)
        │
        ├─► "Wynagrodzenie" (INFLOW)
        └─► "Opłaty" (OUTFLOW)
        │
        ▼
CashFlow
        │
        │ Spójne kategorie dla kontrahenta
        │
        └─► Prawidłowe raportowanie
```

---

## 11. Wpływ na Kategoryzację

### Obecny stan:

```
Statystyki kategoryzacji (791 transakcji):
├── Uncategorized:        539 (68%)
├── Prawidłowo zmapowane: 187 (24%)
└── Błędnie zmapowane:     65 (8%)
```

### Przyczyny wysokiego % Uncategorized:

```
1. Brak grupowania po koncie kontrahenta (np. Mindbox = 3 grupy zamiast 1)
2. Brak MINDBOX w KNOWN_SINGLE_WORD_PATTERNS
3. Generyczne bankCategory ("TRANSAKCJA KARTĄ PŁATNICZĄ" = 60% txn)
4. AI tworzy za mało patternMappings (4 z 45 patternów)
5. Błędne bankCategoryMappings (AI używa nazw merchantów jako bankCategory)
```

### Szacowany wpływ usprawnień:

```
Strategia                              │ Effort │ Uncategorized przed → po │ Poprawa
───────────────────────────────────────┼────────┼──────────────────────────┼─────────
Baseline (obecny stan)                 │   -    │          68%             │    -
+ Quick fix: MINDBOX w known patterns  │  5min  │       68% → 65%          │   -3%
+ Quick fixes A+B+C (z backlogu)       │   5h   │       65% → 19%          │  -46%
+ counterpartyAccount w flow           │  4-6h  │       19% → 10%          │   -9%
+ Grupowanie po account w Deduplicator │  2-3h  │       10% → 8%           │   -2%
+ Pattern cache z account              │  3-4h  │        8% → 5%           │   -3%
───────────────────────────────────────┼────────┼──────────────────────────┼─────────
RAZEM                                  │ ~15h   │       68% → 5%           │  -63%
```

### Szczegóły usprawnień:

```
1. QUICK FIX: Dodaj "MINDBOX" do TransactionNameNormalizer
   Plik: TransactionNameNormalizer.java:20-58
   Zmiana: KNOWN_SINGLE_WORD_PATTERNS.add("MINDBOX")
   Efekt: Mindbox → 1 pattern zamiast 3

2. COUNTER PARTY ACCOUNT W FLOW:
   Pliki do zmiany:
   ├── StageTransactionsCommand.BankTransaction (+counterpartyAccount)
   ├── OriginalTransactionData (+counterpartyAccount)
   ├── UploadCsvCommandHandler (przekaż account)
   └── StagedTransactionEntity (persist account)
   Efekt: Dane o koncie dostępne w całym flow

3. GRUPOWANIE W PATTERN DEDUPLICATOR:
   Plik: PatternDeduplicator.java:47-57
   Zmiana: PatternKey = (counterpartyAccount || pattern, type)
   Efekt: Transakcje z tym samym kontem = 1 grupa

4. AI PROMPT Z ACCOUNT:
   Plik: AiCategorizationPromptBuilder.java:343-378
   Zmiana: Dodaj "| counterparty: PL821090..." do formatu
   Efekt: AI wie że różne nazwy = ten sam kontrahent

5. PATTERN CACHE Z ACCOUNT:
   Plik: PatternMapping.java
   Zmiana: Dodaj counterpartyAccount jako alternatywny klucz
   Efekt: Matching po account bez AI call
```

---

## Załącznik: Kluczowe lokalizacje kodu

```
PARSOWANIE CSV:
├── CsvParserService.java:134-151          (parseRow)
├── BankCsvRow.java:12-132                 (record definition)

STAGING:
├── StageTransactionsCommand.java:36-47    (BankTransaction record)
├── UploadCsvCommandHandler.java:89-105    (toBankTransaction - PUNKT UTRATY 1)
├── StageTransactionsCommandHandler.java   (processTransaction)

CATEGORIZATION:
├── PatternDeduplicator.java:38-76         (deduplicate)
├── TransactionNameNormalizer.java:20-58   (KNOWN_SINGLE_WORD_PATTERNS)
├── AiCategorizationPromptBuilder.java:343-378 (formatPatternGroup)
├── AiCategorizationService.java:68-236    (categorize)

IMPORT:
├── StartImportJobCommandHandler.java:300-310 (PUNKT UTRATY 2)
├── CashFlowServiceClient.java:70-79       (ImportTransactionRequest)

DOMAIN:
├── CashChange.java:16-28                  (aggregate)
├── OriginalTransactionData.java:21-47     (record)
├── MappedTransactionData.java             (record)

FORECAST:
├── TransactionDetails.java:15-22          (minimal data)
├── CashFlowForecastStatement.java         (statement structure)
```
