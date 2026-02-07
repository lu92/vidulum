# Intelligent Cash Flow Reconciliation & Forecasting System

**Data:** 2026-02-07
**Status:** DRAFT
**Autor:** Design Session with Claude

---

## Spis treści

1. [Cel i zakres](#cel-i-zakres)
2. [Bank Integration Providers](#bank-integration-providers)
3. [Model danych - rozszerzenia](#model-danych---rozszerzenia)
4. [Statusy CashChange](#statusy-cashchange)
5. [Adapter Pattern - Bank Integration](#adapter-pattern---bank-integration)
6. [Unified Data Ingestion Pipeline](#unified-data-ingestion-pipeline)
7. [Reconciliation Engine](#reconciliation-engine)
8. [AI Categorization](#ai-categorization)
9. [Soft Close - automatyczne zamykanie miesięcy](#soft-close---automatyczne-zamykanie-miesięcy)
10. [RecurringRule Aggregate](#recurringrule-aggregate)
11. [UI Messages & User Flows](#ui-messages--user-flows)
12. [Scenariusze - pozytywne i negatywne](#scenariusze---pozytywne-i-negatywne)
13. [Następne kroki](#następne-kroki)

---

## Cel i zakres

### Problem

Obecny system wymaga ręcznego:
- Potwierdzania każdej transakcji (PENDING → CONFIRMED)
- Atestacji miesięcy
- Kategoryzacji transakcji
- Dopasowywania oczekiwanych transakcji do bankowych

### Cel

Zbudować **inteligentny system Cash Flow Forecasting** który:
1. Automatycznie dopasowuje transakcje bankowe do oczekiwanych (EXPECTED/FORECASTED)
2. Automatycznie kategoryzuje transakcje
3. Minimalizuje zaangażowanie użytkownika
4. Generuje prognozy na podstawie Recurring Rules
5. Obsługuje dane z CSV i API bankowego przez jeden pipeline

---

## Bank Integration Providers

### Porównanie: GoCardless vs Tink

| Aspekt | GoCardless (Nordigen) | Tink (Visa) |
|--------|----------------------|-------------|
| **Cena** | **DARMOWE** dla AIS (Account Information) | €0.50/user/miesiąc (Standard) |
| **Płatne** | Tylko PIS (Payment Initiation) | Wszystko |
| **Banki EU** | 2,300+ | 6,000+ |
| **Banki PL** | ~263 | 509+ |
| **Sandbox** | Tak | Tak (console.tink.com) |
| **Data Enrichment** | Podstawowe | Zaawansowane (ML kategoryzacja) |
| **Status (2025)** | ⚠️ Raporty o ograniczeniu dla nowych klientów | Aktywny |

### Polskie banki - pełna lista potwierdzonego wsparcia

#### Tink - potwierdzone banki w Polsce (ze status page)

| Bank | Tink ID | Status |
|------|---------|--------|
| **PKO Bank Polski** | pl-pko-ob | ✅ Wspierany |
| **Bank Pekao S.A.** | pl-pekao-ob | ✅ Wspierany |
| **Santander Bank Polska** | pl-santander-ob | ✅ Wspierany |
| **mBank** | pl-mbank-ob | ✅ Wspierany |
| **ING Bank Śląski** | pl-ing-ob | ✅ Wspierany |
| **BNP Paribas** | pl-bnpparibas-ob | ✅ Wspierany |
| **Bank Millennium** | pl-millennium-ob | ✅ Wspierany |
| **Alior Bank** | pl-alior-ob | ✅ Wspierany |
| **Credit Agricole** | pl-creditagricole-ob | ✅ Wspierany |
| **Revolut** | pl-revolut-ob | ✅ Wspierany |
| **Wise** | pl-wise-ob | ✅ Wspierany |

> **Źródło:** [Tink Poland Status Page](https://tinkpoland.statuspage.io/)

#### GoCardless (Nordigen) - potwierdzone banki w Polsce

| Bank | Historia transakcji | Status |
|------|---------------------|--------|
| **PKO Bank Polski** | 360 dni | ✅ Wspierany |
| **Bank Pekao S.A.** | 730 dni | ✅ Wspierany |
| **Santander Bank Polska** | 730 dni | ✅ Wspierany |
| **Nest Bank** | 730 dni | ✅ Wspierany |
| **Bank Millennium** | - | ✅ Wspierany |
| **Alior Bank** | - | ✅ Wspierany |
| mBank | - | Prawdopodobnie |
| ING Bank Śląski | - | Prawdopodobnie |
| BNP Paribas | - | Prawdopodobnie |
| Credit Agricole | - | Prawdopodobnie |

> **Źródło:** [Fintable - GoCardless Coverage](https://fintable.io/coverage/providers/NORDIGEN), [Open Banking Tracker](https://www.openbankingtracker.com/api-aggregators/nordigen)

#### Porównanie wsparcia banków

| Bank | GoCardless | Tink | Uwagi |
|------|------------|------|-------|
| PKO Bank Polski | ✅ | ✅ | Największy bank w Polsce |
| Bank Pekao S.A. | ✅ | ✅ | 2. największy bank |
| Santander Bank Polska | ✅ | ✅ | |
| mBank | ? | ✅ | 6. największy bank |
| ING Bank Śląski | ? | ✅ | |
| BNP Paribas | ? | ✅ | |
| Bank Millennium | ✅ | ✅ | |
| Alior Bank | ✅ | ✅ | |
| Credit Agricole | ? | ✅ | |
| **Nest Bank** | ✅ | ❌ | Tylko GoCardless! |
| Revolut | ? | ✅ | Neobank |
| Wise | ? | ✅ | Neobank |

> **Wniosek:** Tink ma lepsze pokrycie głównych banków, ale GoCardless wspiera Nest Bank którego Tink nie ma.

### API Rate Limits

#### GoCardless (Nordigen) - szczegółowe limity

| Limit | Wartość | Uwagi |
|-------|---------|-------|
| **Minimalny limit bankowy** | 4 requesty/dzień/konto | Obowiązkowe minimum PSD2 |
| **GoCardless limit (od 08.2024)** | 10 requestów/dzień/scope | Na account ID level |
| **Planowany limit** | 4 requesty/dzień/scope | W przyszłości |
| **Bulk operations** | 1000 requestów/minutę | Dla operacji masowych |
| **Free tier** | 50 aktywnych połączeń/miesiąc | Od 06.2023 |

**Scope = endpoint:** details, balances, transactions (każdy osobno)

**Co się dzieje przy przekroczeniu:**
- Błąd: `RateLimitError: "Daily request limit set by the Institution has been exceeded"`
- Trzeba czekać do następnego dnia
- Reconnect konta MOŻE zresetować licznik (nie gwarantowane)

**Rekomendacje:**
- Nie przekraczaj 4 requestów/dzień/konto
- Sync rano (np. 6:00) żeby mieć dane na cały dzień
- Implementuj cache i retry z backoff

#### Tink - rate limits

| Limit | Wartość | Uwagi |
|-------|---------|-------|
| **Limity bankowe** | Zależne od banku | PSD2 minimum 4/dzień |
| **Tink platform** | Nieudokumentowane publicznie | Enterprise pricing |
| **Refresh interval** | Konfigurowalny | Zależny od planu |

> **Uwaga:** Tink nie publikuje szczegółowych limitów. Dla enterprise klientów limity są negocjowane indywidualnie.

### Porównanie kosztów

| Scenariusz | GoCardless | Tink |
|------------|------------|------|
| **1 user, 1 konto** | DARMOWE | €0.50/miesiąc |
| **100 userów** | DARMOWE* | €50/miesiąc |
| **1000 userów** | Pay as you go** | Custom pricing |

*Do 50 aktywnych połączeń/miesiąc
**Powyżej 50 połączeń wymaga kontaktu z sales

### Rekomendacja

1. **Faza rozwoju:** GoCardless (darmowe) dla prototypu i testów
2. **Produkcja:** Tink (lepsza jakość danych, enrichment) lub oba jako fallback
3. **Design:** Adapter pattern umożliwiający łatwą zmianę/dodanie providera

### Linki

- [GoCardless Bank Account Data](https://developer.gocardless.com/bank-account-data/overview)
- [Tink Developer Docs](https://docs.tink.com/)
- [Tink Pricing](https://tink.com/pricing/)
- [Open Banking Tracker - Poland](https://www.openbankingtracker.com/country/poland)

---

## Model danych - rozszerzenia

### CashChange - rozszerzony model

```java
public class CashChange {
    // === ISTNIEJĄCE POLA ===
    private CashChangeId cashChangeId;
    private Name name;
    private Description description;
    private Money money;
    private Type type;                      // INFLOW / OUTFLOW
    private CategoryName categoryName;
    private CashChangeStatus status;
    private ZonedDateTime created;
    private ZonedDateTime dueDate;          // Planowana data płatności
    private ZonedDateTime endDate;          // Faktyczna data zakończenia

    // === NOWE POLA - Dane bankowe ===
    private String bankTransactionId;       // ID z systemu bankowego (dla deduplikacji)
    private ZonedDateTime paidDate;         // Kiedy faktycznie zapłacono
    private String counterpartyAccount;     // IBAN odbiorcy/nadawcy (KLUCZOWE dla matching!)
    private String counterpartyName;        // Nazwa odbiorcy/nadawcy
    private String merchantCategoryCode;    // MCC (jeśli dostępny)

    // === NOWE POLA - Źródło i powiązania ===
    private CashChangeSource source;        // MANUAL, BANK_IMPORT, BANK_API, RECURRING_RULE
    private RecurringRuleId recurringRuleId; // Jeśli wygenerowany z reguły (nullable)
    private CashChangeId matchedWithId;     // Jeśli dopasowany do innego CashChange (nullable)

    // === NOWE POLA - Matching Audit ===
    private MatchingAudit matchingAudit;    // Pełna historia dopasowania

    // === NOWE POLA - Surowe dane ===
    private String rawBankData;             // JSON z oryginalną odpowiedzią banku (nullable)
}

public enum CashChangeSource {
    MANUAL,          // Ręcznie dodany przez użytkownika
    BANK_IMPORT,     // Import z CSV
    BANK_API,        // Real-time z API bankowego
    RECURRING_RULE   // Wygenerowany z RecurringRule (FORECASTED)
}
```

### MatchingAudit - pełna historia dopasowania

```java
public record MatchingAudit(
    // === Jak został dopasowany ===
    MatchingMethod method,              // AUTO_COUNTERPARTY, AUTO_AMOUNT_DATE, MANUAL, AI, NONE
    Integer matchingScore,              // 0-100 (null jeśli brak dopasowania)
    String matchedWithCashChangeId,     // ID dopasowanego EXPECTED/FORECASTED (nullable)
    String matchedByField,              // "counterpartyAccount", "amount+date+category", etc.

    // === Jak został skategoryzowany ===
    CategorizationMethod categorizationMethod, // BANK_CATEGORY, HISTORY_COUNTERPARTY,
                                               // HISTORY_PATTERN, AI, MANUAL, DEFAULT
    String categorizationReason,        // Szczegółowy powód
    Double aiConfidence,                // Pewność AI (0.0-1.0, nullable)
    String aiModel,                     // np. "claude-3-haiku" (nullable)

    // === Metadata ===
    ZonedDateTime matchedAt,
    String matchedBy,                   // "system" lub userId

    // === Historia zmian ===
    List<AuditEntry> history            // Jeśli zmieniano kategorię/dopasowanie
) {}

public enum MatchingMethod {
    AUTO_COUNTERPARTY,    // Automatyczne po numerze konta odbiorcy (najsilniejsze)
    AUTO_AMOUNT_DATE,     // Automatyczne po kwocie + dacie
    AUTO_PATTERN,         // Automatyczne po wzorcu w opisie
    MANUAL,               // Ręczne dopasowanie przez użytkownika
    AI,                   // Sugestia AI zaakceptowana
    NONE                  // Brak dopasowania (nowa transakcja)
}

public enum CategorizationMethod {
    BANK_CATEGORY,        // Użyto kategorii z banku (mapped)
    HISTORY_COUNTERPARTY, // Z historii - ten sam odbiorca
    HISTORY_PATTERN,      // Z historii - podobny opis
    RECURRING_RULE,       // Z reguły recurring
    AI,                   // Kategoryzacja AI
    MANUAL,               // Ręcznie przez użytkownika
    DEFAULT               // Domyślna (Uncategorized)
}

public record AuditEntry(
    ZonedDateTime timestamp,
    String action,                      // "CATEGORIZED", "MATCHED", "UNMATCHED", "WRITTEN_OFF"
    String previousValue,
    String newValue,
    String performedBy,                 // "system" lub userId
    String reason
) {}
```

### BankTransactionDTO - kanoniczny format

```java
/**
 * Canonical DTO for bank transactions from any source (CSV, API, Webhook).
 * All fields that may not be available from certain sources are nullable.
 */
public record BankTransactionDTO(
    // === Identyfikacja (wymagane) ===
    String bankTransactionId,           // Unikalny ID z banku
    LocalDate transactionDate,          // Data transakcji
    Money amount,                        // Kwota
    Type type,                          // INFLOW / OUTFLOW

    // === Daty (opcjonalne - nie wszystkie banki/CSV dostarczają) ===
    LocalDate bookingDate,              // Data księgowania (nullable)
    LocalDate valueDate,                // Data waluty (nullable)

    // === Opis (wymagane) ===
    String description,                 // Opis transakcji

    // === Kontrahent (opcjonalne - KLUCZOWE dla matching jeśli dostępne) ===
    String counterpartyAccount,         // IBAN odbiorcy/nadawcy (nullable)
    String counterpartyName,            // Nazwa odbiorcy/nadawcy (nullable)

    // === Kategoryzacja (opcjonalne) ===
    String bankCategory,                // Kategoria z banku (nullable)
    String merchantCategoryCode,        // MCC (nullable)

    // === Metadata ===
    TransactionSource source,           // CSV_IMPORT, BANK_API, WEBHOOK
    String rawData                      // Oryginalna odpowiedź JSON (nullable)
) {}

public enum TransactionSource {
    CSV_IMPORT,     // Import z pliku CSV
    BANK_API,       // Batch sync z API bankowego
    WEBHOOK         // Real-time webhook z banku
}
```

---

## Statusy CashChange

### Pełna lista statusów

```java
public enum CashChangeStatus {
    // === Transakcje planowane (forecast) ===
    PENDING,        // Ręcznie dodana przez usera, oczekuje na potwierdzenie/dopasowanie
    FORECASTED,     // Wygenerowana z RecurringRule, oczekuje na dopasowanie

    // === Transakcje potwierdzone ===
    CONFIRMED,      // Potwierdzona/dopasowana transakcja

    // === Transakcje nierozwiązane ===
    UNMATCHED,      // Koniec miesiąca, brak dopasowania - wymaga decyzji usera

    // === Transakcje zakończone negatywnie ===
    REJECTED,       // Odrzucona przez usera
    WRITTEN_OFF,    // Oznaczona jako nieopłacona/stracona (kwota = 0 w statystykach)

    // === Archiwum ===
    ARCHIVED        // Zarchiwizowana
}
```

### Przepływy stanów

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         STATUSY CASHCHANGE - PRZEPŁYWY                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    ŹRÓDŁA TRANSAKCJI                                 │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                     │   │
│  │  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐            │   │
│  │  │   BANK       │   │  RECURRING   │   │   MANUAL     │            │   │
│  │  │ (CSV/API)    │   │    RULE      │   │   (user)     │            │   │
│  │  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘            │   │
│  │         │                  │                  │                     │   │
│  │         ▼                  ▼                  ▼                     │   │
│  │  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐            │   │
│  │  │  CONFIRMED   │   │  FORECASTED  │   │   PENDING    │            │   │
│  │  │              │   │              │   │              │            │   │
│  │  │ category:    │   │ category:    │   │ category:    │            │   │
│  │  │ (auto/Uncat.)│   │ (z reguły)   │   │ (z usera)    │            │   │
│  │  └──────────────┘   └──────────────┘   └──────────────┘            │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    RECONCILIATION                                    │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                     │   │
│  │  Bank transaction arrives:                                          │   │
│  │                                                                     │   │
│  │  1. Szukaj dopasowania do PENDING/FORECASTED                        │   │
│  │     ┌──────────────────────────────────────────────────────────┐   │   │
│  │     │  Score 65+  →  AUTO-MATCH                                │   │   │
│  │     │  • PENDING/FORECASTED → CONFIRMED                        │   │   │
│  │     │  • Bank transaction merged                               │   │   │
│  │     │  • matchingAudit zapisany                                │   │   │
│  │     └──────────────────────────────────────────────────────────┘   │   │
│  │                                                                     │   │
│  │     ┌──────────────────────────────────────────────────────────┐   │   │
│  │     │  Score 50-64  →  SUGESTIA dla usera                      │   │   │
│  │     │  • Bank transaction → CONFIRMED (Uncategorized)          │   │   │
│  │     │  • Alert: "Czy dopasować do [PENDING]?"                  │   │   │
│  │     └──────────────────────────────────────────────────────────┘   │   │
│  │                                                                     │   │
│  │     ┌──────────────────────────────────────────────────────────┐   │   │
│  │     │  Score <50  →  BRAK dopasowania                          │   │   │
│  │     │  • Bank transaction → CONFIRMED (kategoryzacja auto/AI)  │   │   │
│  │     │  • PENDING/FORECASTED pozostaje (czeka dalej)            │   │   │
│  │     └──────────────────────────────────────────────────────────┘   │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    SOFT CLOSE (koniec miesiąca)                      │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                     │   │
│  │  Transakcje PENDING/FORECASTED bez dopasowania:                     │   │
│  │                                                                     │   │
│  │  ┌──────────────┐                                                   │   │
│  │  │   PENDING    │──┐                                                │   │
│  │  │  FORECASTED  │  │                                                │   │
│  │  └──────────────┘  │                                                │   │
│  │                    │  Koniec miesiąca                               │   │
│  │                    ▼  (automatycznie)                               │   │
│  │            ┌──────────────┐                                         │   │
│  │            │  UNMATCHED   │                                         │   │
│  │            │              │                                         │   │
│  │            │ Wymaga       │                                         │   │
│  │            │ decyzji      │                                         │   │
│  │            │ użytkownika  │                                         │   │
│  │            └──────┬───────┘                                         │   │
│  │                   │                                                 │   │
│  │     ┌─────────────┼─────────────┬─────────────┐                    │   │
│  │     ▼             ▼             ▼             ▼                    │   │
│  │ ┌────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐              │   │
│  │ │CONFIRMED│  │ PENDING  │  │WRITTEN_OFF│  │ REJECTED │              │   │
│  │ │(ręczne │  │(przesuń  │  │(nieopłac.)│  │ (usuń)   │              │   │
│  │ │dopasow)│  │na później)│ │           │  │          │              │   │
│  │ └────────┘  └──────────┘  └──────────┘  └──────────┘              │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### WRITTEN_OFF - specjalny status

**Cel:** Zachować informację o transakcji która nie została opłacona, ale nie wpływa na statystyki.

```java
// WRITTEN_OFF transakcja:
// - money pozostaje oryginalną kwotą (dla historii)
// - effectiveAmount = 0 (dla statystyk)
// - status = WRITTEN_OFF
// - matchingAudit.action = "WRITTEN_OFF"
// - matchingAudit.reason = "Kontrahent nie zapłacił / Transakcja anulowana"

// W statystykach:
// - NIE liczy się do actual/expected
// - Widoczna w historii z oznaczeniem
// - Można filtrować: "Pokaż napisane off"
```

---

## Adapter Pattern - Bank Integration

### Architektura

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    BANK INTEGRATION ARCHITECTURE                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     BankDataProvider (interface)                     │   │
│  │                                                                     │   │
│  │  + getProviderName(): String                                        │   │
│  │  + getSupportedCountries(): List<Country>                           │   │
│  │  + supportsRealTime(): boolean                                      │   │
│  │                                                                     │   │
│  │  // Account operations                                              │   │
│  │  + getAccounts(connectionId): List<BankAccount>                     │   │
│  │  + getAccountBalance(connectionId, accountId): Money                │   │
│  │                                                                     │   │
│  │  // Transaction operations                                          │   │
│  │  + fetchTransactions(connectionId, accountId, dateRange):           │   │
│  │        List<BankTransactionDTO>                                     │   │
│  │                                                                     │   │
│  │  // Connection management                                           │   │
│  │  + initiateConnection(userId, bankId): ConnectionInitResult         │   │
│  │  + completeConnection(userId, authCode): ConnectionId               │   │
│  │  + refreshConnection(connectionId): void                            │   │
│  │                                                                     │   │
│  │  // Webhook (optional)                                              │   │
│  │  + registerWebhook(connectionId, callbackUrl): void                 │   │
│  │  + parseWebhookPayload(payload): List<BankTransactionDTO>           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    △                                        │
│                                    │ implements                             │
│          ┌─────────────────────────┼─────────────────────────┐             │
│          │                         │                         │             │
│          ▼                         ▼                         ▼             │
│  ┌───────────────┐        ┌───────────────┐        ┌───────────────┐       │
│  │ GoCardless    │        │    Tink       │        │  Future       │       │
│  │ Adapter       │        │   Adapter     │        │  Provider     │       │
│  │               │        │               │        │               │       │
│  │ - Free AIS    │        │ - Paid        │        │ - ...         │       │
│  │ - 2300+ banks │        │ - 6000+ banks │        │               │       │
│  │ - Basic data  │        │ - Enrichment  │        │               │       │
│  └───────────────┘        └───────────────┘        └───────────────┘       │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     BankDataProviderFactory                          │   │
│  │                                                                     │   │
│  │  + getProvider(providerType): BankDataProvider                      │   │
│  │  + getProviderForBank(bankId): BankDataProvider                     │   │
│  │  + getAvailableProviders(): List<BankDataProvider>                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     BankConnection (Entity)                          │   │
│  │                                                                     │   │
│  │  - connectionId: UUID                                               │   │
│  │  - userId: UserId                                                   │   │
│  │  - cashFlowId: CashFlowId                                           │   │
│  │  - providerType: ProviderType (GOCARDLESS, TINK, ...)              │   │
│  │  - bankId: String (bank identifier in provider)                     │   │
│  │  - accountId: String                                                │   │
│  │  - status: ACTIVE, EXPIRED, ERROR                                   │   │
│  │  - lastSyncAt: ZonedDateTime                                        │   │
│  │  - expiresAt: ZonedDateTime                                         │   │
│  │  - metadata: JsonNode                                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Konfiguracja providerów

```yaml
vidulum:
  bank-integration:
    providers:
      gocardless:
        enabled: true
        api-url: https://bankaccountdata.gocardless.com/api/v2
        secret-id: ${GOCARDLESS_SECRET_ID}
        secret-key: ${GOCARDLESS_SECRET_KEY}

      tink:
        enabled: false  # włącz gdy potrzebne
        api-url: https://api.tink.com
        client-id: ${TINK_CLIENT_ID}
        client-secret: ${TINK_CLIENT_SECRET}

    sync:
      batch-interval: PT4H          # co 4 godziny
      on-demand-enabled: true       # user może wymusić sync
      webhook-enabled: false        # real-time (przyszłość)
```

---

## Unified Data Ingestion Pipeline

### Jeden pipeline dla CSV i API

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    UNIFIED DATA INGESTION PIPELINE                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                         ŹRÓDŁA DANYCH                                 │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐             │  │
│  │  │  CSV Parser  │   │ Bank API     │   │  Webhook     │             │  │
│  │  │  (istniejący)│   │  Adapter     │   │  Handler     │             │  │
│  │  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘             │  │
│  │         │                  │                  │                      │  │
│  │         └──────────────────┼──────────────────┘                      │  │
│  │                            ▼                                         │  │
│  │              ┌──────────────────────────────┐                        │  │
│  │              │    BankTransactionDTO        │                        │  │
│  │              │    (canonical format)        │                        │  │
│  │              └──────────────┬───────────────┘                        │  │
│  │                             │                                        │  │
│  └─────────────────────────────┼────────────────────────────────────────┘  │
│                                │                                            │
│  ┌─────────────────────────────▼────────────────────────────────────────┐  │
│  │                    ETAP 1: DEDUPLIKACJA                               │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │  Sprawdź czy bankTransactionId już istnieje w:                       │  │
│  │  • staged_transactions (aktywna sesja)                               │  │
│  │  • CashChange.bankTransactionId (już zaimportowane)                  │  │
│  │                                                                      │  │
│  │  Jeśli istnieje:                                                     │  │
│  │  • staged → SKIP (duplikat w sesji)                                  │  │
│  │  • CashChange → UPDATE jeśli dane się zmieniły (audit)              │  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                │                                            │
│  ┌─────────────────────────────▼────────────────────────────────────────┐  │
│  │                    ETAP 2: RECONCILIATION                             │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │  Szukaj dopasowania do PENDING/FORECASTED:                           │  │
│  │                                                                      │  │
│  │  ┌────────────────────────────────────────────────────────────────┐ │  │
│  │  │                    SCORING ALGORITHM                            │ │  │
│  │  ├────────────────────────────────────────────────────────────────┤ │  │
│  │  │  1. counterpartyAccount match           = 50 pkt               │ │  │
│  │  │  2. amount within ±20%                  = 25 pkt               │ │  │
│  │  │  3. date within ±10 days                = 15 pkt               │ │  │
│  │  │  4. description pattern match           = 10 pkt               │ │  │
│  │  │  5. same category                       = 10 pkt (bonus)       │ │  │
│  │  │                                                                │ │  │
│  │  │  Threshold: 65+ = AUTO-MATCH                                   │ │  │
│  │  │  Threshold: 50-64 = SUGESTIA                                   │ │  │
│  │  │  Threshold: <50 = BRAK                                         │ │  │
│  │  └────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                      │  │
│  │  Match found (65+):                                                  │  │
│  │  • Merge bank data into PENDING/FORECASTED                          │  │
│  │  • Status → CONFIRMED                                                │  │
│  │  • Create matchingAudit                                              │  │
│  │  • DONE (nie idź do kategoryzacji)                                   │  │
│  │                                                                      │  │
│  │  Suggestion (50-64):                                                 │  │
│  │  • Create CashChange jako CONFIRMED (Uncategorized)                  │  │
│  │  • Create Alert: "Możliwe dopasowanie do [X]"                        │  │
│  │  • Kontynuuj do kategoryzacji                                        │  │
│  │                                                                      │  │
│  │  No match (<50):                                                     │  │
│  │  • Kontynuuj do kategoryzacji                                        │  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                │                                            │
│  ┌─────────────────────────────▼────────────────────────────────────────┐  │
│  │                    ETAP 3: KATEGORYZACJA                              │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │  Warstwy (w kolejności priorytetu):                                  │  │
│  │                                                                      │  │
│  │  ┌─ 1. HISTORIA - counterpartyAccount ─────────────────────────────┐│  │
│  │  │  Jeśli ten sam IBAN był już używany → użyj tej kategorii        ││  │
│  │  │  Confidence: 100%                                                ││  │
│  │  └──────────────────────────────────────────────────────────────────┘│  │
│  │                              ▼                                       │  │
│  │  ┌─ 2. HISTORIA - pattern w opisie ────────────────────────────────┐│  │
│  │  │  Jeśli podobny opis był już kategoryzowany → użyj tej kategorii ││  │
│  │  │  Confidence: 90%                                                 ││  │
│  │  └──────────────────────────────────────────────────────────────────┘│  │
│  │                              ▼                                       │  │
│  │  ┌─ 3. BANK CATEGORY (mapped) ─────────────────────────────────────┐│  │
│  │  │  Jeśli bank wysłał kategorię → mapuj do systemowej              ││  │
│  │  │  Używa CategoryMapping z bank_data_ingestion                    ││  │
│  │  │  Confidence: 85%                                                 ││  │
│  │  └──────────────────────────────────────────────────────────────────┘│  │
│  │                              ▼                                       │  │
│  │  ┌─ 4. AI CATEGORIZATION ──────────────────────────────────────────┐│  │
│  │  │  Wyślij do Claude API z kontekstem:                             ││  │
│  │  │  • Lista dostępnych kategorii (INFLOW lub OUTFLOW)              ││  │
│  │  │  • Opis transakcji                                              ││  │
│  │  │  • Kwota i typ                                                  ││  │
│  │  │                                                                 ││  │
│  │  │  Jeśli confidence > 80% → użyj                                  ││  │
│  │  │  Jeśli confidence < 80% → Uncategorized + alert                 ││  │
│  │  └──────────────────────────────────────────────────────────────────┘│  │
│  │                              ▼                                       │  │
│  │  ┌─ 5. DEFAULT: Uncategorized ─────────────────────────────────────┐│  │
│  │  │  Użyj "Uncategorized Income" lub "Uncategorized Expense"        ││  │
│  │  │  User skategoryzuje później                                     ││  │
│  │  └──────────────────────────────────────────────────────────────────┘│  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                │                                            │
│  ┌─────────────────────────────▼────────────────────────────────────────┐  │
│  │                    ETAP 4: ZAPIS                                      │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │  CSV Import:                                                         │  │
│  │  • Zapis do staged_transactions (istniejący flow)                    │  │
│  │  • User preview → approve → import do CashFlow                       │  │
│  │                                                                      │  │
│  │  Bank API (batch):                                                   │  │
│  │  • Zapis do staged_transactions                                      │  │
│  │  • Auto-approve po 24h (konfigurowalny)                              │  │
│  │  • Lub user preview jeśli woli                                       │  │
│  │                                                                      │  │
│  │  Webhook (real-time):                                                │  │
│  │  • Bezpośredni zapis do CashFlow jako CONFIRMED                      │  │
│  │  • Alert jeśli wymaga uwagi (sugestia/Uncategorized)                 │  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Reconciliation Engine

### Scoring Algorithm - szczegóły

```java
public class ReconciliationService {

    private static final int THRESHOLD_AUTO_MATCH = 65;
    private static final int THRESHOLD_SUGGESTION = 50;

    public ReconciliationResult reconcile(
            BankTransactionDTO bankTransaction,
            List<CashChange> candidates  // PENDING + FORECASTED z tego miesiąca
    ) {
        // Filtruj kandydatów - tylko ten sam typ (INFLOW/OUTFLOW)
        candidates = candidates.stream()
            .filter(c -> c.getType() == bankTransaction.type())
            .toList();

        if (candidates.isEmpty()) {
            return ReconciliationResult.noMatch();
        }

        // Oblicz score dla każdego kandydata
        List<ScoredCandidate> scored = candidates.stream()
            .map(c -> new ScoredCandidate(c, calculateScore(bankTransaction, c)))
            .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed())
            .toList();

        ScoredCandidate best = scored.get(0);

        if (best.score() >= THRESHOLD_AUTO_MATCH) {
            return ReconciliationResult.autoMatch(best.candidate(), best.score());
        } else if (best.score() >= THRESHOLD_SUGGESTION) {
            return ReconciliationResult.suggestion(best.candidate(), best.score());
        } else {
            return ReconciliationResult.noMatch();
        }
    }

    private int calculateScore(BankTransactionDTO bank, CashChange expected) {
        int score = 0;

        // 1. Counterparty account match (najsilniejsze)
        if (bank.counterpartyAccount() != null &&
            expected.getCounterpartyAccount() != null &&
            bank.counterpartyAccount().equals(expected.getCounterpartyAccount())) {
            score += 50;
        }

        // 2. Amount match (z tolerancją)
        double amountDiff = Math.abs(
            bank.amount().getAmount().doubleValue() -
            expected.getMoney().getAmount().doubleValue()
        ) / expected.getMoney().getAmount().doubleValue();

        if (amountDiff == 0) {
            score += 25;
        } else if (amountDiff <= 0.05) {
            score += 20;
        } else if (amountDiff <= 0.10) {
            score += 15;
        } else if (amountDiff <= 0.20) {
            score += 10;
        }

        // 3. Date match (z tolerancją)
        long daysDiff = Math.abs(ChronoUnit.DAYS.between(
            bank.transactionDate(),
            expected.getDueDate().toLocalDate()
        ));

        if (daysDiff == 0) {
            score += 15;
        } else if (daysDiff <= 3) {
            score += 12;
        } else if (daysDiff <= 7) {
            score += 10;
        } else if (daysDiff <= 10) {
            score += 5;
        }

        // 4. Description pattern match
        if (matchesPattern(bank.description(), expected.getName().value())) {
            score += 10;
        }

        // 5. Same category (bonus)
        if (expected.getCategoryName() != null &&
            !expected.getCategoryName().name().equals("Uncategorized")) {
            // Jeśli expected ma kategorię i bank ma tę samą (mapped)
            // TODO: implementacja mapowania kategorii bankowych
            score += 10;
        }

        return score;
    }
}
```

### Scenariusze - pozytywne i negatywne

#### Scenariusz 1: Idealne dopasowanie (score 100)

```
Bank: -2000 PLN, IBAN: PL12345..., "PRZELEW DO ZARZĄDCA"
Expected: Czynsz, 2000 PLN, due: 10 sty, counterparty: PL12345...

Score:
  counterpartyAccount match = 50
  amount exact = 25
  date exact = 15
  pattern "ZARZĄDCA" = 10
  TOTAL = 100

→ AUTO-MATCH
→ PENDING → CONFIRMED
→ Audit: {method: AUTO_COUNTERPARTY, score: 100}
```

#### Scenariusz 2: Dobre dopasowanie (score 75)

```
Bank: -2050 PLN, IBAN: PL12345..., "ZN SP ZOO"
Expected: Czynsz, 2000 PLN, due: 10 sty, counterparty: PL12345...

Score:
  counterpartyAccount match = 50
  amount +2.5% = 20
  date exact = 15
  pattern no match = 0
  TOTAL = 85

→ AUTO-MATCH (mimo różnicy kwoty!)
→ Audit: {method: AUTO_COUNTERPARTY, score: 85, note: "Amount differs by 2.5%"}
```

#### Scenariusz 3: Sugestia (score 55)

```
Bank: -2000 PLN, no IBAN, "CZYNSZ STYCZEŃ"
Expected: Czynsz, 2000 PLN, due: 10 sty

Score:
  counterpartyAccount = 0 (brak danych)
  amount exact = 25
  date ±5 dni = 12
  pattern "CZYNSZ" = 10
  same category = 10
  TOTAL = 57

→ SUGESTIA
→ Bank → CONFIRMED (Uncategorized)
→ Alert: "Czy 2000 PLN z 15 sty to Czynsz?"
→ User decyduje
```

#### Scenariusz 4: Brak dopasowania (score 20)

```
Bank: -150 PLN, "ALLEGRO *SELLER123"
Expected: Czynsz, 2000 PLN, due: 10 sty

Score:
  counterpartyAccount = 0
  amount -92.5% = 0
  date = 0
  pattern = 0
  TOTAL = 0

→ BRAK DOPASOWANIA
→ Bank → CONFIRMED
→ Kategoryzacja: AI → "Zakupy online" (confidence: 92%)
→ PENDING "Czynsz" pozostaje (czeka dalej)
```

#### Scenariusz 5: Błąd - duplikat

```
Bank: ID "TRX-123", -100 PLN
CashChange już istnieje z bankTransactionId = "TRX-123"

→ SKIP (duplikat)
→ Log: "Duplicate bank transaction TRX-123 ignored"
```

#### Scenariusz 6: Błąd - brak kategorii do mapowania

```
Bank: -500 PLN, bankCategory: "Nowa kategoria XYZ"
CategoryMapping nie istnieje dla "Nowa kategoria XYZ"

→ Kontynuuj do AI/Uncategorized
→ Alert: "Niezmapowana kategoria bankowa: XYZ"
```

#### Scenariusz 7: Koniec miesiąca - UNMATCHED

```
31 stycznia, batch job sprawdza:
PENDING: Czynsz, 2000 PLN, due: 10 sty - brak dopasowania

→ Status: PENDING → UNMATCHED
→ Alert do usera: "Nierozwiązane transakcje: 1"
```

#### Scenariusz 8: Kontrahent nie zapłacił - WRITTEN_OFF

```
User decyduje dla UNMATCHED "Faktura #123":
"Kontrahent nie zapłaci - oznacz jako stracone"

→ Status: UNMATCHED → WRITTEN_OFF
→ matchingAudit.action = "WRITTEN_OFF"
→ matchingAudit.reason = "Kontrahent nie zapłacił"
→ effectiveAmount = 0 (nie wpływa na statystyki)
→ money = 5000 PLN (oryginalna kwota zachowana w historii)
```

---

## AI Categorization

### Koszt i limity

| Provider | Model | Koszt/1K input | Koszt/1K output | Szacunek/transakcja |
|----------|-------|----------------|-----------------|---------------------|
| Claude | claude-3-haiku | $0.00025 | $0.00125 | ~$0.003 |
| Claude | claude-3-sonnet | $0.003 | $0.015 | ~$0.02 |
| OpenAI | gpt-4o-mini | $0.00015 | $0.0006 | ~$0.002 |

**Szacowany koszt miesięczny:**
- 100 transakcji wymagających AI: ~$0.30
- 500 transakcji: ~$1.50
- Cache podobnych → redukcja 50%+

### Prompt dla kategoryzacji

```
System: Jesteś asystentem do kategoryzacji transakcji bankowych.
Odpowiadaj TYLKO w formacie JSON.

User: Skategoryzuj tę transakcję bankową.

Transakcja:
- Opis: "{description}"
- Kwota: {amount} {currency}
- Typ: {type} (INFLOW = przychód, OUTFLOW = wydatek)
- Data: {date}
{counterpartyName ? "- Kontrahent: " + counterpartyName : ""}

Dostępne kategorie {type}:
{categories.map(c => "- " + c.name + (c.description ? ": " + c.description : "")).join("\n")}

Odpowiedz w formacie:
{
  "category": "nazwa kategorii z listy powyżej",
  "confidence": 0.0-1.0,
  "reasoning": "krótkie wyjaśnienie"
}

Jeśli nie jesteś pewny (confidence < 0.5), użyj "Uncategorized".
```

### Przykładowe odpowiedzi

```json
// Wysoka pewność
{
  "category": "Zakupy online",
  "confidence": 0.95,
  "reasoning": "ALLEGRO to popularna platforma e-commerce w Polsce"
}

// Średnia pewność
{
  "category": "Transport",
  "confidence": 0.72,
  "reasoning": "UBER może być transport lub jedzenie, ale kwota 45 PLN sugeruje przejazd"
}

// Niska pewność
{
  "category": "Uncategorized",
  "confidence": 0.35,
  "reasoning": "Opis 'PRZELEW 123456' jest zbyt ogólny do kategoryzacji"
}
```

### Cache dla AI

```java
public class AICategorization Cache {
    // Key: normalized description pattern
    // Value: category + confidence

    // Przykład:
    // "ALLEGRO*" → {category: "Zakupy online", confidence: 0.95}
    // "NETFLIX*" → {category: "Rozrywka", confidence: 0.98}
    // "SPOTIFY*" → {category: "Rozrywka", confidence: 0.97}

    // Logika:
    // 1. Normalize description (lowercase, remove numbers, trim)
    // 2. Check cache
    // 3. If hit && confidence > 0.9 → use cached
    // 4. If miss → call AI → cache result
}
```

---

## Soft Close - automatyczne zamykanie miesięcy

### Mechanizm

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SOFT CLOSE - AUTOMATYCZNE ZAMYKANIE                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Zamiast ręcznej atestacji:                                                 │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                       SCHEDULED JOB (cron)                           │   │
│  │                       Uruchamiany: 1-go każdego miesiąca             │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                     │   │
│  │  1. Zmień status poprzedniego miesiąca:                             │   │
│  │     ACTIVE → CLOSED (automatycznie)                                 │   │
│  │                                                                     │   │
│  │  2. Zmień status bieżącego miesiąca:                                │   │
│  │     FORECASTED → ACTIVE                                             │   │
│  │                                                                     │   │
│  │  3. Wygeneruj nowy miesiąc na końcu horyzontu:                      │   │
│  │     Nowy FORECASTED (month +12)                                     │   │
│  │                                                                     │   │
│  │  4. Sprawdź PENDING/FORECASTED bez dopasowania:                     │   │
│  │     → Status: UNMATCHED                                             │   │
│  │     → Wyślij alert do usera                                         │   │
│  │                                                                     │   │
│  │  5. Wygeneruj FORECASTED z RecurringRules:                          │   │
│  │     Dla nowego miesiąca na horyzoncie                               │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                       CashFlowMonthlyForecast.Status                 │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                     │   │
│  │  IMPORT_PENDING  →  (import historyczny)  →  IMPORTED               │   │
│  │                                                                     │   │
│  │  FORECASTED  →  (1-go miesiąca)  →  ACTIVE                          │   │
│  │                                                                     │   │
│  │  ACTIVE  →  (1-go następnego miesiąca)  →  CLOSED                   │   │
│  │                                                                     │   │
│  │  CLOSED = zamknięty automatycznie (odpowiednik ATTESTED)            │   │
│  │                                                                     │   │
│  │  UWAGA: Usunięto ATTESTED (ręczna atestacja)                        │   │
│  │         Zamieniono na CLOSED (automatyczne)                         │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Różnice: Atestacja vs Soft Close

| Aspekt | Atestacja (stare) | Soft Close (nowe) |
|--------|-------------------|-------------------|
| Zamknięcie miesiąca | Ręczne (user klika) | Automatyczne (1-go) |
| PENDING bez dopasowania | Przeniesione do następnego miesiąca | UNMATCHED (wymaga decyzji) |
| Edycja historii | Zablokowana po atestacji | Dozwolona (z audit trail) |
| Wymagana akcja usera | Tak (kliknij "Zamknij") | Nie (chyba że UNMATCHED) |
| Saldo końcowe | User potwierdza | Bank jest source of truth |

---

## RecurringRule Aggregate

### Model

```java
public class RecurringRule {
    private RecurringRuleId ruleId;
    private CashFlowId cashFlowId;
    private UserId createdBy;

    // === Co generować ===
    private Name name;                      // "Czynsz"
    private Description description;        // "Opłata za mieszkanie"
    private Money amount;                   // 2000 PLN
    private Type type;                      // OUTFLOW
    private CategoryName categoryName;      // "Mieszkanie"

    // === Kiedy generować ===
    private Frequency frequency;            // MONTHLY, WEEKLY, YEARLY, etc.
    private Integer dayOfMonth;             // 1-28 lub -1 (ostatni dzień)
    private DayOfWeek dayOfWeek;            // dla WEEKLY
    private Integer monthOfYear;            // dla YEARLY (1-12)

    // === Okres obowiązywania ===
    private LocalDate startDate;
    private LocalDate endDate;              // nullable (nieskończona)
    private Integer maxOccurrences;         // nullable

    // === Wykluczenia ===
    private List<Integer> activeMonths;     // [1,2,3,4,5,9,10,11,12] dla przedszkola
    private List<LocalDate> excludedDates;  // konkretne daty do pominięcia

    // === Matching (dla reconciliation) ===
    private String counterpartyAccount;     // IBAN odbiorcy (KLUCZOWE!)
    private Money amountTolerance;          // ±50 PLN
    private Integer dateTolerance;          // ±5 dni
    private List<String> matchingPatterns;  // ["CZYNSZ", "ZARZĄDCA"]

    // === Status ===
    private RuleStatus status;              // ACTIVE, PAUSED, ENDED
    private ZonedDateTime createdAt;
    private ZonedDateTime lastModifiedAt;
    private YearMonth lastGeneratedPeriod;  // do którego miesiąca wygenerowano
}

public enum Frequency {
    WEEKLY,         // co tydzień
    BIWEEKLY,       // co 2 tygodnie
    MONTHLY,        // co miesiąc
    QUARTERLY,      // co kwartał
    YEARLY          // co rok
}

public enum RuleStatus {
    ACTIVE,         // generuje transakcje
    PAUSED,         // tymczasowo wstrzymana
    ENDED           // zakończona (endDate minął lub maxOccurrences osiągnięte)
}
```

### Generowanie CashChange z reguł

```java
public class RecurringRuleProcessor {

    /**
     * Uruchamiany 1-go każdego miesiąca (po soft close)
     * Generuje FORECASTED dla nowego miesiąca na horyzoncie
     */
    @Scheduled(cron = "0 5 0 1 * *")  // 00:05 1-go każdego miesiąca
    public void generateForNewMonth() {
        YearMonth newMonth = YearMonth.now().plusMonths(12);  // nowy na horyzoncie

        List<RecurringRule> activeRules = ruleRepository.findAllActive();

        for (RecurringRule rule : activeRules) {
            if (shouldGenerateForMonth(rule, newMonth)) {
                CashChange forecasted = createForecastedCashChange(rule, newMonth);
                cashFlowService.appendForecastedCashChange(forecasted);

                rule.setLastGeneratedPeriod(newMonth);
                ruleRepository.save(rule);
            }
        }
    }

    private boolean shouldGenerateForMonth(RecurringRule rule, YearMonth month) {
        // 1. Czy reguła jest aktywna?
        if (rule.getStatus() != RuleStatus.ACTIVE) return false;

        // 2. Czy miesiąc jest w zakresie dat?
        LocalDate monthStart = month.atDay(1);
        if (rule.getStartDate().isAfter(monthStart)) return false;
        if (rule.getEndDate() != null && rule.getEndDate().isBefore(monthStart)) return false;

        // 3. Czy miesiąc jest w activeMonths?
        if (rule.getActiveMonths() != null &&
            !rule.getActiveMonths().contains(month.getMonthValue())) {
            return false;
        }

        // 4. Czy już wygenerowano?
        if (rule.getLastGeneratedPeriod() != null &&
            !rule.getLastGeneratedPeriod().isBefore(month)) {
            return false;
        }

        return true;
    }

    private CashChange createForecastedCashChange(RecurringRule rule, YearMonth month) {
        LocalDate dueDate = calculateDueDate(rule, month);

        return CashChange.builder()
            .cashChangeId(CashChangeId.generate())
            .name(rule.getName())
            .description(rule.getDescription())
            .money(rule.getAmount())
            .type(rule.getType())
            .categoryName(rule.getCategoryName())
            .status(CashChangeStatus.FORECASTED)
            .source(CashChangeSource.RECURRING_RULE)
            .recurringRuleId(rule.getRuleId())
            .dueDate(dueDate.atStartOfDay(ZoneId.systemDefault()))
            .counterpartyAccount(rule.getCounterpartyAccount())  // dla matching!
            .created(ZonedDateTime.now())
            .build();
    }
}
```

---

## UI Messages & User Flows

### Dashboard - Soft Close Alert

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DASHBOARD - Luty 2026                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ⚠️  NIEROZWIĄZANE TRANSAKCJE (3)                    [Rozwiąż teraz] │   │
│  │                                                                     │   │
│  │  Styczeń został zamknięty automatycznie.                            │   │
│  │  Te transakcje nie zostały dopasowane do danych bankowych:          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  📋 Czynsz                                      2,000 PLN  EXPECTED  │   │
│  │     Planowano: 10 stycznia                                          │   │
│  │                                                                     │   │
│  │     🔍 Możliwe dopasowania z banku:                                 │   │
│  │     ┌─────────────────────────────────────────────────────────────┐│   │
│  │     │ ○ 2,050 PLN, 12 sty, "ZARZĄDCA NIERUCHOMOŚCI"  (score: 72)  ││   │
│  │     │ ○ 2,000 PLN, 15 sty, "PRZELEW DO ZN SP"        (score: 65)  ││   │
│  │     └─────────────────────────────────────────────────────────────┘│   │
│  │                                                                     │   │
│  │     Lub wybierz akcję:                                              │   │
│  │     ┌─────────────────────────────────────────────────────────────┐│   │
│  │     │ ○ Przesuń na luty (zmień termin płatności)                  ││   │
│  │     │ ○ Oznacz jako nieopłacone (WRITTEN_OFF)                     ││   │
│  │     │ ○ Usuń tę transakcję (REJECTED)                             ││   │
│  │     └─────────────────────────────────────────────────────────────┘│   │
│  │                                                                     │   │
│  │     💡 AI sugeruje: Dopasuj do "2,050 PLN, ZARZĄDCA" (87%)         │   │
│  │                                                                     │   │
│  │                                       [Zastosuj sugestię] [Pomiń]  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  📋 Faktura #456                                5,000 PLN  EXPECTED  │   │
│  │     Planowano: 15 stycznia (od kontrahenta ABC Sp. z o.o.)          │   │
│  │                                                                     │   │
│  │     ❌ Brak pasujących transakcji w banku                           │   │
│  │                                                                     │   │
│  │     Wybierz akcję:                                                  │   │
│  │     ┌─────────────────────────────────────────────────────────────┐│   │
│  │     │ ○ Przesuń na luty - kontrahent zapłaci później              ││   │
│  │     │ ○ Kontrahent nie zapłaci (WRITTEN_OFF) ⚠️                   ││   │
│  │     │ ○ Błędna transakcja - usuń (REJECTED)                       ││   │
│  │     │ ○ Dopasuj ręcznie do innej transakcji                       ││   │
│  │     └─────────────────────────────────────────────────────────────┘│   │
│  │                                                                     │   │
│  │                                                  [Zatwierdź wybór]  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  📋 Netflix                                        52 PLN  FORECASTED│   │
│  │     Z reguły: "Subskrypcja Netflix", cyklicznie co miesiąc          │   │
│  │                                                                     │   │
│  │     🔍 Znaleziono dopasowanie:                                      │   │
│  │     ┌─────────────────────────────────────────────────────────────┐│   │
│  │     │ ✓ 52 PLN, 5 sty, "NETFLIX.COM"               (score: 95)    ││   │
│  │     └─────────────────────────────────────────────────────────────┘│   │
│  │                                                                     │   │
│  │     Automatycznie dopasowane, ale czeka na Twoją weryfikację.       │   │
│  │                                                                     │   │
│  │                                     [Potwierdź] [Zmień dopasowanie] │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### WRITTEN_OFF - Dialog potwierdzenia

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ⚠️ OZNACZ JAKO NIEOPŁACONE                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Czy na pewno chcesz oznaczyć tę transakcję jako nieopłaconą?              │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Faktura #456                                                       │   │
│  │  Kwota: 5,000 PLN                                                   │   │
│  │  Planowano: 15 stycznia 2026                                        │   │
│  │  Kontrahent: ABC Sp. z o.o.                                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Co to oznacza:                                                            │
│  • Transakcja zostanie oznaczona statusem WRITTEN_OFF                      │
│  • NIE wpłynie na saldo ani statystyki (kwota = 0 w obliczeniach)         │
│  • Pozostanie widoczna w historii dla celów audytu                        │
│  • Możesz później zmienić decyzję                                         │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Powód (opcjonalny):                                                │   │
│  │  ┌─────────────────────────────────────────────────────────────────┐│   │
│  │  │ Kontrahent nie zapłacił - windykacja nieudana                   ││   │
│  │  └─────────────────────────────────────────────────────────────────┘│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                                            [Anuluj]  [Oznacz jako stratę]  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Lista transakcji do kategoryzacji

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TRANSAKCJE DO KATEGORYZACJI (12)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Filtruj: [Wszystkie ▼]  [Styczeń 2026 ▼]  [Wydatki ▼]                     │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Data       │ Opis                      │ Kwota    │ Kategoria       │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ 15 sty     │ ALLEGRO *SELLER123        │ -150 PLN │ [Wybierz... ▼]  │   │
│  │            │ 💡 AI: Zakupy online (92%)│          │                 │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ 14 sty     │ BOLT WARSAW               │ -25 PLN  │ [Wybierz... ▼]  │   │
│  │            │ 💡 AI: Transport (88%)    │          │                 │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ 12 sty     │ PRZELEW 987654            │ -500 PLN │ [Wybierz... ▼]  │   │
│  │            │ ❓ AI: Nie jestem pewny   │          │                 │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ ...                                                                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Akcje zbiorcze:                                                           │
│  [Zastosuj wszystkie sugestie AI]  [Ustaw jako "Inne"]                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Powiadomienie e-mail (opcjonalne)

```
Temat: ⚠️ Vidulum: 3 nierozwiązane transakcje ze stycznia

Cześć [Imię],

Styczeń 2026 został automatycznie zamknięty.

Masz 3 transakcje które wymagają Twojej uwagi:

1. Czynsz (2,000 PLN) - brak dopasowania
   → System znalazł możliwe dopasowanie: 2,050 PLN z 12 sty

2. Faktura #456 (5,000 PLN) - brak płatności
   → Kontrahent nie zapłacił?

3. Netflix (52 PLN) - oczekuje weryfikacji
   → Automatycznie dopasowane, potwierdź

[Rozwiąż teraz →]

---
Vidulum - Inteligentny Cash Flow
```

---

## Scenariusze - pozytywne i negatywne

### Scenariusz A: Happy Path - wszystko działa

```
1. User tworzy RecurringRule "Czynsz" z counterpartyAccount = PL12345...
2. System generuje FORECASTED dla każdego miesiąca
3. Bank wysyła transakcję: -2000 PLN, IBAN: PL12345...
4. Reconciliation: score 100 → AUTO-MATCH
5. FORECASTED → CONFIRMED
6. User nie musi nic robić ✓
```

### Scenariusz B: Różnica kwoty - akceptowalna

```
1. FORECASTED: Czynsz 2000 PLN
2. Bank: -2100 PLN (podwyżka czynszu)
3. Reconciliation: counterparty match (50) + amount ±5% (20) = 70 → AUTO-MATCH
4. Automatyczne dopasowanie z notatką: "Kwota różni się o 5%"
5. User widzi powiadomienie, może zaktualizować RecurringRule
```

### Scenariusz C: Brak IBAN - sugestia

```
1. EXPECTED: "Zakupy w Biedronce" 150 PLN
2. Bank: -155 PLN, "BIEDRONKA SKLEP 123" (brak IBAN)
3. Reconciliation: amount (15) + pattern (10) = 25 → BRAK
4. Bank → CONFIRMED (Uncategorized)
5. Alert: "Czy 155 PLN to Zakupy w Biedronce?"
6. User decyduje
```

### Scenariusz D: Koniec miesiąca bez rozwiązania

```
1. EXPECTED: Faktura 5000 PLN
2. Brak transakcji bankowej
3. 1 lutego: Soft Close → EXPECTED → UNMATCHED
4. Alert na dashboardzie
5. User decyduje:
   - Przesuń na luty (kontrahent zapłaci)
   - WRITTEN_OFF (kontrahent nie zapłaci)
   - REJECTED (błędna transakcja)
```

### Scenariusz E: Podwójne naliczenie (konflikt)

```
1. EXPECTED: Netflix 52 PLN
2. Bank: -52 PLN, "NETFLIX"
3. Reconciliation: score 85 → AUTO-MATCH
4. EXPECTED → CONFIRMED (matched)

Następnego dnia:
5. Bank: -52 PLN, "NETFLIX" (duplikat z banku?)
6. Deduplikacja: bankTransactionId różny!
7. System tworzy nowy CONFIRMED (Uncategorized)
8. Alert: "Podwójna transakcja Netflix?"
9. User sprawdza i decyduje
```

### Scenariusz F: Bank API niedostępny

```
1. Scheduled sync o 4:00
2. Bank API: timeout / 503 error
3. Retry po 1h, 2h, 4h
4. Po 3 nieudanych próbach: Alert do usera
5. "Nie można połączyć z bankiem. Sprawdź status połączenia."
6. User może:
   - Wymusić ręczny sync
   - Sprawdzić dane logowania
   - Poczekać na automatyczny retry
```

### Scenariusz G: Nowa kategoria bankowa

```
1. Bank wysyła: category = "Nowa kategoria XYZ"
2. CategoryMapping nie istnieje
3. System:
   a) Próbuje AI kategoryzację
   b) Jeśli confidence > 80%: użyj AI kategorii
   c) Jeśli confidence < 80%: Uncategorized + alert
4. Alert: "Nieznana kategoria bankowa: XYZ. Skonfiguruj mapowanie."
```

---

## Następne kroki

### Faza 1: Rozszerzenie modelu danych
- [ ] Dodać nowe pola do CashChange (bankTransactionId, counterpartyAccount, etc.)
- [ ] Dodać MatchingAudit record
- [ ] Dodać CashChangeSource enum
- [ ] Rozszerzyć CashChangeStatus o UNMATCHED, WRITTEN_OFF

### Faza 2: RecurringRule Aggregate
- [ ] Zdefiniować RecurringRule entity
- [ ] Zaimplementować RecurringRuleProcessor (scheduler)
- [ ] API do CRUD reguł
- [ ] Generowanie FORECASTED

### Faza 3: Reconciliation Engine
- [ ] Zaimplementować scoring algorithm
- [ ] Integracja z istniejącym pipeline'em
- [ ] Auto-match + sugestie

### Faza 4: AI Categorization
- [ ] Integracja z Claude API
- [ ] Prompty i cache
- [ ] Fallback do Uncategorized

### Faza 5: Soft Close
- [ ] Scheduler dla automatycznego zamykania
- [ ] UNMATCHED handling
- [ ] Usunięcie starej atestacji (opcjonalnie zachować jako legacy)

### Faza 6: Bank API Integration
- [ ] Adapter interface
- [ ] GoCardless implementation (najpierw)
- [ ] Tink implementation (później)
- [ ] Connection management

### Faza 7: UI
- [ ] Dashboard z alertami
- [ ] Lista do kategoryzacji
- [ ] Dialog UNMATCHED
- [ ] Konfiguracja RecurringRules

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2026-02-07 | Utworzenie dokumentu |

