# VID-UNIFIED-AI-IMPORT: Complete AI-Powered CSV Import

**Status:** Design Phase
**Priority:** HIGH
**Complexity:** HIGH

---

## ⚠️ Konwencja wartości amount

> **WAŻNE:** W systemie Vidulum wartości `amount` i `money` są **ZAWSZE DODATNIE**.
> Kierunek transakcji (wpływ/wydatek) określa pole `type` (INFLOW/OUTFLOW).
>
> ```
> ✅ POPRAWNIE:  { amount: 3000.00, type: OUTFLOW }  // wydatek 3000 PLN
> ❌ BŁĘDNIE:    { amount: -3000.00 }                 // NIGDY ujemne!
> ```
>
> **Wyjątek:** W raportach/wizualizacjach (np. `totalAmount`, `categorizedOutFlows`)
> wartości mogą być ujemne dla czytelności prezentacji użytkownikowi.

---

## Current System Analysis (EXISTING ENDPOINTS)

### Module Architecture (Separation of Concerns)

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    ARCHITEKTURA MODUŁÓW - CELOWE ROZDZIELENIE                            │
└─────────────────────────────────────────────────────────────────────────────────────────┘

Projekt stosuje SEPARATION OF CONCERNS - moduły są celowo rozdzielone:

┌─────────────────────────────────┐          ┌─────────────────────────────────┐
│      bank_data_adapter          │          │      bank_data_ingestion        │
│                                 │          │                                 │
│  ODPOWIEDZIALNOŚĆ:              │          │  ODPOWIEDZIALNOŚĆ:              │
│  • CSV format detection         │          │  • Staging transactions         │
│  • AI/Cache transformation      │          │  • Category mappings            │
│  • Normalize to BankCsvRow      │          │  • Import to CashFlow           │
│                                 │          │  • Validation & deduplication   │
│  STORAGE:                       │   REST   │  STORAGE:                       │
│  ai_csv_transformations         │ ───────► │  staged_transactions            │
│  (transformationId)             │   HTTP   │  category_mappings              │
│                                 │          │  import_jobs                    │
│  SCOPE: per User                │          │  SCOPE: per CashFlow            │
└─────────────────────────────────┘          └─────────────────────────────────┘
         │                                             ▲
         │ BankDataIngestionClient                     │
         │ POST /cf={id}/upload                        │
         └─────────────────────────────────────────────┘

KOMUNIKACJA MIĘDZY MODUŁAMI:
────────────────────────────
1. User → POST /csv-import/upload → bank_data_adapter
   └── Zwraca: transformationId

2. User → POST /bank-data-adapter/{transformationId}/import
   └── bank_data_adapter → POST /bank-data-ingestion/cf={id}/upload
   └── Zwraca: stagingSessionId (z bank_data_ingestion)

3. User → POST /bank-data-ingestion/cf={id}/import
   └── Używa stagingSessionId do importu
```

### Existing Endpoints Map

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                        ISTNIEJĄCE ENDPOINTY - PEŁNA MAPA                                │
└─────────────────────────────────────────────────────────────────────────────────────────┘

MODUŁ: bank_data_adapter
────────────────────────
┌───────────────────────────────────────────────────────────────────────────────┐
│ POST /api/v1/csv-import/upload                                                │
│ ├── Unified CSV Import Controller                                             │
│ ├── Przyjmuje: MultipartFile (CSV), bankHint (opcjonalnie)                   │
│ ├── Zwraca: UploadResponse                                                    │
│ │   ├── transformationId (UUID)                                               │
│ │   ├── detectionResult: CANONICAL | CACHED | AI_TRANSFORMED                  │
│ │   ├── detectedBank, detectedCurrency, detectedLanguage, detectedCountry    │
│ │   ├── suggestedStartPeriod (YYYY-MM) ← KLUCZOWE dla CashFlow               │
│ │   ├── monthsOfData, monthsCovered[], minTransactionDate, maxTransactionDate│
│ │   ├── bankCategories[] ← kategorie z CSV do mappingu                        │
│ │   └── importStatus: PENDING                                                 │
│ │                                                                             │
│ └── Wewnętrzny flow:                                                          │
│     ├── AiBankCsvTransformService.transform()                                 │
│     ├── isCanonicalFormat() → jeśli tak, CANONICAL (instant, FREE)           │
│     ├── checkCache(bankIdentifier) → jeśli hit, CACHED (instant, FREE)       │
│     └── obtainMappingRulesFromAi() → AI_TRANSFORMED (5-15s, ~$0.01)          │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /api/v1/bank-data-adapter/{transformationId}                              │
│ ├── Pobiera szczegóły transformacji                                           │
│ └── Zwraca: TransformResponse (full transformation details)                  │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /api/v1/bank-data-adapter/{transformationId}/preview                      │
│ ├── Podgląd pierwszych 10 wierszy przetworzonego CSV                         │
│ └── Zwraca: PreviewResponse { id, detectedBank, rowCount, previewLines[] }   │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /api/v1/bank-data-adapter/{transformationId}/download                     │
│ ├── Pobiera pełny przetransformowany CSV (format BankCsvRow)                 │
│ ├── Content-Type: text/csv                                                    │
│ └── Zwraca: CSV file jako attachment                                          │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /api/v1/bank-data-adapter/{transformationId}/import      ← KLUCZOWY!    │
│ ├── Wysyła transformację do bank-data-ingestion i tworzy staging session     │
│ ├── Przyjmuje: { cashFlowId }                                                 │
│ ├── Wewnętrznie: BankDataIngestionClient.sendToIngestion()                   │
│ │   └── POST /api/v1/bank-data-ingestion/cf={cashFlowId}/upload              │
│ ├── Aktualizuje: importStatus = IMPORTED, stagingSessionId = ...             │
│ └── Zwraca: ImportResponse { transformationId, stagingSessionId, ... }       │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /api/v1/bank-data-adapter/history                                         │
│ └── Zwraca: List<TransformHistoryItem> (wszystkie transformacje usera)       │
└───────────────────────────────────────────────────────────────────────────────┘

MODUŁ: cashflow
───────────────
┌───────────────────────────────────────────────────────────────────────────────┐
│ POST /cash-flow/with-history                                                  │
│ ├── Tworzy CashFlow w trybie SETUP (dla importu historycznego)               │
│ ├── Przyjmuje:                                                                │
│ │   ├── userId, name, description                                             │
│ │   ├── bankAccount: { bankName, accountNumber, currency, balance }          │
│ │   ├── startPeriod (YYYY-MM) ← z suggestedStartPeriod!                       │
│ │   └── initialBalance: Money                                                 │
│ └── Zwraca: cashFlowId                                                        │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /cash-flow/cf={cashFlowId}/category                                      │
│ ├── Tworzy kategorię w CashFlow                                               │
│ ├── Przyjmuje: categoryName, type (INFLOW/OUTFLOW), parent (opcjonalnie)     │
│ └── Zwraca: void                                                              │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /cash-flow/cf={cashFlowId}/import-historical                             │
│ ├── Importuje pojedynczą transakcję historyczną                               │
│ ├── Tylko w trybie SETUP                                                      │
│ ├── Przyjmuje: name, description, category, money, type, dueDate, paidDate   │
│ └── Zwraca: cashChangeId                                                      │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /cash-flow/cf={cashFlowId}/attest-historical-import                      │
│ ├── Potwierdza import i zmienia status SETUP → OPEN                          │
│ ├── Weryfikuje bilans (calculated vs confirmed)                               │
│ ├── Przyjmuje: confirmedBalance, createAdjustment, forceAttestation          │
│ └── Zwraca: AttestHistoricalImportResponse (balance info, status)            │
├───────────────────────────────────────────────────────────────────────────────┤
│ DELETE /cash-flow/cf={cashFlowId}/import                                      │
│ ├── Rollback importu (usuwa transakcje, opcjonalnie kategorie)               │
│ └── CashFlow pozostaje w SETUP                                                │
└───────────────────────────────────────────────────────────────────────────────┘

MODUŁ: bank_data_ingestion
──────────────────────────
┌───────────────────────────────────────────────────────────────────────────────┐
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/mappings                     │
│ ├── Konfiguruje mapowanie: bankCategory → cashFlowCategory                    │
│ ├── Przyjmuje: mappings[] { bankCategoryName, targetCategoryName,            │
│ │                           parentCategoryName, categoryType, action }        │
│ │   action: CREATE_NEW | MAP_TO_EXISTING | MAP_TO_UNCATEGORIZED              │
│ └── Zwraca: ConfigureMappingsResponse                                         │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /api/v1/bank-data-ingestion/cf={cashFlowId}/mappings                      │
│ └── Zwraca wszystkie mapowania dla CashFlow                                   │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging                      │
│ ├── Tworzy staging session z transakcjami                                     │
│ ├── Waliduje i aplikuje mapowania                                             │
│ ├── Przyjmuje: transactions[] { bankTransactionId, name, description,        │
│ │                               bankCategory, amount, currency, type, paidDate}│
│ └── Zwraca: StageTransactionsResponse                                         │
│     ├── stagingSessionId, status, expiresAt (24h TTL)                        │
│     ├── summary: { total, valid, invalid, duplicates }                        │
│     ├── categoryBreakdown[] ← ile transakcji na kategorię                     │
│     ├── categoriesToCreate[] ← nowe kategorie do stworzenia                   │
│     ├── monthlyBreakdown[] ← inflow/outflow per miesiąc                       │
│     └── unmappedCategories[] ← brak mapowania, wymaga konfiguracji            │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/upload                       │
│ ├── Upload CSV + automatyczny staging                                         │
│ ├── Parsuje BankCsvRow format (canonical CSV)                                 │
│ └── Zwraca: UploadCsvResponse { parseSummary, stagingResult }                │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}           │
│ └── Podgląd staged transactions                                               │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}/revalidate│
│ ├── Po dodaniu mappingów - ponowna walidacja                                  │
│ └── PENDING_MAPPING → VALID (jeśli mapping teraz istnieje)                   │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/import                       │
│ ├── Uruchamia import job                                                      │
│ ├── Fazy: CREATING_CATEGORIES → IMPORTING_TRANSACTIONS → COMPLETED           │
│ ├── Przyjmuje: { stagingSessionId }                                           │
│ └── Zwraca: StartImportResponse { jobId, status, progress, pollUrl }         │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /api/v1/bank-data-ingestion/cf={cashFlowId}/import/{jobId}                │
│ └── Status i progress importu (polling)                                       │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/import/{jobId}/finalize      │
│ └── Czyści staging data, opcjonalnie mappingi                                 │
├───────────────────────────────────────────────────────────────────────────────┤
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/import/{jobId}/rollback      │
│ └── Usuwa zaimportowane transakcje i kategorie                                │
└───────────────────────────────────────────────────────────────────────────────┘

MODUŁ: cashflow_forecast_processor
──────────────────────────────────
┌───────────────────────────────────────────────────────────────────────────────┐
│ GET /cash-flow-forecast/cf={cashFlowId}                                       │
│ ├── Zwraca CashFlowForecastStatement                                          │
│ │   ├── forecasts: Map<YearMonth, CashFlowMonthlyForecast>                   │
│ │   ├── categoryStructure (inflow/outflow z nested)                          │
│ │   └── attestation info                                                      │
│ └── Każdy miesiąc ma status: IMPORT_PENDING | IMPORTED | ACTIVE | FORECASTED │
├───────────────────────────────────────────────────────────────────────────────┤
│ GET /cash-flow-forecast/cf={cashFlowId}/month-statuses                        │
│ └── Mapa YearMonth → ForecastMonthStatus (dla bank-data-ingestion)           │
└───────────────────────────────────────────────────────────────────────────────┘
```

### Current Full Flow (Step by Step)

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│              OBECNY FLOW: OD CSV DO AKTYWNEGO CASHFLOW (KROK PO KROKU)                  │
└─────────────────────────────────────────────────────────────────────────────────────────┘

KROK 1: Upload CSV i transformacja do canonical format
═══════════════════════════════════════════════════════

User → POST /api/v1/csv-import/upload (file=nest_bank.csv, bankHint="Nest Bank")
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ AiBankCsvTransformService.transform()                                                    │
│                                                                                          │
│ 1. validateFile() - sprawdź size < 5MB, .csv extension, not empty                       │
│ 2. calculateHash() - SHA-256 dla deduplikacji                                            │
│ 3. checkDuplicate() - czy ten hash już istnieje dla tego usera?                         │
│ 4. isCanonicalFormat() - sprawdź czy już ma nagłówki BankCsvRow                         │
│    │                                                                                     │
│    ├─ TAK → DetectionResult.CANONICAL (instant, free)                                   │
│    │        └── handleCanonicalFormat() - tylko walidacja i statystyki                  │
│    │                                                                                     │
│    └─ NIE → Continue                                                                    │
│                                                                                          │
│ 5. computeBankIdentifier() - hash struktury CSV (kolumny, separatory)                   │
│ 6. checkCache(bankIdentifier) - czy mamy cached mapping rules?                          │
│    │                                                                                     │
│    ├─ HIT → DetectionResult.CACHED (instant, free)                                      │
│    │        └── localCsvTransformer.transform(csv, cachedRules)                         │
│    │                                                                                     │
│    └─ MISS → obtainMappingRulesFromAi()                                                 │
│              │                                                                           │
│              ├── csvAnonymizer.anonymizeAndSample(csv, 10 rows)                         │
│              ├── AI call: "Stwórz mapping rules dla tego CSV"                           │
│              ├── mappingRulesProcessor.process(aiOutput)                                │
│              ├── mappingRulesCacheService.save(rules) ← cache na przyszłość            │
│              └── localCsvTransformer.transform(csv, newRules)                           │
│                                                                                          │
│ 7. extractDateRangeAndUpdate() - min/max date, suggestedStartPeriod                     │
│ 8. transformationRepository.save() - zapisz do MongoDB (ai_csv_transformations)        │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
Response: {
  transformationId: "abc123",
  detectionResult: "CACHED",
  detectedBank: "Nest Bank",
  detectedCurrency: "PLN",
  suggestedStartPeriod: "2023-01",    ← WAŻNE: użyj tego do CashFlow!
  monthsOfData: 36,
  rowCount: 402,
  bankCategories: [
    { name: "Przelewy wychodzące", count: 150, type: "OUTFLOW" },
    { name: "Opłaty i prowizje", count: 50, type: "OUTFLOW" },
    { name: "Przelewy przychodzące", count: 12, type: "INFLOW" }
  ]
}

KROK 2: Stworzenie CashFlow w trybie SETUP
══════════════════════════════════════════

User → POST /cash-flow/with-history
       Body: {
         userId: "U123",
         name: "Nest Bank - Konto główne",
         description: "...",
         bankAccount: { ... },
         startPeriod: "2023-01",          ← z suggestedStartPeriod!
         initialBalance: { amount: 14484.19, currency: "PLN" }
       }
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ CreateCashFlowWithHistoryCommandHandler                                                  │
│                                                                                          │
│ 1. Stwórz CashFlow aggregate w statusie SETUP                                           │
│ 2. Ustaw startPeriod (2023-01) i activePeriod (bieżący miesiąc)                        │
│ 3. Ustaw initialBalance                                                                  │
│ 4. Stwórz domyślne kategorie "Uncategorized" (SYSTEM origin)                            │
│ 5. Emit CashFlowWithHistoryCreatedEvent → Kafka                                         │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastProcessor (Kafka consumer)                                               │
│                                                                                          │
│ CashFlowWithHistoryCreatedEventHandler:                                                  │
│ 1. Stwórz CashFlowForecastStatement                                                      │
│ 2. Dla każdego miesiąca od startPeriod do activePeriod-1:                               │
│    └── stwórz CashFlowMonthlyForecast z status = IMPORT_PENDING                         │
│ 3. Dla activePeriod: status = ACTIVE                                                     │
│ 4. Zapisz do MongoDB (cash_flow_forecast_statement)                                      │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
Response: cashFlowId = "CF10000123"

KROK 3: Konfiguracja mapowania kategorii (MANUALNIE!)
════════════════════════════════════════════════════

⚠️ TO JEST MIEJSCE GDZIE AI MOŻE POMÓC!

User → POST /api/v1/bank-data-ingestion/cf=CF10000123/mappings
       Body: {
         mappings: [
           {
             bankCategoryName: "Przelewy wychodzące",    ← z CSV bankCategory
             targetCategoryName: "Przelewy",             ← kategoria w CashFlow
             parentCategoryName: null,
             categoryType: "OUTFLOW",
             action: "CREATE_NEW"
           },
           {
             bankCategoryName: "Opłaty i prowizje",
             targetCategoryName: "Opłaty bankowe",
             categoryType: "OUTFLOW",
             action: "CREATE_NEW"
           },
           {
             bankCategoryName: "Przelewy przychodzące",
             targetCategoryName: "Przychody",
             categoryType: "INFLOW",
             action: "CREATE_NEW"
           }
         ]
       }
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ ConfigureCategoryMappingCommandHandler                                                   │
│                                                                                          │
│ 1. Dla każdego mapping:                                                                  │
│    ├── Stwórz CategoryMapping document                                                   │
│    └── Zapisz do MongoDB (category_mappings)                                            │
│                                                                                          │
│ UWAGA: Mappingi są na poziomie bankCategory (np. "Przelewy wychodzące")                 │
│        NIE na poziomie nazwy transakcji!                                                 │
│                                                                                          │
│ PROBLEM: bankCategory jest zbyt ogólne!                                                  │
│   - "Przelewy wychodzące" = czynsz, składki ZUS, przelew do żony, wszystko!            │
│   - User musi RĘCZNIE kategoryzować każdą transakcję później                            │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘

KROK 4: Upload canonical CSV do staging
═══════════════════════════════════════

User → POST /api/v1/bank-data-ingestion/cf=CF10000123/upload
       (file = transformed CSV z transformationId)
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ UploadCsvCommandHandler                                                                  │
│                                                                                          │
│ 1. Parse CSV (BankCsvRow format)                                                         │
│ 2. Call StageTransactionsCommandHandler                                                  │
│                                                                                          │
│ StageTransactionsCommandHandler:                                                         │
│ 1. Load category mappings dla tego CashFlow                                              │
│ 2. Dla każdej transakcji:                                                                │
│    ├── Znajdź mapping dla (bankCategory, type)                                           │
│    │   ├── FOUND → status = VALID, mappedData = { targetCategory, parent }              │
│    │   └── NOT FOUND → status = PENDING_MAPPING                                         │
│    ├── Sprawdź duplikaty (by bankTransactionId)                                          │
│    └── Waliduj money i dates                                                             │
│ 3. Stwórz StagingSession (TTL 24h)                                                       │
│ 4. Zapisz StagedTransaction records                                                      │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
Response: {
  stagingSessionId: "session-456",
  status: "READY_FOR_IMPORT" | "HAS_PENDING_MAPPINGS",
  summary: { total: 402, valid: 350, invalid: 0, duplicates: 2 },
  unmappedCategories: [ ... ],    ← jeśli jakieś, musi dodać mappingi
  categoriesToCreate: [ "Przelewy", "Opłaty bankowe", "Przychody" ]
}

KROK 5: (Opcjonalnie) Rewalidacja po dodaniu mappingów
═══════════════════════════════════════════════════════

Jeśli były unmappedCategories:
User → POST /mappings (dodatkowe mappingi)
User → POST /staging/{sessionId}/revalidate
       │
       ▼
PENDING_MAPPING transactions → VALID (jeśli mapping teraz istnieje)

KROK 6: Start import job
════════════════════════

User → POST /api/v1/bank-data-ingestion/cf=CF10000123/import
       Body: { stagingSessionId: "session-456" }
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ StartImportJobCommandHandler                                                             │
│                                                                                          │
│ FAZA 1: CREATING_CATEGORIES                                                              │
│ ├── Dla każdej kategorii z categoriesToCreate:                                           │
│ │   ├── POST /cash-flow/cf={id}/category (przez CashFlowServiceClient)                  │
│ │   └── Jeśli parent: najpierw stwórz parent                                            │
│ └── Progress: 0% → 20%                                                                   │
│                                                                                          │
│ FAZA 2: IMPORTING_TRANSACTIONS                                                           │
│ ├── Dla każdej VALID transakcji (skip DUPLICATE):                                        │
│ │   └── POST /cash-flow/cf={id}/import-historical                                        │
│ │       Body: { name, description, category, money, type, dueDate, paidDate }           │
│ └── Progress: 20% → 100%                                                                 │
│                                                                                          │
│ FAZA 3: COMPLETED                                                                        │
│ └── ImportJob status = COMPLETED                                                         │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
       │
       ├── Każda transakcja emituje HistoricalCashChangeImportedEvent → Kafka
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastProcessor (Kafka consumer)                                               │
│                                                                                          │
│ HistoricalCashChangeImportedEventHandler:                                                │
│ 1. Znajdź odpowiedni miesiąc w CashFlowForecastStatement                                │
│ 2. Dodaj transakcję do categorizedInFlows lub categorizedOutFlows                       │
│ 3. Przelicz sumy miesięczne                                                              │
│ 4. Zapisz do MongoDB                                                                     │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘

KROK 7: Attestation (SETUP → OPEN)
══════════════════════════════════

User → POST /cash-flow/cf=CF10000123/attest-historical-import
       Body: {
         confirmedBalance: { amount: 76047.25, currency: "PLN" },
         createAdjustment: true,
         forceAttestation: false
       }
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ AttestHistoricalImportCommandHandler                                                     │
│                                                                                          │
│ 1. calculateCurrentBalance() = initialBalance + Σ(inflows) - Σ(outflows)                │
│ 2. Porównaj z confirmedBalance                                                           │
│    ├── MATCH → OK                                                                        │
│    └── RÓŻNICA:                                                                          │
│        ├── createAdjustment=true → stwórz "Balance Adjustment" transakcję               │
│        ├── forceAttestation=true → zignoruj różnicę                                     │
│        └── else → BŁĄD                                                                   │
│ 3. Emit HistoricalImportAttestedEvent:                                                   │
│    ├── CashFlow.status = OPEN                                                            │
│    ├── Ustaw importCutoffDateTime                                                        │
│    └── Zaktualizuj bankAccount.balance                                                   │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastProcessor (Kafka consumer)                                               │
│                                                                                          │
│ HistoricalImportAttestedEventHandler:                                                    │
│ 1. Dla każdego miesiąca IMPORT_PENDING → IMPORTED                                       │
│ 2. Ustaw attestation info w CashFlowForecastStatement                                   │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘

KROK 8: Weryfikacja w Forecast
══════════════════════════════

User → GET /cash-flow-forecast/cf=CF10000123
       │
       ▼
Response: {
  cashFlowId: "CF10000123",
  forecasts: {
    "2023-01": {
      status: "IMPORTED",
      categorizedOutFlows: [
        { category: "Przelewy", amount: -50000 },
        { category: "Opłaty bankowe", amount: -500 }
      ],
      categorizedInFlows: [
        { category: "Przychody", amount: 34506.42 }
      ]
    },
    // ... kolejne miesiące
    "2026-03": {
      status: "ACTIVE",
      // current month transactions (activePeriod)
    }
  },
  categoryStructure: {
    inflowCategories: [ { name: "Przychody", subCategories: [] } ],
    outflowCategories: [ { name: "Przelewy" }, { name: "Opłaty bankowe" } ]
  }
}
```

### Gap Analysis: Current vs Proposed

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           GAP ANALYSIS: OBECNY vs PROPONOWANY                            │
└─────────────────────────────────────────────────────────────────────────────────────────┘

OBECNY SYSTEM                                   BRAKUJE (DO DODANIA)
─────────────────────────────────────────────────────────────────────────────────────────

1. CSV Transformation (✅ GOTOWE)               ─
   POST /csv-import/upload
   → canonical CSV + metadata

2. CashFlow Creation (✅ GOTOWE)                ─
   POST /cash-flow/with-history

3. Category Mapping (⚠️ MANUALNE)              → AI Categorization Service
   POST /mappings                                  POST /ai-categorize/{sessionId}
   User RĘCZNIE mapuje:                           AI automatycznie:
   - bankCategory → cashFlowCategory              - normalizedName → category
   - Zbyt ogólne! ("Przelewy wychodzące")        - Granularnie! (ZUS, SILVA, MINDBOX)
                                                  - Confidence scores
                                                  - Nested categories (parent > child)

4. Staging (✅ GOTOWE, ale...)                 → Rozszerzyć o AI sugestie
   POST /staging                                  Dodać pole aiSuggestion do
   Działa na bankCategory                         StagedTransaction

5. Import (✅ GOTOWE)                          ─

6. Attestation (✅ GOTOWE)                     ─

7. Forecast (✅ GOTOWE)                        ─

─────────────────────────────────────────────────────────────────────────────────────────

KLUCZOWE RÓŻNICE:

OBECNY MAPPING (zbyt ogólny):
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ CSV bankCategory           →    CashFlow Category                                        │
│ ─────────────────────────────────────────────────────────────────────────────────────── │
│ "Przelewy wychodzące"      →    "Przelewy"                                              │
│                                                                                          │
│ PROBLEM: W "Przelewy wychodzące" jest WSZYSTKO:                                         │
│   - Czynsz do Silva                                                                      │
│   - Składki ZUS                                                                          │
│   - Podatek PIT                                                                          │
│   - Przelew do żony                                                                      │
│   - Faktura do Mindbox                                                                   │
│   - itd.                                                                                 │
│                                                                                          │
│ User musi PÓŹNIEJ ręcznie przekategoryzować każdą transakcję!                           │
└─────────────────────────────────────────────────────────────────────────────────────────┘

PROPONOWANY MAPPING (granularny):
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ Transaction Name (normalized)   →    CashFlow Category + Parent                         │
│ ─────────────────────────────────────────────────────────────────────────────────────── │
│ "SILVA"                         →    "Czynsz" (parent: "Mieszkanie")                    │
│ "ZUS"                           →    "ZUS" (parent: "Opłaty obowiązkowe")               │
│ "URZĄD SKARBOWY + PIT"          →    "Podatek PIT" (parent: "Opłaty obowiązkowe")       │
│ "URZĄD SKARBOWY + VAT"          →    "Podatek VAT" (parent: "Opłaty obowiązkowe")       │
│ "MINDBOX"                       →    "Pensja" (parent: "Wynagrodzenie")                 │
│ "LUCJAN BIK PEKAO"              →    ❓ Manual (confidence <50%)                        │
│                                                                                          │
│ AI kategoryzuje po NAZWIE transakcji, nie po bankCategory!                              │
│ Wynik: Nested categories z góry zorganizowane                                           │
└─────────────────────────────────────────────────────────────────────────────────────────┘

GDZIE DODAĆ AI:
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                          │
│  OPCJA A: Post-staging (bez modyfikacji staging handler)                                │
│  ─────────────────────────────────────────────────────────────────────────────────────  │
│                                                                                          │
│  POST /csv-import/upload                                                                 │
│       │                                                                                  │
│       ▼                                                                                  │
│  POST /staging (BEZ ZMIAN - staging jak dotychczas)                                     │
│       │                                                                                  │
│       ▼                                                                                  │
│  POST /ai-categorize/{sessionId}  ← NOWY ENDPOINT                                       │
│       │                                                                                  │
│       │  1. Pobierz staged transactions                                                  │
│       │  2. Normalizuj nazwy (usuń adresy, numery)                                      │
│       │  3. Sprawdź GlobalPatterns (BIEDRONKA, ZUS - FREE)                              │
│       │  4. Sprawdź UserPatternCache (poprzednie kategoryzacje - FREE)                  │
│       │  5. Pozostałe → AI (PAID)                                                        │
│       │  6. Zapisz aiSuggestion do StagedTransaction                                    │
│       │                                                                                  │
│       ▼                                                                                  │
│  UI pokazuje sugestie AI                                                                 │
│       │                                                                                  │
│       ▼                                                                                  │
│  POST /accept-suggestions  ← NOWY ENDPOINT                                              │
│       │  User potwierdza/modyfikuje                                                      │
│       │  Zapisz do PatternMapping cache (na przyszłość)                                 │
│       ▼                                                                                  │
│  POST /import (BEZ ZMIAN)                                                                │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Overview

Single endpoint that accepts ANY CSV file and:
1. Transforms to canonical format (AI or cache)
2. AI categorizes transactions with nested categories
3. Creates CashFlow with suggested structure
4. Imports transactions
5. Activates CashFlow

**No manual mapping required** - AI handles everything, user only confirms.

---

## Master Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           UNIFIED AI IMPORT - MASTER FLOW                                │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐
│  USER UPLOADS   │
│   CSV FILE      │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  PHASE 1: CSV TRANSFORMATION                                                             │
│  POST /api/v1/ai-import/upload                                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  ┌──────────────────────┐    ┌────────────────────────────────────────────────────────┐ │
│  │ Validate:            │    │ Detection Result:                                      │ │
│  │ • File not empty     │    │                                                        │ │
│  │ • Size < 5MB         │───▶│ CANONICAL?   → Skip AI, parse directly (FREE)         │ │
│  │ • .csv extension     │    │ CACHED?      → Use cached rules (FREE)                │ │
│  │ • No duplicate hash  │    │ NEW FORMAT?  → Call AI to create rules (PAID)         │ │
│  └──────────────────────┘    └────────────────────────────────────────────────────────┘ │
│                                                 │                                        │
│                                                 ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ OUTPUT: AiImportSessionDocument                                                     ││
│  │ ├── sessionId: UUID                                                                 ││
│  │ ├── transformedRows: List<BankCsvRow>                                              ││
│  │ ├── detectedBank: "Nest Bank"                                                       ││
│  │ ├── detectedCurrency: "PLN"                                                         ││
│  │ ├── dateRange: { min: "2023-01-15", max: "2026-01-11" }                            ││
│  │ ├── suggestedStartPeriod: "2023-01"                                                 ││
│  │ └── status: AWAITING_CATEGORIZATION                                                 ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  PHASE 2: AI CATEGORIZATION                                                              │
│  POST /api/v1/ai-import/{sessionId}/categorize                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  INPUT: Transformed rows with names, counterparties, amounts                             │
│                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 1: PATTERN EXTRACTION                                                          ││
│  │                                                                                     ││
│  │ 402 transactions                                                                    ││
│  │     │                                                                               ││
│  │     ▼ Normalize names (remove addresses, numbers)                                   ││
│  │ ~45 unique patterns                                                                 ││
│  │     │                                                                               ││
│  │     ├── Check GlobalPatterns (FREE) → BIEDRONKA, ZUS, NETFLIX...                   ││
│  │     │   └── ~15 patterns resolved                                                   ││
│  │     │                                                                               ││
│  │     ├── Check UserPatternCache (FREE) → user's previous categorizations            ││
│  │     │   └── ~10 patterns resolved                                                   ││
│  │     │                                                                               ││
│  │     └── Remaining ~20 patterns → Send to AI (PAID)                                  ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 2: AI CATEGORIZATION CALL                                                      ││
│  │                                                                                     ││
│  │ Prompt includes:                                                                    ││
│  │ • Transaction patterns with counterparty, amount, type                              ││
│  │ • Instruction to create nested category structure                                   ││
│  │ • Polish financial context (ZUS, US, KRUS, etc.)                                   ││
│  │                                                                                     ││
│  │ AI returns:                                                                         ││
│  │ • Suggested category structure (nested)                                             ││
│  │ • Pattern → Category mappings                                                       ││
│  │ • Confidence scores (0-100)                                                         ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                                                                          │
│  OUTPUT: AiCategorizationResult                                                          │
│  ├── suggestedStructure:                                                                 │
│  │   ├── outflow:                                                                        │
│  │   │   ├── Mieszkanie: [Czynsz, Media, Ubezpieczenie]                                │
│  │   │   ├── Transport: [Paliwo, Komunikacja, Serwis]                                   │
│  │   │   └── ...                                                                         │
│  │   └── inflow:                                                                         │
│  │       ├── Wynagrodzenie: [Pensja, Premie]                                            │
│  │       └── ...                                                                         │
│  ├── patternMappings:                                                                    │
│  │   ├── { pattern: "MINDBOX", category: "Pensja", parent: "Wynagrodzenie", conf: 85 } │
│  │   ├── { pattern: "ZUS", category: "Składki ZUS", parent: "Opłaty", conf: 99 }       │
│  │   └── ...                                                                             │
│  ├── stats:                                                                              │
│  │   ├── autoAccepted: 320 (80%)  [confidence ≥90%]                                    │
│  │   ├── suggested: 62 (15%)      [confidence 50-89%]                                   │
│  │   └── manual: 20 (5%)          [confidence <50%]                                     │
│  └── status: AWAITING_CONFIRMATION                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  PHASE 3: USER CONFIRMATION                                                              │
│  UI displays AI suggestions for review                                                   │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  User sees:                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │                                                                                     ││
│  │  🏦 Wykryty bank: Nest Bank                                                         ││
│  │  💰 Waluta: PLN                                                                     ││
│  │  📅 Zakres: 2023-01-15 - 2026-01-11 (36 miesięcy)                                  ││
│  │  📊 Transakcje: 402                                                                 ││
│  │                                                                                     ││
│  │  ═══════════════════════════════════════════════════════════════════════════════   ││
│  │                                                                                     ││
│  │  🗂️ SUGEROWANA STRUKTURA KATEGORII                                                 ││
│  │                                                                                     ││
│  │  WYDATKI:                                                                           ││
│  │  ☑️ Mieszkanie                                                                      ││
│  │     ├── ☑️ Czynsz (SILVA → 12 transakcji)                                          ││
│  │     ├── ☑️ Media                                                                    ││
│  │     └── ☐ Ubezpieczenie                                                             ││
│  │  ☑️ Opłaty obowiązkowe                                                              ││
│  │     ├── ☑️ ZUS (12 transakcji)                                                      ││
│  │     ├── ☑️ Podatek PIT (12 transakcji)                                              ││
│  │     └── ☑️ Podatek VAT (4 transakcji)                                               ││
│  │  ☐ Transport                                                                         ││
│  │     └── ☐ Paliwo                                                                     ││
│  │                                                                                     ││
│  │  PRZYCHODY:                                                                          ││
│  │  ☑️ Wynagrodzenie                                                                   ││
│  │     └── ☑️ Pensja (MINDBOX → 12 transakcji)                                         ││
│  │                                                                                     ││
│  │  ═══════════════════════════════════════════════════════════════════════════════   ││
│  │                                                                                     ││
│  │  ⚠️ DO POTWIERDZENIA (confidence 50-89%):                                           ││
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐   ││
│  │  │ "LUCJAN BIK PEKAO" → ❓                                                      │   ││
│  │  │ 20 transakcji, łącznie -60,000 PLN                                          │   ││
│  │  │                                                                             │   ││
│  │  │ AI sugestia: "Oszczędności" (55% pewności)                                  │   ││
│  │  │ Tytuły: "zycie", "zycie", "zycie"...                                        │   ││
│  │  │                                                                             │   ││
│  │  │ [Akceptuj sugestię] [Wybierz inną] [Utwórz nową: _______]                   │   ││
│  │  └─────────────────────────────────────────────────────────────────────────────┘   ││
│  │                                                                                     ││
│  │  ❓ WYMAGAJĄ RĘCZNEGO WYBORU (<50% confidence):                                      ││
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐   ││
│  │  │ "zaswiadczenie" → IKANO                                                      │   ││
│  │  │ 1 transakcja, -50 PLN                                                       │   ││
│  │  │                                                                             │   ││
│  │  │ [Kredyt] [Opłaty bankowe] [Inne] [+ Nowa kategoria]                         │   ││
│  │  └─────────────────────────────────────────────────────────────────────────────┘   ││
│  │                                                                                     ││
│  │  ═══════════════════════════════════════════════════════════════════════════════   ││
│  │                                                                                     ││
│  │  📝 DANE CASHFLOW:                                                                  ││
│  │  Nazwa: [Nest Bank - Konto główne_________]                                        ││
│  │  Saldo początkowe (2023-01): [14,484.19 PLN______]  ← z pierwszej transakcji       ││
│  │                                                                                     ││
│  │  [Importuj i aktywuj CashFlow]                                                      ││
│  │                                                                                     ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                                                                          │
│  POST /api/v1/ai-import/{sessionId}/confirm                                              │
│  Body:                                                                                   │
│  {                                                                                       │
│    "cashFlowName": "Nest Bank - Konto główne",                                          │
│    "initialBalance": { "amount": 14484.19, "currency": "PLN" },                         │
│    "confirmedStructure": { ... },  // with user modifications                           │
│    "patternOverrides": [                                                                 │
│      { "pattern": "LUCJAN BIK PEKAO", "category": "Oszczędności", "parent": null }     │
│    ],                                                                                    │
│    "manualMappings": [                                                                   │
│      { "pattern": "zaswiadczenie", "category": "Opłaty bankowe", "parent": "Opłaty" }  │
│    ]                                                                                     │
│  }                                                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  PHASE 4: CASHFLOW CREATION & IMPORT                                                     │
│  Backend orchestrates all steps automatically                                            │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 1: CREATE CASHFLOW IN SETUP MODE                                               ││
│  │                                                                                     ││
│  │ CreateCashFlowWithHistoryCommand {                                                  ││
│  │   userId: <from JWT>,                                                               ││
│  │   name: "Nest Bank - Konto główne",                                                 ││
│  │   bankAccount: {                                                                    ││
│  │     bankName: "Nest Bank",  // from AI detection                                    ││
│  │     accountNumber: "93187010452083105656550001",  // from CSV header                ││
│  │     balance: 14484.19 PLN                                                           ││
│  │   },                                                                                ││
│  │   startPeriod: "2023-01",  // earliest transaction month                            ││
│  │   initialBalance: 14484.19 PLN                                                      ││
│  │ }                                                                                   ││
│  │                                                                                     ││
│  │ OUTPUT: cashFlowId = "CF10000123"                                                   ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                      │                                                   │
│                                      ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 2: CREATE NESTED CATEGORY STRUCTURE                                            ││
│  │                                                                                     ││
│  │ For each confirmed top-level category:                                              ││
│  │   CreateCategoryCommand { cashFlowId, categoryName, type, origin: AI_SUGGESTED }    ││
│  │                                                                                     ││
│  │   For each subcategory:                                                             ││
│  │     CreateSubCategoryCommand { cashFlowId, parentName, categoryName }               ││
│  │                                                                                     ││
│  │ Result:                                                                             ││
│  │ outflowCategories:                                                                  ││
│  │   ├── Uncategorized (SYSTEM)                                                        ││
│  │   ├── Mieszkanie (AI_SUGGESTED)                                                     ││
│  │   │   ├── Czynsz                                                                    ││
│  │   │   └── Media                                                                     ││
│  │   ├── Opłaty obowiązkowe (AI_SUGGESTED)                                             ││
│  │   │   ├── ZUS                                                                       ││
│  │   │   ├── Podatek PIT                                                               ││
│  │   │   └── Podatek VAT                                                               ││
│  │   └── ...                                                                           ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                      │                                                   │
│                                      ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 3: IMPORT TRANSACTIONS BY PERIOD                                               ││
│  │                                                                                     ││
│  │ For each transaction (sorted by date):                                              ││
│  │                                                                                     ││
│  │   1. Normalize name → get pattern                                                   ││
│  │   2. Look up pattern mapping → get category + parent                                ││
│  │   3. Find target category in CashFlow (exact match or parent > child)               ││
│  │   4. Create CashChange:                                                             ││
│  │      ImportHistoricalCashChangeCommand {                                            ││
│  │        cashFlowId,                                                                  ││
│  │        name: "Składki ZUS",                                                         ││
│  │        description: "składki ZUS",                                                  ││
│  │        money: { amount: 1771.17, currency: "PLN" },  // amount ZAWSZE dodatnie     ││
│  │        type: OUTFLOW,  // typ określa kierunek                                      ││
│  │        categoryName: "ZUS",                                                         ││
│  │        parentCategoryName: "Opłaty obowiązkowe",                                    ││
│  │        dueDate: "2023-01-20",                                                       ││
│  │        status: CONFIRMED  // historical = already paid                              ││
│  │      }                                                                              ││
│  │                                                                                     ││
│  │ Progress tracking:                                                                  ││
│  │   ├── Creating categories: 15/15 ✓                                                  ││
│  │   └── Importing transactions: 402/402 ✓                                             ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                      │                                                   │
│                                      ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 4: BALANCE VERIFICATION & ATTESTATION                                          ││
│  │                                                                                     ││
│  │ Calculate balance:                                                                  ││
│  │   calculatedBalance = initialBalance + Σ(inflows) - Σ(outflows)                    ││
│  │                                                                                     ││
│  │ From CSV "Saldo po operacji" (last row):                                            ││
│  │   expectedBalance = 76,047.25 PLN                                                   ││
│  │                                                                                     ││
│  │ If match:                                                                           ││
│  │   └── AttestHistoricalImportCommand { confirmedBalance: 76047.25, autoAttest: true }││
│  │                                                                                     ││
│  │ If mismatch (rare):                                                                 ││
│  │   └── Return warning, ask user for decision:                                        ││
│  │       [Create adjustment transaction] [Force attestation] [Cancel]                  ││
│  │                                                                                     ││
│  │ After attestation:                                                                  ││
│  │   └── CashFlow.status changes from SETUP → OPEN                                     ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                      │                                                   │
│                                      ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 5: SAVE LEARNED PATTERNS                                                        ││
│  │                                                                                     ││
│  │ Save confirmed mappings to PatternMappingCache:                                     ││
│  │   • Source: USER_CONFIRMED (for user-modified)                                      ││
│  │   • Source: AI_CONFIRMED (for auto-accepted)                                        ││
│  │                                                                                     ││
│  │ Next import from same bank → higher cache hit rate → lower AI cost                  ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  RESULT                                                                                  │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  {                                                                                       │
│    "success": true,                                                                      │
│    "cashFlowId": "CF10000123",                                                          │
│    "status": "OPEN",  // ready for use!                                                  │
│                                                                                          │
│    "summary": {                                                                          │
│      "transactionsImported": 402,                                                        │
│      "categoriesCreated": 15,                                                            │
│      "monthsCovered": 36,                                                                │
│      "dateRange": { "from": "2023-01-15", "to": "2026-01-11" }                          │
│    },                                                                                    │
│                                                                                          │
│    "balance": {                                                                          │
│      "initial": { "amount": 14484.19, "currency": "PLN" },                              │
│      "current": { "amount": 76047.25, "currency": "PLN" }                               │
│    },                                                                                    │
│                                                                                          │
│    "aiStats": {                                                                          │
│      "patternsFromCache": 25,                                                            │
│      "patternsFromAi": 20,                                                               │
│      "tokensUsed": 2400,                                                                 │
│      "costEstimate": "0.31 gr"                                                           │
│    },                                                                                    │
│                                                                                          │
│    "nextSteps": [                                                                        │
│      "View your CashFlow dashboard",                                                     │
│      "Set up budget alerts",                                                             │
│      "Configure recurring rules"                                                         │
│    ]                                                                                     │
│  }                                                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## API Endpoints

### 1. Upload CSV

```
POST /api/v1/ai-import/upload
Content-Type: multipart/form-data

Parameters:
  - file: CSV file (required)
  - bankHint: String (optional) - helps AI detect bank faster

Response: 200 OK
{
  "sessionId": "abc123-...",
  "status": "AWAITING_CATEGORIZATION",

  "csvInfo": {
    "detectionResult": "AI_TRANSFORMED",  // CANONICAL | CACHED | AI_TRANSFORMED
    "detectedBank": "Nest Bank",
    "detectedCurrency": "PLN",
    "rowCount": 402,
    "dateRange": {
      "min": "2023-01-15",
      "max": "2026-01-11"
    },
    "suggestedStartPeriod": "2023-01",
    "monthsOfData": 36
  },

  "accountInfo": {
    "accountNumber": "93187010452083105656550001",
    "accountOwner": "DEV LUCJAN BIK",
    "lastBalance": { "amount": 76047.25, "currency": "PLN" }
  },

  "nextStep": {
    "action": "CATEGORIZE",
    "url": "/api/v1/ai-import/{sessionId}/categorize"
  }
}

Errors:
  - 400: File empty, invalid format
  - 409: Duplicate file (already uploaded)
  - 413: File too large (>5MB)
```

### 2. AI Categorize

```
POST /api/v1/ai-import/{sessionId}/categorize

Response: 200 OK
{
  "sessionId": "abc123-...",
  "status": "AWAITING_CONFIRMATION",

  "suggestedStructure": {
    "outflow": [
      {
        "name": "Mieszkanie",
        "subCategories": ["Czynsz", "Media", "Ubezpieczenie"],
        "transactionCount": 24,
        "totalAmount": -48000.00
      },
      {
        "name": "Opłaty obowiązkowe",
        "subCategories": ["ZUS", "Podatek PIT", "Podatek VAT"],
        "transactionCount": 28,
        "totalAmount": -85000.00
      }
    ],
    "inflow": [
      {
        "name": "Wynagrodzenie",
        "subCategories": ["Pensja", "Premie"],
        "transactionCount": 12,
        "totalAmount": 380000.00
      }
    ]
  },

  "patternMappings": [
    {
      "pattern": "MINDBOX",
      "sampleTransactions": ["N1D8MC1 12/2022", "PCB9QO25 11/2025"],
      "counterparty": "MINDBOX SPÓŁKA AKCYJNA",
      "transactionCount": 12,
      "totalAmount": 380000.00,
      "suggestedCategory": "Pensja",
      "suggestedParent": "Wynagrodzenie",
      "confidence": 85,
      "status": "SUGGEST",  // AUTO_ACCEPT | SUGGEST | MANUAL_REQUIRED
      "reasoning": "Regular monthly payments from a company indicate salary"
    },
    {
      "pattern": "ZUS",
      "counterparty": "ZUS",
      "transactionCount": 12,
      "totalAmount": -21254.04,
      "suggestedCategory": "ZUS",
      "suggestedParent": "Opłaty obowiązkowe",
      "confidence": 99,
      "status": "AUTO_ACCEPT",
      "reasoning": "ZUS is the Polish social insurance institution"
    },
    {
      "pattern": "LUCJAN BIK PEKAO",
      "sampleTransactions": ["zycie"],
      "transactionCount": 20,
      "totalAmount": -60000.00,
      "suggestedCategory": null,
      "suggestedParent": null,
      "confidence": 25,
      "status": "MANUAL_REQUIRED",
      "reasoning": "Personal transfer - purpose unclear from title 'zycie'"
    }
  ],

  "stats": {
    "autoAccepted": { "transactions": 320, "percentage": 80 },
    "suggestions": { "transactions": 62, "percentage": 15 },
    "manualRequired": { "transactions": 20, "percentage": 5 }
  },

  "nextStep": {
    "action": "CONFIRM",
    "url": "/api/v1/ai-import/{sessionId}/confirm",
    "requiredFields": ["cashFlowName", "initialBalance", "manualMappings"]
  }
}
```

### 3. Confirm & Import

```
POST /api/v1/ai-import/{sessionId}/confirm

Request:
{
  "cashFlowName": "Nest Bank - Konto główne",
  "initialBalance": { "amount": 14484.19, "currency": "PLN" },

  "structureOverrides": {
    "removed": ["Transport"],  // user unchecked this category
    "renamed": [
      { "from": "Opłaty obowiązkowe", "to": "Podatki i składki" }
    ],
    "added": [
      { "name": "Oszczędności", "type": "OUTFLOW", "parent": null }
    ]
  },

  "patternOverrides": [
    {
      "pattern": "LUCJAN BIK PEKAO",
      "category": "Oszczędności",
      "parent": null
    }
  ],

  "autoAttest": true  // automatically attest if balance matches
}

Response: 200 OK
{
  "sessionId": "abc123-...",
  "status": "IMPORTING",
  "jobId": "job-456-...",

  "progress": {
    "phase": "CREATING_CATEGORIES",
    "current": 5,
    "total": 15,
    "percentage": 33
  },

  "pollUrl": "/api/v1/ai-import/{sessionId}/status"
}
```

### 4. Check Status (Polling)

```
GET /api/v1/ai-import/{sessionId}/status

Response: 200 OK
{
  "sessionId": "abc123-...",
  "status": "COMPLETED",  // IMPORTING | COMPLETED | FAILED | NEEDS_ATTESTATION

  "result": {
    "cashFlowId": "CF10000123",
    "cashFlowStatus": "OPEN",

    "summary": {
      "categoriesCreated": 15,
      "transactionsImported": 402,
      "duplicatesSkipped": 0,
      "errorsCount": 0
    },

    "balance": {
      "calculated": { "amount": 76047.25, "currency": "PLN" },
      "attested": { "amount": 76047.25, "currency": "PLN" },
      "match": true
    },

    "patternsLearned": 45
  }
}

// If status = NEEDS_ATTESTATION:
{
  "status": "NEEDS_ATTESTATION",
  "attestationRequired": {
    "calculatedBalance": 76000.00,
    "expectedBalance": 76047.25,
    "difference": 47.25,
    "options": [
      { "action": "CREATE_ADJUSTMENT", "description": "Create adjustment transaction" },
      { "action": "FORCE_ATTEST", "description": "Ignore difference and attest" },
      { "action": "CANCEL", "description": "Cancel import and review" }
    ]
  },
  "attestUrl": "/api/v1/ai-import/{sessionId}/attest"
}
```

---

## AI Prompts Design

### Prompt 1: Category Structure Suggestion

```
SYSTEM PROMPT:
You are a financial categorization expert for Polish bank statements.
Your task is to analyze transaction patterns and suggest a nested category structure.

CONTEXT:
- Country: Poland
- Currency: PLN
- User type: Individual (consumer or freelancer)

INSTRUCTIONS:
1. Analyze the transaction patterns below
2. Group them into logical categories (max 8 top-level, max 5 subcategories each)
3. Use Polish category names
4. Consider Polish-specific categories:
   - Składki ZUS (social insurance)
   - Podatki (PIT, VAT, CIT)
   - Czynsz/Media (rent/utilities)

OUTPUT FORMAT (JSON only, no explanation):
{
  "outflow": [
    {
      "name": "Category Name",
      "subCategories": ["Sub1", "Sub2"],
      "matchingPatterns": ["PATTERN1", "PATTERN2"]
    }
  ],
  "inflow": [...],
  "uncategorizable": ["PATTERN_X"]  // patterns with <50% confidence
}

---

USER PROMPT:
Transaction patterns to categorize:

OUTFLOW patterns:
1. Pattern: "ZUS" | Counterparty: "ZUS" | Count: 12 | Total: -21,254.04 PLN
   Sample titles: "składki ZUS"

2. Pattern: "URZĄD SKARBOWY" | Counterparty: "Urzad skarbowy w Mielcu" | Count: 16 | Total: -95,000 PLN
   Sample titles: "PIT28", "VAT7K"

3. Pattern: "SILVA" | Counterparty: "Silva, Warszawa" | Count: 12 | Total: -20,274 PLN
   Sample titles: "czynsz Lokal: 00-070 -020"

4. Pattern: "IKANO" | Counterparty: "Ikano" | Count: 6 | Total: -3,600 PLN
   Sample titles: "rata kredytu", "zaswiadczenie"

5. Pattern: "LUCJAN BIK PEKAO" | Counterparty: "Lucjan Bik Pekao" | Count: 20 | Total: -60,000 PLN
   Sample titles: "zycie"  // cryptic personal transfer

INFLOW patterns:
1. Pattern: "MINDBOX" | Counterparty: "MINDBOX SPÓŁKA AKCYJNA" | Count: 12 | Total: 380,000 PLN
   Sample titles: "N1D8MC1 12/2022", "PCB9QO25 11/2025"  // cryptic invoice numbers
```

### Prompt 2: Pattern Categorization

```
SYSTEM PROMPT:
You are a transaction categorizer. Given a category structure and transaction patterns,
assign each pattern to the most appropriate category.

RULES:
1. Confidence scoring:
   - 90-100%: Certain (known brands, government, utilities)
   - 70-89%: Likely (clear business context)
   - 50-69%: Uncertain (could be multiple categories)
   - <50%: Cannot determine (personal transfers, cryptic titles)

2. Use counterparty name when title is cryptic
3. Regular monthly payments from companies = likely salary
4. Personal transfers to individuals = cannot categorize reliably

OUTPUT FORMAT (JSON only):
{
  "mappings": [
    {
      "pattern": "PATTERN_NAME",
      "category": "Category Name",
      "parentCategory": "Parent Name",  // null if top-level
      "confidence": 85,
      "reasoning": "Brief explanation"
    }
  ]
}

---

USER PROMPT:
Category structure:
OUTFLOW:
- Mieszkanie: [Czynsz, Media, Ubezpieczenie]
- Opłaty obowiązkowe: [ZUS, Podatki]
- Kredyty: [Raty kredytów]

INFLOW:
- Wynagrodzenie: [Pensja, Premie]
- Inne przychody: [Zwroty, Odsetki]

Patterns to categorize:
[... patterns from previous step ...]
```

---

## Corner Cases & Validations

### File Upload

| Case | Handling |
|------|----------|
| Empty file | 400 Bad Request: "File is empty" |
| No CSV content | 400 Bad Request: "File has no valid CSV content" |
| File > 5MB | 413 Payload Too Large |
| Duplicate file (same hash) | 409 Conflict: "File already uploaded" + existing sessionId |
| Malformed CSV | 400 Bad Request: "Cannot parse CSV" + line number |
| Wrong encoding | Auto-detect UTF-8/CP1250, warn if issues |
| BOM present | Strip BOM silently |

### Date Handling

| Case | Handling |
|------|----------|
| Future dates | Warning: "Found transactions dated in future" |
| Date format DD-MM-YYYY vs YYYY-MM-DD | AI detects and normalizes |
| Missing dates | Error: "Transaction missing date" + skip row |
| Invalid dates (31-02-2023) | Error: "Invalid date" + skip row |

### Amount Handling

| Case | Handling |
|------|----------|
| Comma as decimal (1.234,56) | Normalize to 1234.56 |
| Negative amounts as text ("-100,00") | Parse correctly |
| Currency mismatch in file | Warning: "Mixed currencies detected" |
| Zero amounts | Skip transaction, warn |

### Category Structure

| Case | Handling |
|------|----------|
| Circular dependency (A > B > A) | Prevent: "Circular category structure" |
| Duplicate category names | Add suffix: "Opłaty", "Opłaty (2)" |
| Too many categories (>20) | Warning: "Consider consolidating categories" |
| Empty category (no transactions) | Include but mark as empty |
| Subcategory without parent | Create as top-level |

### Transaction Import

| Case | Handling |
|------|----------|
| Duplicate transaction (same bankTransactionId) | Skip, count as duplicate |
| Missing category mapping | Use "Uncategorized" |
| Transaction before startPeriod | Error (shouldn't happen - startPeriod from earliest) |
| Very old transactions (>5 years) | Warning: "Consider reducing history" |

### Balance Reconciliation

| Case | Handling |
|------|----------|
| Balance matches exactly | Auto-attest |
| Small difference (<1 PLN) | Auto-attest with warning |
| Significant difference | Require user decision (adjust/force/cancel) |
| No balance in CSV | User must provide expected balance |

### AI Edge Cases

| Case | Handling |
|------|----------|
| AI timeout | Retry once, then fallback to "Uncategorized" |
| AI returns invalid JSON | Parse error, retry with simplified prompt |
| AI suggests non-existent category | Create category automatically |
| AI confidence all <50% | Ask user to provide sample categorizations |
| Rate limit hit | Queue and retry with exponential backoff |

---

## Data Model

### AiImportSession (MongoDB)

```java
@Document(collection = "ai_import_sessions")
public record AiImportSession(
    @Id String sessionId,
    String userId,
    AiImportStatus status,

    // Phase 1: CSV data
    String originalFileName,
    String fileHash,
    String detectedBank,
    String detectedCurrency,
    List<TransformedRow> transformedRows,
    DateRange dateRange,
    YearMonth suggestedStartPeriod,

    // Phase 2: AI categorization
    SuggestedStructure suggestedStructure,
    List<PatternMapping> patternMappings,
    CategorizationStats stats,

    // Phase 3: User confirmation
    UserConfirmation userConfirmation,

    // Phase 4: Import result
    String cashFlowId,
    ImportResult importResult,

    // Metadata
    Instant created,
    Instant lastModified,
    Instant expiresAt  // TTL: 24 hours
) {}

public enum AiImportStatus {
    AWAITING_CATEGORIZATION,
    CATEGORIZING,
    AWAITING_CONFIRMATION,
    IMPORTING,
    NEEDS_ATTESTATION,
    COMPLETED,
    FAILED,
    EXPIRED
}
```

### PatternMapping (MongoDB)

```java
@Document(collection = "pattern_mappings")
public record PatternMapping(
    @Id String id,
    String userId,
    String cashFlowId,  // null = user-level cache

    String normalizedPattern,
    String categoryName,
    String parentCategoryName,
    CashChangeType type,

    MappingSource source,
    int confidence,
    int timesUsed,

    Instant created,
    Instant lastUsed
) {}

public enum MappingSource {
    GLOBAL,           // System-wide known patterns (BIEDRONKA, ZUS)
    AI_CONFIRMED,     // AI suggested, user accepted
    USER_DEFINED      // User manually selected
}
```

---

## Sequence Diagram: Complete Flow

```
┌──────┐          ┌───────────────┐          ┌────────────┐          ┌──────────┐          ┌────────────┐
│Client│          │AiImportController│        │AiImportService│       │PatternCache│        │   OpenAI   │
└──┬───┘          └───────┬───────┘          └──────┬─────┘          └─────┬────┘          └──────┬─────┘
   │                      │                         │                      │                      │
   │ POST /upload (file)  │                         │                      │                      │
   │─────────────────────>│                         │                      │                      │
   │                      │                         │                      │                      │
   │                      │ transform(file)         │                      │                      │
   │                      │────────────────────────>│                      │                      │
   │                      │                         │                      │                      │
   │                      │                         │ checkCache(bankId)   │                      │
   │                      │                         │─────────────────────>│                      │
   │                      │                         │                      │                      │
   │                      │                         │ [MISS] callAI()      │                      │
   │                      │                         │─────────────────────────────────────────────>│
   │                      │                         │                      │                      │
   │                      │                         │<─────────────────────────────────────────────│
   │                      │                         │  mapping rules       │                      │
   │                      │                         │                      │                      │
   │                      │                         │ saveCache()          │                      │
   │                      │                         │─────────────────────>│                      │
   │                      │                         │                      │                      │
   │                      │<────────────────────────│                      │                      │
   │                      │  AiImportSession        │                      │                      │
   │                      │  (AWAITING_CATEGORIZATION)                     │                      │
   │                      │                         │                      │                      │
   │<─────────────────────│                         │                      │                      │
   │  { sessionId, csvInfo }                        │                      │                      │
   │                      │                         │                      │                      │
   │                      │                         │                      │                      │
   │ POST /categorize     │                         │                      │                      │
   │─────────────────────>│                         │                      │                      │
   │                      │                         │                      │                      │
   │                      │ categorize(sessionId)   │                      │                      │
   │                      │────────────────────────>│                      │                      │
   │                      │                         │                      │                      │
   │                      │                         │ extractPatterns()    │                      │
   │                      │                         │────────┐             │                      │
   │                      │                         │<───────┘             │                      │
   │                      │                         │                      │                      │
   │                      │                         │ checkGlobalPatterns()│                      │
   │                      │                         │─────────────────────>│                      │
   │                      │                         │<─────────────────────│                      │
   │                      │                         │  15 patterns found   │                      │
   │                      │                         │                      │                      │
   │                      │                         │ checkUserCache()     │                      │
   │                      │                         │─────────────────────>│                      │
   │                      │                         │<─────────────────────│                      │
   │                      │                         │  10 patterns found   │                      │
   │                      │                         │                      │                      │
   │                      │                         │ categorizeWithAI(20 remaining)              │
   │                      │                         │─────────────────────────────────────────────>│
   │                      │                         │                      │                      │
   │                      │                         │<─────────────────────────────────────────────│
   │                      │                         │  suggestions + structure                    │
   │                      │                         │                      │                      │
   │                      │<────────────────────────│                      │                      │
   │                      │  CategorizationResult   │                      │                      │
   │                      │                         │                      │                      │
   │<─────────────────────│                         │                      │                      │
   │  { structure, mappings, stats }                │                      │                      │
   │                      │                         │                      │                      │
   │                      │                         │                      │                      │
   │ POST /confirm        │                         │                      │                      │
   │  { name, balance,    │                         │                      │                      │
   │    overrides }       │                         │                      │                      │
   │─────────────────────>│                         │                      │                      │
   │                      │                         │                      │                      │
   │                      │ executeImport(session, confirmation)           │                      │
   │                      │────────────────────────>│                      │                      │
   │                      │                         │                      │                      │
   │                      │                         │ ┌────────────────────────────────────────┐  │
   │                      │                         │ │ 1. CreateCashFlowWithHistory           │  │
   │                      │                         │ │ 2. Create categories (nested)          │  │
   │                      │                         │ │ 3. Import transactions (by period)     │  │
   │                      │                         │ │ 4. Verify balance                      │  │
   │                      │                         │ │ 5. Attest (if match)                   │  │
   │                      │                         │ │ 6. Save learned patterns               │  │
   │                      │                         │ └────────────────────────────────────────┘  │
   │                      │                         │                      │                      │
   │                      │                         │ savePatterns()       │                      │
   │                      │                         │─────────────────────>│                      │
   │                      │                         │                      │                      │
   │                      │<────────────────────────│                      │                      │
   │                      │  ImportResult           │                      │                      │
   │                      │                         │                      │                      │
   │<─────────────────────│                         │                      │                      │
   │  { cashFlowId, COMPLETED }                     │                      │                      │
   │                      │                         │                      │                      │
```

---

## Implementation Checklist

### Phase 1: Core Infrastructure (No AI Yet)
- [ ] Create `AiImportSession` MongoDB document
- [ ] Create `AiImportSessionRepository`
- [ ] Create `AiImportRestController` with upload endpoint
- [ ] Integrate with existing `AiBankCsvTransformService`
- [ ] Create `TransactionPatternExtractor` service

### Phase 2: Pattern Cache
- [ ] Create `PatternMapping` MongoDB document
- [ ] Create `PatternMappingRepository`
- [ ] Create `GlobalPatternSeeder` with 50+ known patterns
- [ ] Create `PatternCacheService` with lookup/save

### Phase 3: AI Categorization
- [ ] Create `AiCategorizationService`
- [ ] Create `AiPromptBuilder` (structure + categorization)
- [ ] Create `AiResponseParser` with validation
- [ ] Add retry logic and fallbacks

### Phase 4: Import Orchestration
- [ ] Create `AiImportOrchestrator` service
- [ ] Integrate with `CreateCashFlowWithHistoryCommand`
- [ ] Create batch category creation logic
- [ ] Create batch transaction import logic
- [ ] Add progress tracking

### Phase 5: Balance & Attestation
- [ ] Create balance calculator
- [ ] Integrate with `AttestHistoricalImportCommand`
- [ ] Add adjustment transaction creation
- [ ] Handle attestation edge cases

### Phase 6: REST API
- [ ] `POST /api/v1/ai-import/upload`
- [ ] `POST /api/v1/ai-import/{sessionId}/categorize`
- [ ] `POST /api/v1/ai-import/{sessionId}/confirm`
- [ ] `GET /api/v1/ai-import/{sessionId}/status`
- [ ] `POST /api/v1/ai-import/{sessionId}/attest`

### Phase 7: Testing
- [ ] Unit tests for pattern extraction
- [ ] Unit tests for AI prompt building
- [ ] Integration tests with mock AI
- [ ] E2E test with real CSV files

---

## USER JOURNEY: Complete Step-by-Step with Data Transformations

This section documents the complete user journey showing:
- What endpoint is called
- What data is sent/received
- How data transforms at each step
- What is saved to database
- Error handling and recovery scenarios

### Journey Map: Happy Path

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    USER JOURNEY: CSV TO ACTIVE CASHFLOW                                  │
│                    (każdy krok z transformacją danych)                                   │
└─────────────────────────────────────────────────────────────────────────────────────────┘

╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ KROK 1: USER WGRYWA PLIK CSV                                                              ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  POST /api/v1/csv-import/upload                                                           ║
║  Content-Type: multipart/form-data                                                        ║
║  Authorization: Bearer eyJhbGciOi...                                                      ║
║  Body: file=lista_operacji_20260111.csv, bankHint="Nest Bank"                            ║
║                                                                                           ║
║  ┌─────────────────────────────────────────────────────────────────────────────────────┐  ║
║  │ DANE WEJŚCIOWE (CSV Nest Bank):                                                     │  ║
║  │                                                                                     │  ║
║  │ Numer rachunku: 93187010452083105656550001,                                        │  ║
║  │ Właściciel: DEV LUCJAN BIK,                                                        │  ║
║  │ Historia operacji za okres od 01.01.2023 do 11.01.2026,                           │  ║
║  │ Liczba operacji: 402,                                                              │  ║
║  │ Suma uznań: 1217096.34 PLN,                                                        │  ║
║  │ Suma obciążeń: -1141912.03 PLN,                                                    │  ║
║  │ Data księgowania,Data operacji,Rodzaj operacji,Kwota,Waluta,Dane kontrahenta,...  │  ║
║  │ 31-12-2025,31-12-2025,Opłaty i prowizje,-10,PLN,,,"Prowizja...",76047.25,          │  ║
║  │ 31-12-2025,31-12-2025,Przelewy wychodzące,-3000,PLN,"Lucjan Bik Pekao",...         │  ║
║  │ ...                                                                                 │  ║
║  └─────────────────────────────────────────────────────────────────────────────────────┘  ║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE W AiBankCsvTransformService:                                            ║
║                                                                                           ║
║  1. WALIDACJA:                                                                            ║
║     ├── validateFile(): size < 5MB ✓, extension .csv ✓, not empty ✓                     ║
║     ├── calculateHash(): SHA-256("Numer rachunku...") = "a7f3b2..."                      ║
║     └── checkDuplicate(): findByHashAndUserId(hash, userId)                              ║
║         ├── NIE ZNALEZIONO → kontynuuj                                                   ║
║         └── ZNALEZIONO → throw DuplicateFileException (HTTP 409)                         ║
║                                                                                           ║
║  2. DETEKCJA FORMATU:                                                                     ║
║     ├── isCanonicalFormat()? → sprawdź nagłówek "bankTransactionId,name,description..."  ║
║     │   └── NIE → to nie jest canonical format                                           ║
║     │                                                                                     ║
║     ├── computeBankIdentifier() → hash struktury (kolumny + separatory)                  ║
║     │   └── bankIdentifier = "nest_pl_v1_ab3d..."                                        ║
║     │                                                                                     ║
║     └── checkCache(bankIdentifier)?                                                       ║
║         ├── HIT → DetectionResult.CACHED (instant, FREE)                                 ║
║         │         └── użyj cachedRules do transformacji                                  ║
║         │                                                                                 ║
║         └── MISS → DetectionResult.AI_TRANSFORMED                                        ║
║                    └── AI musi stworzyć mapping rules (5-15s, ~$0.01)                    ║
║                                                                                           ║
║  3. TRANSFORMACJA DO CANONICAL FORMAT:                                                    ║
║     ├── AI/Cache zwraca MappingRules:                                                     ║
║     │   {                                                                                 ║
║     │     "bankName": "Nest Bank",                                                        ║
║     │     "dateColumn": "Data operacji",                                                  ║
║     │     "dateFormat": "DD-MM-YYYY",                                                     ║
║     │     "amountColumn": "Kwota",                                                        ║
║     │     "categoryColumn": "Rodzaj operacji",                                            ║
║     │     "counterpartyColumn": "Dane kontrahenta",                                       ║
║     │     "descriptionColumn": "Tytuł operacji",                                          ║
║     │     ...                                                                             ║
║     │   }                                                                                 ║
║     │                                                                                     ║
║     └── localCsvTransformer.transform(csvString, rules):                                 ║
║         ┌─────────────────────────────────────────────────────────────────────────────┐  ║
║         │ TRANSFORMACJA WIERSZA:                                                       │  ║
║         │                                                                              │  ║
║         │ PRZED (Nest Bank format):                                                    │  ║
║         │ 31-12-2025,31-12-2025,Przelewy wychodzące,-3000,PLN,"Lucjan Bik Pekao",     │  ║
║         │ PL98124014441111001078171074,"zycie",76057.25,                              │  ║
║         │                                                                              │  ║
║         │ PO (Canonical BankCsvRow):                                                   │  ║
║         │ bankTransactionId: TXN-2025-12-31-001                                        │  ║
║         │ name: "Lucjan Bik Pekao"                                                     │  ║
║         │ description: "zycie"                                                         │  ║
║         │ bankCategory: "Przelewy wychodzące"                                          │  ║
║         │ amount: 3000.00   // ZAWSZE DODATNIE! Typ określa kierunek                  │  ║
║         │ currency: PLN                                                                │  ║
║         │ type: OUTFLOW    // Kierunek: INFLOW (+) lub OUTFLOW (-)                   │  ║
║         │ operationDate: 2025-12-31                                                    │  ║
║         │ bookingDate: 2025-12-31                                                      │  ║
║         │ sourceAccountNumber: 93187010452083105656550001                              │  ║
║         │ targetAccountNumber: PL98124014441111001078171074                            │  ║
║         └─────────────────────────────────────────────────────────────────────────────┘  ║
║                                                                                           ║
║  4. EKSTRAKCJA METADANYCH:                                                                ║
║     ├── extractDateRange(): min=2023-01-13, max=2025-12-31                               ║
║     ├── suggestedStartPeriod: "2023-01" (najwcześniejszy miesiąc)                        ║
║     ├── monthsOfData: 36                                                                  ║
║     ├── monthsCovered: ["2023-01", "2023-02", ..., "2025-12"]                            ║
║     └── extractBankCategories():                                                          ║
║         [                                                                                 ║
║           { name: "Przelewy wychodzące", count: 150, type: OUTFLOW },                    ║
║           { name: "Opłaty i prowizje", count: 50, type: OUTFLOW },                       ║
║           { name: "Przelewy przychodzące", count: 12, type: INFLOW },                    ║
║           { name: "Płatności kartą", count: 190, type: OUTFLOW }                         ║
║         ]                                                                                 ║
║                                                                                           ║
║  💾 ZAPIS DO MongoDB (collection: ai_csv_transformations):                                ║
║     {                                                                                     ║
║       "_id": "891e699b-2120-42bc-9ad5-5ab692854faa",                                     ║
║       "userId": "U10000123",                                                              ║
║       "originalFileName": "lista_operacji_20260111.csv",                                 ║
║       "originalFileHash": "a7f3b2c4d5e6...",                                             ║
║       "originalCsvContent": "Numer rachunku: 931870...",                                 ║
║       "transformedCsvContent": "bankTransactionId,name,...\nTXN-001,...",               ║
║       "detectedBank": "Nest Bank",                                                        ║
║       "detectedCurrency": "PLN",                                                          ║
║       "detectionResult": "CACHED",                                                        ║
║       "importStatus": "PENDING",    ← KLUCZOWE: jeszcze nie zaimportowano               ║
║       "minTransactionDate": "2023-01-13",                                                ║
║       "maxTransactionDate": "2025-12-31",                                                ║
║       "suggestedStartPeriod": "2023-01",                                                  ║
║       "outputRowCount": 402,                                                              ║
║       "processingTimeMs": 45,                                                             ║
║       "createdAt": "2026-03-28T10:00:00Z"                                                ║
║     }                                                                                     ║
║                                                                                           ║
║  📥 RESPONSE (200 OK):                                                                    ║
║     {                                                                                     ║
║       "transformationId": "891e699b-2120-42bc-9ad5-5ab692854faa",                        ║
║       "success": true,                                                                    ║
║       "detectionResult": "CACHED",                                                        ║
║       "fromCache": true,                                                                  ║
║       "processingTimeMs": 45,                                                             ║
║       "detectedBank": "Nest Bank",                                                        ║
║       "detectedCurrency": "PLN",                                                          ║
║       "rowCount": 402,                                                                    ║
║       "minTransactionDate": "2023-01-13",                                                ║
║       "maxTransactionDate": "2025-12-31",                                                ║
║       "suggestedStartPeriod": "2023-01",                                                  ║
║       "monthsOfData": 36,                                                                 ║
║       "bankCategories": [                                                                 ║
║         { "name": "Przelewy wychodzące", "count": 150, "type": "OUTFLOW" },              ║
║         { "name": "Opłaty i prowizje", "count": 50, "type": "OUTFLOW" },                 ║
║         { "name": "Przelewy przychodzące", "count": 12, "type": "INFLOW" },              ║
║         { "name": "Płatności kartą", "count": 190, "type": "OUTFLOW" }                   ║
║       ],                                                                                  ║
║       "importStatus": "PENDING"                                                           ║
║     }                                                                                     ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
                                            │
                                            ▼
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ KROK 2: USER TWORZY CASHFLOW                                                              ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  POST /cash-flow/with-history                                                             ║
║  Authorization: Bearer eyJhbGciOi...                                                      ║
║  Body:                                                                                    ║
║  {                                                                                        ║
║    "userId": "U10000123",                                                                 ║
║    "name": "Nest Bank - Konto główne",                                                    ║
║    "description": "Główne konto firmowe",                                                 ║
║    "bankAccount": {                                                                       ║
║      "bankName": "Nest Bank",                                                             ║
║      "bankAccountNumber": {                                                               ║
║        "account": "93187010452083105656550001",                                          ║
║        "denomination": { "id": "PLN" }                                                    ║
║      },                                                                                   ║
║      "balance": { "amount": 0, "currency": "PLN" }                                       ║
║    },                                                                                     ║
║    "startPeriod": "2023-01",    ← Z suggestedStartPeriod!                                ║
║    "initialBalance": { "amount": 14484.19, "currency": "PLN" }                           ║
║  }                                                                                        ║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE W CreateCashFlowWithHistoryCommandHandler:                              ║
║                                                                                           ║
║  1. WALIDACJA:                                                                            ║
║     ├── userId istnieje? ✓                                                                ║
║     ├── IBAN format? validateIBAN("93187010452083105656550001")                          ║
║     │   ├── VALID → kontynuuj                                                            ║
║     │   └── INVALID → throw InvalidIbanException (HTTP 400)                              ║
║     ├── startPeriod format YYYY-MM? ✓                                                    ║
║     └── initialBalance > 0 lub = 0? ✓                                                    ║
║                                                                                           ║
║  2. TWORZENIE AGGREGATE:                                                                  ║
║     CashFlow.createWithHistory():                                                         ║
║     ├── cashFlowId = CashFlowId.generate() → "CF10000123"                                ║
║     ├── status = SETUP (tryb importu historycznego)                                      ║
║     ├── startPeriod = YearMonth.parse("2023-01")                                          ║
║     ├── activePeriod = YearMonth.now() → "2026-03"                                       ║
║     ├── initialBalance = Money(14484.19, PLN)                                             ║
║     │                                                                                     ║
║     └── Domyślne kategorie (SYSTEM origin):                                               ║
║         ├── inflowCategories: [Uncategorized]                                             ║
║         └── outflowCategories: [Uncategorized]                                            ║
║                                                                                           ║
║  3. EMIT EVENT → KAFKA (topic: cash_flow):                                               ║
║     CashFlowWithHistoryCreatedEvent {                                                     ║
║       cashFlowId: "CF10000123",                                                           ║
║       userId: "U10000123",                                                                ║
║       startPeriod: "2023-01",                                                             ║
║       activePeriod: "2026-03",                                                            ║
║       currency: "PLN",                                                                    ║
║       initialBalance: 14484.19                                                            ║
║     }                                                                                     ║
║                                                                                           ║
║  💾 ZAPIS DO MongoDB (collection: cash_flows):                                            ║
║     {                                                                                     ║
║       "_id": "CF10000123",                                                                ║
║       "userId": "U10000123",                                                              ║
║       "name": "Nest Bank - Konto główne",                                                 ║
║       "status": "SETUP",    ← TRYB IMPORTU                                               ║
║       "startPeriod": "2023-01",                                                           ║
║       "activePeriod": "2026-03",                                                          ║
║       "bankAccount": { ... },                                                             ║
║       "inflowCategories": [{ "name": "Uncategorized", "origin": "SYSTEM" }],             ║
║       "outflowCategories": [{ "name": "Uncategorized", "origin": "SYSTEM" }],            ║
║       "initialBalance": { "amount": 14484.19, "currency": "PLN" }                        ║
║     }                                                                                     ║
║                                                                                           ║
║  🔄 KAFKA CONSUMER (CashFlowForecastProcessor):                                           ║
║     CashFlowWithHistoryCreatedEventHandler:                                               ║
║     ├── Stwórz CashFlowForecastStatement                                                  ║
║     ├── Dla każdego miesiąca startPeriod → activePeriod-1:                               ║
║     │   └── stwórz CashFlowMonthlyForecast { status: IMPORT_PENDING }                    ║
║     └── Dla activePeriod: { status: ACTIVE }                                              ║
║                                                                                           ║
║  💾 ZAPIS DO MongoDB (collection: cash_flow_forecast_statement):                          ║
║     {                                                                                     ║
║       "cashFlowId": "CF10000123",                                                         ║
║       "forecasts": {                                                                      ║
║         "2023-01": { "status": "IMPORT_PENDING", "inflows": [], "outflows": [] },        ║
║         "2023-02": { "status": "IMPORT_PENDING", "inflows": [], "outflows": [] },        ║
║         ...                                                                               ║
║         "2026-02": { "status": "IMPORT_PENDING", "inflows": [], "outflows": [] },        ║
║         "2026-03": { "status": "ACTIVE", "inflows": [], "outflows": [] }                 ║
║       },                                                                                  ║
║       "categoryStructure": {                                                              ║
║         "inflowCategories": [{ "name": "Uncategorized" }],                               ║
║         "outflowCategories": [{ "name": "Uncategorized" }]                               ║
║       }                                                                                   ║
║     }                                                                                     ║
║                                                                                           ║
║  📥 RESPONSE (201 Created):                                                               ║
║     { "cashFlowId": "CF10000123" }                                                        ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
                                            │
                                            ▼
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ KROK 3: USER KONFIGURUJE MAPOWANIA KATEGORII                                              ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  ⚠️ OBECNA WERSJA: Mapowanie jest MANUALNE na poziomie bankCategory                       ║
║  🎯 DOCELOWA WERSJA: AI kategoryzuje po nazwie transakcji (szczegóły poniżej)            ║
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  POST /api/v1/bank-data-ingestion/cf=CF10000123/mappings                                  ║
║  Body:                                                                                    ║
║  {                                                                                        ║
║    "mappings": [                                                                          ║
║      {                                                                                    ║
║        "bankCategoryName": "Przelewy wychodzące",                                         ║
║        "targetCategoryName": "Przelewy",                                                  ║
║        "parentCategoryName": null,                                                        ║
║        "categoryType": "OUTFLOW",                                                         ║
║        "action": "CREATE_NEW"                                                             ║
║      },                                                                                   ║
║      {                                                                                    ║
║        "bankCategoryName": "Opłaty i prowizje",                                           ║
║        "targetCategoryName": "Opłaty bankowe",                                            ║
║        "categoryType": "OUTFLOW",                                                         ║
║        "action": "CREATE_NEW"                                                             ║
║      },                                                                                   ║
║      {                                                                                    ║
║        "bankCategoryName": "Przelewy przychodzące",                                       ║
║        "targetCategoryName": "Przychody",                                                 ║
║        "categoryType": "INFLOW",                                                          ║
║        "action": "CREATE_NEW"                                                             ║
║      },                                                                                   ║
║      {                                                                                    ║
║        "bankCategoryName": "Płatności kartą",                                             ║
║        "targetCategoryName": "Wydatki kartą",                                             ║
║        "categoryType": "OUTFLOW",                                                         ║
║        "action": "CREATE_NEW"                                                             ║
║      }                                                                                    ║
║    ]                                                                                      ║
║  }                                                                                        ║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE W ConfigureCategoryMappingCommandHandler:                               ║
║                                                                                           ║
║  1. WALIDACJA:                                                                            ║
║     ├── CashFlow istnieje? ✓                                                              ║
║     ├── CashFlow.status == SETUP? ✓ (mappingi tylko w trybie SETUP)                      ║
║     ├── Dla każdego mapping:                                                              ║
║     │   ├── bankCategoryName not empty? ✓                                                 ║
║     │   ├── targetCategoryName not empty? ✓                                               ║
║     │   └── categoryType valid (INFLOW/OUTFLOW)? ✓                                       ║
║     │                                                                                     ║
║     └── action validation:                                                                ║
║         ├── CREATE_NEW → targetCategoryName NIE istnieje w CashFlow                      ║
║         ├── MAP_TO_EXISTING → targetCategoryName MUSI istnieć w CashFlow                 ║
║         └── CREATE_SUBCATEGORY → parentCategoryName MUSI istnieć                         ║
║                                                                                           ║
║  💾 ZAPIS DO MongoDB (collection: category_mappings):                                     ║
║     [                                                                                     ║
║       {                                                                                   ║
║         "cashFlowId": "CF10000123",                                                       ║
║         "bankCategoryName": "Przelewy wychodzące",                                        ║
║         "targetCategoryName": "Przelewy",                                                 ║
║         "parentCategoryName": null,                                                       ║
║         "categoryType": "OUTFLOW",                                                        ║
║         "action": "CREATE_NEW"                                                            ║
║       },                                                                                  ║
║       ...                                                                                 ║
║     ]                                                                                     ║
║                                                                                           ║
║  📥 RESPONSE (200 OK):                                                                    ║
║     { "configured": 4, "message": "Mappings configured successfully" }                   ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
                                            │
                                            ▼
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ KROK 4: USER WYSYŁA TRANSFORMACJĘ DO STAGING                                              ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  ⚠️ ARCHITEKTURA: Moduły bank_data_adapter i bank_data_ingestion są CELOWO ROZDZIELONE   ║
║                                                                                           ║
║  bank_data_adapter:   Transformacja CSV (format detection, AI/cache)                      ║
║  bank_data_ingestion: Staging i import do CashFlow (per cashFlowId)                       ║
║                                                                                           ║
║  Komunikacja odbywa się przez REST API (BankDataIngestionClient)                          ║
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  POST /api/v1/bank-data-adapter/{transformationId}/import                                 ║
║  Authorization: Bearer eyJhbGciOi...                                                      ║
║  Body: { "cashFlowId": "CF10000123" }                                                     ║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE W AiBankCsvController.importToCashFlow():                               ║
║                                                                                           ║
║  1. WALIDACJA W BANK_DATA_ADAPTER:                                                        ║
║     ├── transformService.getTransformation(transformationId, userId)                      ║
║     │   ├── Transformation istnieje? ✓                                                    ║
║     │   └── Transformation.userId == request.userId? ✓ (security check)                  ║
║     └── Transformation.importStatus == PENDING? (opcjonalnie)                             ║
║                                                                                           ║
║  2. WYSŁANIE DO BANK_DATA_INGESTION (via BankDataIngestionClient):                        ║
║     ├── ingestionClient.sendToIngestion(cashFlowId, transformedCsvContent, ...)           ║
║     │                                                                                     ║
║     │   Wewnętrznie wykonuje:                                                             ║
║     │   POST /api/v1/bank-data-ingestion/cf={cashFlowId}/upload                           ║
║     │   Content-Type: multipart/form-data                                                 ║
║     │   Body: file = ai_transformed_{id}.csv (canonical BankCsvRow format)                ║
║     │                                                                                     ║
║     └── Zwraca: UploadCsvResponse { stagingSessionId, parseSummary, ... }                ║
║                                                                                           ║
║  3. AKTUALIZACJA TRANSFORMACJI:                                                           ║
║     transformService.markAsImported(transformationId, userId, stagingSessionId)           ║
║     └── ai_csv_transformations.stagingSessionId = "session-789" (link do ingestion)      ║
║                                                                                           ║
║  ─────────────────────────────────────────────────────────────────────────────────────────║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE W BANK_DATA_INGESTION (UploadCsvCommandHandler):                        ║
║                                                                                           ║
║  1. PARSOWANIE CANONICAL CSV (CsvParserService):                                          ║
║     transformedCsvContent → List<BankCsvRow>:                                            ║
║     [                                                                                     ║
║       BankCsvRow {                                                                        ║
║         bankTransactionId: "TXN-2025-12-31-001",                                         ║
║         name: "Lucjan Bik Pekao",                                                         ║
║         description: "zycie",                                                             ║
║         bankCategory: "Przelewy wychodzące",                                              ║
║         amount: 3000.00,   // ZAWSZE DODATNIE! Typ określa kierunek                      ║
║         currency: "PLN",                                                                  ║
║         type: OUTFLOW,     // Kierunek: INFLOW (+) lub OUTFLOW (-)                       ║
║         operationDate: 2025-12-31                                                         ║
║       },                                                                                  ║
║       ... (402 rows)                                                                      ║
║     ]                                                                                     ║
║                                                                                           ║
║  2. STAGING TRANSACTIONS (StageTransactionsCommandHandler):                               ║
║                                                                                           ║
║     Dla każdej transakcji:                                                                ║
║     ┌─────────────────────────────────────────────────────────────────────────────────┐  ║
║     │ TRANSFORMACJA:                                                                   │  ║
║     │                                                                                  │  ║
║     │ BankCsvRow → StagedTransaction                                                   │  ║
║     │                                                                                  │  ║
║     │ 1. Stwórz OriginalTransactionData:                                               │  ║
║     │    { bankTransactionId, name, description, bankCategory, money, type, paidDate } │  ║
║     │                                                                                  │  ║
║     │ 2. Znajdź mapping: mappingMap.get(bankCategory + type)                           │  ║
║     │    ├── FOUND → stwórz MappedTransactionData                                      │  ║
║     │    │           { name, description, targetCategoryName, parentCategoryName, ... }│  ║
║     │    └── NOT FOUND → status = PENDING_MAPPING                                      │  ║
║     │                                                                                  │  ║
║     │ 3. Walidacja:                                                                    │  ║
║     │    ├── Duplikat? existingBankTransactionIds.contains(id)?                        │  ║
║     │    │   └── TAK → status = DUPLICATE                                              │  ║
║     │    ├── Data w zakresie? paidDate >= startPeriod?                                 │  ║
║     │    │   └── NIE → error "paidDate before startPeriod"                             │  ║
║     │    ├── CashFlow w SETUP? → paidDate < activePeriod?                              │  ║
║     │    │   └── NIE → error "In SETUP mode, must be before activePeriod"              │  ║
║     │    └── Data w przyszłości?                                                       │  ║
║     │        └── TAK → error "paidDate cannot be in future"                            │  ║
║     │                                                                                  │  ║
║     │ 4. Ustal status:                                                                 │  ║
║     │    ├── Brak błędów + mapping → VALID                                             │  ║
║     │    ├── Brak mapping → PENDING_MAPPING                                            │  ║
║     │    ├── Duplikat → DUPLICATE                                                      │  ║
║     │    └── Błędy walidacji → INVALID                                                 │  ║
║     └─────────────────────────────────────────────────────────────────────────────────┘  ║
║                                                                                           ║
║  💾 ZAPIS DO MongoDB (collection: staged_transactions):                                   ║
║     [                                                                                     ║
║       {                                                                                   ║
║         "stagingSessionId": "session-789",                                               ║
║         "cashFlowId": "CF10000123",                                                       ║
║         "originalData": {                                                                 ║
║           "bankTransactionId": "TXN-2025-12-31-001",                                     ║
║           "name": "Lucjan Bik Pekao",                                                     ║
║           "bankCategory": "Przelewy wychodzące",                                          ║
║           ...                                                                             ║
║         },                                                                                ║
║         "mappedData": {                                                                   ║
║           "categoryName": "Przelewy",                                                     ║
║           "parentCategoryName": null,                                                     ║
║           ...                                                                             ║
║         },                                                                                ║
║         "validation": { "status": "VALID" },                                              ║
║         "expiresAt": "2026-03-29T10:00:00Z"   ← TTL 24h                                  ║
║       },                                                                                  ║
║       ...                                                                                 ║
║     ]                                                                                     ║
║                                                                                           ║
║  📥 RESPONSE (200 OK):                                                                    ║
║     {                                                                                     ║
║       "stagingSessionId": "session-789",                                                  ║
║       "cashFlowId": "CF10000123",                                                         ║
║       "status": "READY_FOR_IMPORT",   lub "HAS_UNMAPPED_CATEGORIES"                      ║
║       "expiresAt": "2026-03-29T10:00:00Z",                                               ║
║       "summary": {                                                                        ║
║         "totalTransactions": 402,                                                         ║
║         "validTransactions": 400,                                                         ║
║         "invalidTransactions": 0,                                                         ║
║         "duplicateTransactions": 2                                                        ║
║       },                                                                                  ║
║       "categoryBreakdown": [                                                              ║
║         { "targetCategory": "Przelewy", "count": 150, "totalAmount": -500000.00 },       ║
║         { "targetCategory": "Opłaty bankowe", "count": 50, "totalAmount": -500.00 },     ║
║         { "targetCategory": "Przychody", "count": 12, "totalAmount": 1217096.34 },       ║
║         { "targetCategory": "Wydatki kartą", "count": 188, "totalAmount": -640000.00 }   ║
║       ],                                                                                  ║
║       "categoriesToCreate": [                                                             ║
║         { "name": "Przelewy", "type": "OUTFLOW" },                                       ║
║         { "name": "Opłaty bankowe", "type": "OUTFLOW" },                                 ║
║         { "name": "Przychody", "type": "INFLOW" },                                       ║
║         { "name": "Wydatki kartą", "type": "OUTFLOW" }                                   ║
║       ],                                                                                  ║
║       "monthlyBreakdown": [                                                               ║
║         { "month": "2023-01", "inflowTotal": 34506.42, "outflowTotal": -20000.00 },      ║
║         ...                                                                               ║
║       ],                                                                                  ║
║       "unmappedCategories": []    ← puste jeśli wszystkie zmapowane                      ║
║     }                                                                                     ║
║                                                                                           ║
║  ─────────────────────────────────────────────────────────────────────────────────────────║
║                                                                                           ║
║  📥 FINALNA RESPONSE Z BANK_DATA_ADAPTER (200 OK):                                        ║
║     {                                                                                     ║
║       "transformationId": "891e699b-2120-42bc-9ad5-5ab692854faa",                        ║
║       "stagingSessionId": "session-789",    ← KLUCZ do dalszych operacji                 ║
║       "importedRows": 402,                                                                ║
║       "message": "Transformation imported successfully"                                   ║
║     }                                                                                     ║
║                                                                                           ║
║  💾 AKTUALIZACJA MongoDB (collection: ai_csv_transformations):                            ║
║     {                                                                                     ║
║       "importStatus": "IMPORTED",                                                         ║
║       "stagingSessionId": "session-789",  ← link między modułami                         ║
║       "importedAt": "2026-03-28T10:00:30Z"                                               ║
║     }                                                                                     ║
║                                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
                                            │
                                            ▼
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ KROK 5: USER URUCHAMIA IMPORT                                                             ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  POST /api/v1/bank-data-ingestion/cf=CF10000123/import                                    ║
║  Body: { "stagingSessionId": "session-789" }                                              ║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE W StartImportJobCommandHandler:                                         ║
║                                                                                           ║
║  1. WALIDACJA:                                                                            ║
║     ├── Staging session istnieje? ✓                                                       ║
║     ├── Staging session.status == READY_FOR_IMPORT? ✓                                    ║
║     │   └── HAS_UNMAPPED_CATEGORIES → throw StagingNotReadyException                     ║
║     ├── Brak już istniejącego ImportJob dla tej sesji? ✓                                 ║
║     │   └── ISTNIEJE → throw ImportJobAlreadyExistsException                             ║
║     └── CashFlow.status == SETUP? ✓                                                       ║
║                                                                                           ║
║  2. FAZA 1: TWORZENIE KATEGORII                                                           ║
║     Dla każdej kategorii z categoriesToCreate:                                            ║
║     ├── POST /cash-flow/cf=CF10000123/category                                            ║
║     │   Body: { "categoryName": "Przelewy", "type": "OUTFLOW" }                          ║
║     │                                                                                     ║
║     │   EMIT → CategoryCreatedEvent → Kafka → ForecastProcessor                          ║
║     │   └── Dodaj kategorię do categoryStructure w Forecast                              ║
║     │                                                                                     ║
║     └── Jeśli ma parent: najpierw stwórz parent, potem child jako subcategory           ║
║                                                                                           ║
║  3. FAZA 2: IMPORTOWANIE TRANSAKCJI                                                       ║
║     Dla każdej VALID transakcji (skip DUPLICATE):                                         ║
║     ├── POST /cash-flow/cf=CF10000123/import-historical                                   ║
║     │   Body: {                                                                           ║
║     │     "name": "Lucjan Bik Pekao",                                                     ║
║     │     "description": "zycie",                                                         ║
║     │     "category": "Przelewy",                                                         ║
║     │     "money": { "amount": 3000.00, "currency": "PLN" },  // ZAWSZE dodatnie!        ║
║     │     "type": "OUTFLOW",  // typ określa kierunek                                     ║
║     │     "dueDate": "2025-12-31",                                                        ║
║     │     "paidDate": "2025-12-31"                                                        ║
║     │   }                                                                                 ║
║     │                                                                                     ║
║     │   EMIT → HistoricalCashChangeImportedEvent → Kafka → ForecastProcessor             ║
║     │   └── Dodaj transakcję do odpowiedniego miesiąca w Forecast                        ║
║     │                                                                                     ║
║     └── Progress: importedCount / totalToImport                                           ║
║                                                                                           ║
║  💾 ZAPIS DO MongoDB (collection: import_jobs):                                           ║
║     {                                                                                     ║
║       "jobId": "job-123",                                                                 ║
║       "stagingSessionId": "session-789",                                                  ║
║       "cashFlowId": "CF10000123",                                                         ║
║       "status": "COMPLETED",                                                              ║
║       "progress": {                                                                       ║
║         "categoriesCreated": 4,                                                           ║
║         "transactionsImported": 400,                                                      ║
║         "transactionsSkipped": 2   ← duplikaty                                           ║
║       },                                                                                  ║
║       "startedAt": "2026-03-28T10:01:00Z",                                               ║
║       "completedAt": "2026-03-28T10:02:30Z"                                               ║
║     }                                                                                     ║
║                                                                                           ║
║  💾 UPDATE MongoDB (collection: ai_csv_transformations):                                  ║
║     { "importStatus": "IMPORTED", "importedAt": "2026-03-28T10:02:30Z" }                 ║
║                                                                                           ║
║  📥 RESPONSE (202 Accepted):                                                              ║
║     {                                                                                     ║
║       "jobId": "job-123",                                                                 ║
║       "status": "IMPORTING",                                                              ║
║       "progress": { "phase": "IMPORTING_TRANSACTIONS", "percent": 50 },                  ║
║       "pollUrl": "/api/v1/bank-data-ingestion/cf=CF10000123/import/job-123"              ║
║     }                                                                                     ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
                                            │
                                            ▼
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ KROK 6: USER POTWIERDZA IMPORT (ATTESTATION)                                              ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  POST /cash-flow/cf=CF10000123/attest-historical-import                                   ║
║  Body:                                                                                    ║
║  {                                                                                        ║
║    "confirmedBalance": { "amount": 76047.25, "currency": "PLN" },                        ║
║    "createAdjustment": true,                                                              ║
║    "forceAttestation": false                                                              ║
║  }                                                                                        ║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE W AttestHistoricalImportCommandHandler:                                 ║
║                                                                                           ║
║  1. KALKULACJA SALDA:                                                                     ║
║     calculatedBalance = initialBalance + Σ(inflows) - Σ(outflows)                        ║
║                       = 14484.19 + 1217096.34 - 1141912.03                               ║
║                       = 89668.50 PLN                                                      ║
║                                                                                           ║
║  2. PORÓWNANIE Z confirmedBalance:                                                        ║
║     ├── calculatedBalance (89668.50) vs confirmedBalance (76047.25)                      ║
║     ├── difference = 13621.25 PLN                                                        ║
║     │                                                                                     ║
║     └── RÓŻNICA!                                                                          ║
║         ├── createAdjustment=true → stwórz transakcję korygującą                         ║
║         │   CashChange { name: "Balance Adjustment", amount: 13621.25, type: OUTFLOW }   ║
║         │                                                                                 ║
║         ├── forceAttestation=true → zignoruj różnicę (nie twórz adjustment)              ║
║         │                                                                                 ║
║         └── oba false → throw BalanceMismatchException                                   ║
║                                                                                           ║
║  3. EMIT EVENT → KAFKA:                                                                   ║
║     HistoricalImportAttestedEvent {                                                       ║
║       cashFlowId: "CF10000123",                                                           ║
║       attestedAt: "2026-03-28T10:05:00Z",                                                ║
║       confirmedBalance: 76047.25,                                                         ║
║       adjustmentCreated: true                                                             ║
║     }                                                                                     ║
║                                                                                           ║
║  💾 UPDATE MongoDB (collection: cash_flows):                                              ║
║     {                                                                                     ║
║       "status": "OPEN",    ← ZMIANA Z SETUP!                                             ║
║       "importCutoffDateTime": "2026-03-28T10:05:00Z",                                    ║
║       "bankAccount.balance": { "amount": 76047.25, "currency": "PLN" }                   ║
║     }                                                                                     ║
║                                                                                           ║
║  🔄 KAFKA CONSUMER (CashFlowForecastProcessor):                                           ║
║     HistoricalImportAttestedEventHandler:                                                 ║
║     ├── Dla każdego miesiąca IMPORT_PENDING → IMPORTED                                   ║
║     └── Ustaw attestation info                                                            ║
║                                                                                           ║
║  📥 RESPONSE (200 OK):                                                                    ║
║     {                                                                                     ║
║       "status": "SUCCESS",                                                                ║
║       "cashFlowStatus": "OPEN",                                                           ║
║       "calculatedBalance": { "amount": 89668.50, "currency": "PLN" },                    ║
║       "confirmedBalance": { "amount": 76047.25, "currency": "PLN" },                     ║
║       "adjustmentCreated": true,                                                          ║
║       "adjustmentAmount": { "amount": -13621.25, "currency": "PLN" }                     ║
║     }                                                                                     ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
                                            │
                                            ▼
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ KROK 7: WERYFIKACJA W FORECAST                                                            ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  GET /cash-flow-forecast/cf=CF10000123                                                    ║
║                                                                                           ║
║  📥 RESPONSE:                                                                             ║
║  {                                                                                        ║
║    "cashFlowId": "CF10000123",                                                            ║
║    "forecasts": {                                                                         ║
║      "2023-01": {                                                                         ║
║        "status": "IMPORTED",                                                              ║
║        "categorizedInFlows": [                                                            ║
║          { "category": "Przychody", "amount": 34506.42, "count": 1 }                     ║
║        ],                                                                                 ║
║        "categorizedOutFlows": [                                                           ║
║          { "category": "Przelewy", "amount": -40883.17, "count": 5 },                    ║
║          { "category": "Opłaty bankowe", "amount": -124.00, "count": 1 }                 ║
║        ]                                                                                  ║
║      },                                                                                   ║
║      ...                                                                                  ║
║      "2026-03": {                                                                         ║
║        "status": "ACTIVE"                                                                 ║
║      }                                                                                    ║
║    },                                                                                     ║
║    "categoryStructure": {                                                                 ║
║      "inflowCategories": [                                                                ║
║        { "name": "Przychody", "subCategories": [], "origin": "IMPORTED" },               ║
║        { "name": "Uncategorized", "subCategories": [], "origin": "SYSTEM" }              ║
║      ],                                                                                   ║
║      "outflowCategories": [                                                               ║
║        { "name": "Przelewy", "subCategories": [], "origin": "IMPORTED" },                ║
║        { "name": "Opłaty bankowe", "subCategories": [], "origin": "IMPORTED" },          ║
║        { "name": "Wydatki kartą", "subCategories": [], "origin": "IMPORTED" },           ║
║        { "name": "Uncategorized", "subCategories": [], "origin": "SYSTEM" }              ║
║      ]                                                                                    ║
║    }                                                                                      ║
║  }                                                                                        ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
```

---

## Error Handling & Recovery Scenarios

### Scenariusz 1: Duplikat pliku (ten sam hash)

```
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ BŁĄD: DUPLICATE FILE (HTTP 409)                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  USER wgrywa ten sam plik po raz drugi                                                    ║
║                                                                                           ║
║  📤 REQUEST: POST /api/v1/csv-import/upload                                               ║
║              file=lista_operacji_20260111.csv (identyczna treść)                          ║
║                                                                                           ║
║  ⚙️ PRZETWARZANIE:                                                                        ║
║  1. calculateHash(csvContent) = "a7f3b2c4d5e6..."                                        ║
║  2. transformationRepository.findByOriginalFileHashAndUserId(hash, userId)               ║
║     └── ZNALEZIONO existing transformation!                                               ║
║  3. throw DuplicateFileException(hash, existingTransformationId)                         ║
║                                                                                           ║
║  📥 RESPONSE (409 Conflict):                                                              ║
║  {                                                                                        ║
║    "timestamp": "2026-03-28T10:00:00Z",                                                  ║
║    "status": 409,                                                                         ║
║    "error": "Conflict",                                                                   ║
║    "errorCode": "AI_ADAPTER_DUPLICATE_FILE",                                             ║
║    "message": "File with hash [a7f3b2...] already processed",                            ║
║    "details": {                                                                           ║
║      "existingTransformationId": "891e699b-2120-42bc-9ad5-5ab692854faa"                  ║
║    }                                                                                      ║
║  }                                                                                        ║
║                                                                                           ║
║  🔧 ROZWIĄZANIE DLA UI:                                                                   ║
║                                                                                           ║
║  1. Sprawdź status istniejącej transformacji:                                             ║
║     GET /api/v1/bank-data-adapter/history/{existingTransformationId}                     ║
║                                                                                           ║
║  2. Jeśli importStatus == PENDING:                                                        ║
║     └── "Ten plik już został przetworzony. Chcesz kontynuować import?"                   ║
║         [Kontynuuj import] → przejdź do kroku tworzenia CashFlow                         ║
║         [Anuluj]                                                                          ║
║                                                                                           ║
║  3. Jeśli importStatus == IMPORTED:                                                       ║
║     └── "Ten plik został już zaimportowany do CashFlow [nazwa]"                          ║
║         [Zobacz CashFlow] → redirect do CF                                                ║
║                                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
```

### Scenariusz 2: Błąd przy tworzeniu CashFlow (np. zły IBAN)

```
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ BŁĄD: INVALID IBAN (HTTP 400) + PONOWNE WGRANIE PLIKU                                     ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  SYTUACJA:                                                                                ║
║  1. User wgrał CSV → transformationId = "abc123" (OK)                                    ║
║  2. User próbuje stworzyć CashFlow z błędnym IBAN → HTTP 400                             ║
║  3. User chce wgrać ten sam plik ponownie (np. po poprawieniu IBAN w formularzu)         ║
║  4. PROBLEM: Duplikat pliku blokuje wgranie!                                              ║
║                                                                                           ║
║  ❌ OBECNE ZACHOWANIE:                                                                    ║
║  ┌─────────────────────────────────────────────────────────────────────────────────────┐  ║
║  │ User → POST /csv-import/upload (ten sam plik)                                       │  ║
║  │ → HTTP 409 DuplicateFileException                                                    │  ║
║  │ → User nie może kontynuować!                                                         │  ║
║  └─────────────────────────────────────────────────────────────────────────────────────┘  ║
║                                                                                           ║
║  ✅ PROPONOWANE ROZWIĄZANIE:                                                              ║
║                                                                                           ║
║  OPCJA A: Re-use istniejącej transformacji (REKOMENDOWANE)                               ║
║  ───────────────────────────────────────────────────────────────────────────────────────  ║
║  1. Zmień logikę w AiBankCsvTransformService:                                             ║
║     ```java                                                                               ║
║     transformationRepository.findByOriginalFileHashAndUserId(fileHash, userId)           ║
║         .ifPresent(existing -> {                                                          ║
║             if (existing.getImportStatus() == ImportStatus.PENDING) {                    ║
║                 // Zamiast błędu - zwróć istniejącą transformację                        ║
║                 return existingTransformationToResponse(existing);                        ║
║             } else if (existing.getImportStatus() == ImportStatus.IMPORTED) {            ║
║                 throw new AlreadyImportedException(existing.getId());                    ║
║             }                                                                             ║
║         });                                                                               ║
║     ```                                                                                   ║
║                                                                                           ║
║  2. Flow dla usera:                                                                       ║
║     User → POST /csv-import/upload (ten sam plik)                                        ║
║     → HTTP 200 (zwraca istniejący transformationId)                                      ║
║     → User może teraz stworzyć CashFlow z poprawionym IBAN                               ║
║                                                                                           ║
║  OPCJA B: Endpoint do usunięcia transformacji                                             ║
║  ───────────────────────────────────────────────────────────────────────────────────────  ║
║  1. Dodaj endpoint:                                                                       ║
║     DELETE /api/v1/bank-data-adapter/transformation/{id}                                 ║
║     ├── Walidacja: importStatus == PENDING (nie można usunąć imported)                   ║
║     └── Usuwa transformację z bazy                                                        ║
║                                                                                           ║
║  2. Flow dla usera:                                                                       ║
║     User → DELETE /transformation/abc123                                                  ║
║     User → POST /csv-import/upload (ten sam plik)                                        ║
║     → HTTP 200 (nowy transformationId)                                                    ║
║                                                                                           ║
║  OPCJA C: Retry endpoint (bez ponownego wgrywania)                                        ║
║  ───────────────────────────────────────────────────────────────────────────────────────  ║
║  1. UI pamięta transformationId z poprzedniego upload                                    ║
║  2. Przy ponownej próbie CashFlow → użyj tego samego transformationId                    ║
║  3. Nie trzeba wgrywać pliku ponownie                                                     ║
║                                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
```

### Scenariusz 3: Brak mapowań kategorii

```
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ BŁĄD: UNMAPPED CATEGORIES (staging nie gotowy)                                            ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  SYTUACJA:                                                                                ║
║  User wgrywa CSV ale nie skonfigurował wszystkich mapowań                                 ║
║                                                                                           ║
║  📥 RESPONSE z staging:                                                                   ║
║  {                                                                                        ║
║    "stagingSessionId": "session-789",                                                     ║
║    "status": "HAS_UNMAPPED_CATEGORIES",    ← NIE MOŻNA importować!                       ║
║    "unmappedCategories": [                                                                ║
║      { "bankCategory": "Płatności kartą", "count": 190, "type": "OUTFLOW" }              ║
║    ]                                                                                      ║
║  }                                                                                        ║
║                                                                                           ║
║  🔧 ROZWIĄZANIE:                                                                          ║
║                                                                                           ║
║  1. UI pokazuje brakujące mapowania:                                                      ║
║     ┌─────────────────────────────────────────────────────────────────────────────────┐  ║
║     │ ⚠️ Brakuje mapowań dla następujących kategorii bankowych:                        │  ║
║     │                                                                                  │  ║
║     │ • "Płatności kartą" (190 transakcji, wydatki)                                   │  ║
║     │   Docelowa kategoria: [___________] [Utwórz nową ▼]                             │  ║
║     │                                                                                  │  ║
║     │ [Zapisz mapowania]                                                               │  ║
║     └─────────────────────────────────────────────────────────────────────────────────┘  ║
║                                                                                           ║
║  2. User dodaje brakujące mapowania:                                                      ║
║     POST /api/v1/bank-data-ingestion/cf=CF10000123/mappings                              ║
║     Body: { "mappings": [{ "bankCategoryName": "Płatności kartą", ... }] }               ║
║                                                                                           ║
║  3. Rewalidacja staging session:                                                          ║
║     POST /api/v1/bank-data-ingestion/cf=CF10000123/staging/session-789/revalidate        ║
║                                                                                           ║
║  4. Teraz status = READY_FOR_IMPORT                                                       ║
║                                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
```

### Scenariusz 4: Import job failed

```
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ BŁĄD: IMPORT JOB FAILED                                                                   ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  SYTUACJA:                                                                                ║
║  Import zatrzymał się w trakcie (np. błąd przy tworzeniu kategorii)                       ║
║                                                                                           ║
║  📥 RESPONSE z GET /import/job-123:                                                       ║
║  {                                                                                        ║
║    "jobId": "job-123",                                                                    ║
║    "status": "FAILED",                                                                    ║
║    "error": {                                                                             ║
║      "phase": "CREATING_CATEGORIES",                                                      ║
║      "message": "Category 'Przelewy' already exists",                                    ║
║      "failedItem": "Przelewy"                                                             ║
║    },                                                                                     ║
║    "progress": {                                                                          ║
║      "categoriesCreated": 2,                                                              ║
║      "categoriesTotal": 4,                                                                ║
║      "transactionsImported": 0,                                                           ║
║      "transactionsTotal": 400                                                             ║
║    }                                                                                      ║
║  }                                                                                        ║
║                                                                                           ║
║  🔧 ROZWIĄZANIE:                                                                          ║
║                                                                                           ║
║  OPCJA A: Rollback i retry                                                                ║
║  ───────────────────────────────────────────────────────────────────────────────────────  ║
║  1. POST /api/v1/bank-data-ingestion/cf=CF10000123/import/job-123/rollback               ║
║     └── Usuwa wszystkie zaimportowane transakcje i kategorie                             ║
║                                                                                           ║
║  2. Napraw problem (np. zmień nazwę kategorii w mappingu)                                 ║
║                                                                                           ║
║  3. POST /api/v1/bank-data-ingestion/cf=CF10000123/import                                ║
║     └── Nowy import job                                                                   ║
║                                                                                           ║
║  OPCJA B: Resume z punktu błędu (bardziej zaawansowane)                                   ║
║  ───────────────────────────────────────────────────────────────────────────────────────  ║
║  1. POST /api/v1/bank-data-ingestion/cf=CF10000123/import/job-123/resume                 ║
║     Body: { "skipFailedCategory": true }                                                  ║
║     └── Kontynuuje od miejsca błędu, pomija problematyczną kategorię                     ║
║                                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
```

### Scenariusz 5: Attestation balance mismatch

```
╔═══════════════════════════════════════════════════════════════════════════════════════════╗
║ BŁĄD: BALANCE MISMATCH (HTTP 400)                                                         ║
╠═══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                           ║
║  SYTUACJA:                                                                                ║
║  Obliczone saldo ≠ saldo potwierdzone przez użytkownika                                   ║
║                                                                                           ║
║  📤 REQUEST:                                                                              ║
║  POST /cash-flow/cf=CF10000123/attest-historical-import                                   ║
║  Body: {                                                                                  ║
║    "confirmedBalance": { "amount": 76047.25, "currency": "PLN" },                        ║
║    "createAdjustment": false,                                                             ║
║    "forceAttestation": false                                                              ║
║  }                                                                                        ║
║                                                                                           ║
║  📥 RESPONSE (400 Bad Request):                                                           ║
║  {                                                                                        ║
║    "status": "BALANCE_MISMATCH",                                                          ║
║    "calculatedBalance": { "amount": 89668.50, "currency": "PLN" },                       ║
║    "confirmedBalance": { "amount": 76047.25, "currency": "PLN" },                        ║
║    "difference": { "amount": 13621.25, "currency": "PLN" },                              ║
║    "options": [                                                                           ║
║      {                                                                                    ║
║        "action": "CREATE_ADJUSTMENT",                                                     ║
║        "description": "Utworzy transakcję korygującą -13621.25 PLN"                      ║
║      },                                                                                   ║
║      {                                                                                    ║
║        "action": "FORCE_ATTESTATION",                                                     ║
║        "description": "Zignoruj różnicę i potwierdź import"                              ║
║      },                                                                                   ║
║      {                                                                                    ║
║        "action": "REVIEW_TRANSACTIONS",                                                   ║
║        "description": "Sprawdź zaimportowane transakcje"                                 ║
║      }                                                                                    ║
║    ]                                                                                      ║
║  }                                                                                        ║
║                                                                                           ║
║  🔧 UI:                                                                                   ║
║  ┌─────────────────────────────────────────────────────────────────────────────────────┐  ║
║  │ ⚠️ Różnica w saldzie!                                                               │  ║
║  │                                                                                      │  ║
║  │ Obliczone saldo:   89,668.50 PLN                                                    │  ║
║  │ Twoje saldo:       76,047.25 PLN                                                    │  ║
║  │ Różnica:           13,621.25 PLN                                                    │  ║
║  │                                                                                      │  ║
║  │ Co chcesz zrobić?                                                                   │  ║
║  │                                                                                      │  ║
║  │ [Utwórz korektę] → doda transakcję "Balance Adjustment"                             │  ║
║  │ [Zignoruj]       → potwierdź mimo różnicy                                           │  ║
║  │ [Sprawdź transakcje] → wróć do listy transakcji                                     │  ║
║  └─────────────────────────────────────────────────────────────────────────────────────┘  ║
║                                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
```

---

## Data Transformation Summary

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    PODSUMOWANIE TRANSFORMACJI DANYCH                                     │
└─────────────────────────────────────────────────────────────────────────────────────────┘

KROK 1: CSV Upload
├── INPUT:  Raw bank CSV (dowolny format: Nest, mBank, ING, PKO...)
├── OUTPUT: Canonical BankCsvRow format + metadata
├── SAVED:  ai_csv_transformations collection
└── TRANSFORMACJA:
    "Data operacji,Rodzaj,Kwota,Kontrahent,Tytuł"     →    "bankTransactionId,name,description,
    "31-12-2025,Przelewy,-3000,Lucjan Bik,zycie"           bankCategory,amount,currency,type,
                                                           operationDate,bookingDate,source,target"

KROK 2: CashFlow Creation
├── INPUT:  suggestedStartPeriod, detectedCurrency, user-provided name/IBAN
├── OUTPUT: CashFlow aggregate w statusie SETUP
├── SAVED:  cash_flows collection, cash_flow_forecast_statement
└── TRANSFORMACJA:
    { startPeriod: "2023-01" }     →     forecasts: {
                                           "2023-01": { status: IMPORT_PENDING },
                                           "2023-02": { status: IMPORT_PENDING },
                                           ...
                                           "2026-03": { status: ACTIVE }
                                         }

KROK 3: Category Mappings
├── INPUT:  bankCategories z CSV + user's target category names
├── OUTPUT: Mapping rules
├── SAVED:  category_mappings collection
└── TRANSFORMACJA:
    bankCategory: "Przelewy wychodzące"     →     targetCategory: "Przelewy"
    bankCategory: "Opłaty i prowizje"       →     targetCategory: "Opłaty bankowe"

KROK 4: Staging
├── INPUT:  Canonical CSV rows + mapping rules
├── OUTPUT: StagedTransaction records z walidacją
├── SAVED:  staged_transactions collection (TTL 24h)
└── TRANSFORMACJA:
    BankCsvRow {                              →     StagedTransaction {
      bankCategory: "Przelewy wychodzące",            originalData: { bankCategory: "..." },
      name: "Lucjan Bik",                             mappedData: {
      amount: 3000, type: OUTFLOW                       categoryName: "Przelewy",
    }   // amount ZAWSZE dodatnie!                      // type określa kierunek                                                   parentCategoryName: null
                                                      },
                                                      validation: { status: VALID }
                                                    }

KROK 5: Import
├── INPUT:  VALID staged transactions
├── OUTPUT: CashChange records w CashFlow
├── SAVED:  cash_flows.cashChanges[], import_jobs, UPDATE ai_csv_transformations.importStatus
└── TRANSFORMACJA:
    StagedTransaction {                       →     CashChange {
      mappedData: {                                   name: "Lucjan Bik",
        categoryName: "Przelewy",                     category: "Przelewy",
        money: 3000 PLN, type: OUTFLOW,               money: 3000 PLN,
        paidDate: 2025-12-31                          type: OUTFLOW, status: CONFIRMED,
      }  // amount ZAWSZE dodatnie!                   paidDate: 2025-12-31                                               paidDate: 2025-12-31
    }                                               }

KROK 6: Attestation
├── INPUT:  confirmedBalance, CashFlow in SETUP
├── OUTPUT: CashFlow in OPEN status
├── SAVED:  UPDATE cash_flows.status, cash_flow_forecast_statement.forecasts.*.status
└── TRANSFORMACJA:
    CashFlow { status: SETUP }                →     CashFlow { status: OPEN }
    forecasts { status: IMPORT_PENDING }      →     forecasts { status: IMPORTED }

KROK 7: Forecast Query
├── INPUT:  CashFlow with imported transactions
├── OUTPUT: CashFlowForecastStatement with category breakdown
├── SAVED:  N/A (read-only)
└── TRANSFORMACJA:
    Individual CashChanges                    →     Aggregated by category + month:
    [                                               forecasts: {
      { category: "Przelewy", -3000 },               "2023-01": {
      { category: "Przelewy", -5000 },                 categorizedOutFlows: [
      { category: "ZUS", -1771 }                         { category: "Przelewy", sum: -8000 },
    ]                                                    { category: "ZUS", sum: -1771 }
                                                       ]
                                                     }
                                                   }
```

---

## Comparison with AI_TRANSACTION_CATEGORIZATION_ANALYSIS.md

### Current Manual Approach vs Proposed AI Approach

| Aspect | Obecny (Manual) | Proponowany (AI) |
|--------|-----------------|------------------|
| **Poziom kategoryzacji** | bankCategory (np. "Przelewy wychodzące") | transactionName (np. "ZUS", "SILVA", "MINDBOX") |
| **Granularność** | Niska - wszystkie przelewy w jednej kategorii | Wysoka - każdy kontrahent ma swoją kategorię |
| **User effort** | Ręczne mapowanie każdej bankCategory | Tylko potwierdzenie sugestii AI |
| **Nested categories** | Możliwe, ale manual | Automatyczne z AI |
| **Koszt** | 0 PLN | ~0.31 gr per 402 transakcje |
| **Czas** | Minuty (user musi myśleć) | Sekundy (AI) |
| **Powtarzalność** | User musi pamiętać mapowania | Cache pamięta wzorce |

### Architecture Gap

```
OBECNY FLOW (bankCategory-based):
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ CSV row:                                                                                │
│   bankCategory: "Przelewy wychodzące"  ─────────────────────────┐                       │
│   name: "ZUS składki"                                           │                       │
│   description: "składki ZUS styczeń"                            │ IGNOROWANE!           │
│                                          ▼                      │                       │
│                                    CategoryMapping              │                       │
│                                    "Przelewy wychodzące" → "Przelewy"                   │
│                                                                                         │
│ PROBLEM: name i description nie są używane do kategoryzacji!                           │
│          Wszystkie przelewy lądują w tej samej kategorii                               │
└─────────────────────────────────────────────────────────────────────────────────────────┘

PROPONOWANY FLOW (transactionName-based):
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ CSV row:                                                                                │
│   bankCategory: "Przelewy wychodzące"  ─────┐                                           │
│   name: "ZUS składki"                  ─────┼───────────────────────┐                   │
│   description: "składki ZUS styczeń"  ──────┘                       │                   │
│                                          ▼                          │                   │
│                                    PatternExtractor                 │                   │
│                                    "ZUS składki" → normalize → "ZUS"│                   │
│                                          ▼                          │                   │
│                                    PatternMappingCache              │                   │
│                                    "ZUS" → "Składki ZUS" (parent: "Opłaty obowiązkowe") │
│                                          │                                              │
│                                          │ CACHE MISS?                                  │
│                                          ▼                                              │
│                                    AI Categorization                                    │
│                                    "ZUS" → { category, parent, confidence }            │
│                                                                                         │
│ REZULTAT: Granularna kategoryzacja oparta na NAZWIE transakcji                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Key Changes Needed for AI Categorization

1. **New Domain Objects:**
   - `TransactionPattern` - znormalizowany wzorzec nazwy transakcji
   - `PatternMapping` - mapowanie wzorzec → kategoria (z cache)
   - `AiCategorizationResult` - wynik kategoryzacji AI

2. **New Services:**
   - `PatternExtractorService` - normalizacja nazw transakcji
   - `PatternMappingCacheService` - cache wzorców (user + global)
   - `AiCategorizationService` - wywołanie AI dla nowych wzorców

3. **Modified Staging Flow:**
   - Po staging, przed import: wywołaj AI kategoryzację
   - Zapisz sugestie AI do `StagedTransaction.aiSuggestion`
   - User potwierdza/modyfikuje sugestie
   - Zapisz potwierdzone mapowania do cache

4. **New Endpoints:**
   - `POST /ai-categorize/{sessionId}` - uruchom AI kategoryzację
   - `POST /accept-suggestions/{sessionId}` - potwierdź sugestie AI
   - `GET /patterns/user` - pobierz wzorce użytkownika

---

## Related Documents

- [AI Transaction Categorization Analysis](./AI_TRANSACTION_CATEGORIZATION_ANALYSIS.md)
- [Bank Data Ingestion Pipeline](../bank-data-ingestion-pipeline.md)
- [Unified CSV Import UI Integration](../UNIFIED_CSV_IMPORT_UI_INTEGRATION.md)
