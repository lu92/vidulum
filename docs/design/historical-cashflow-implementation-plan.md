# Plan implementacji - Ładowanie danych historycznych

## Przegląd

Ten dokument zawiera podział implementacji funkcjonalności ładowania danych historycznych na małe, przyrostowe Pull Requesty.

**Powiązane dokumenty:**
- [historical-cashflow-setup.md](./historical-cashflow-setup.md) - oryginalny design
- [historical-import-user-guide.md](./historical-import-user-guide.md) - user guide z przykładami
- [bank-data-ingestion-pipeline.md](./bank-data-ingestion-pipeline.md) - nowy moduł do importu danych z banków

---

## PR 1: Status SETUP dla CashFlow ✅ DONE
**Branch:** `VID-86`
**Tytuł:** `VID-86: Add SETUP status to CashFlow`

### Zakres:
- [x] Dodaj `SETUP` do enum `CashFlowStatus` - już istniał
- [x] Dodaj `IMPORT_PENDING` do enum `Status` (Forecast) - wcześniej `SETUP_PENDING`
- [x] Walidacje blokujące operacje w trybie SETUP:
  - [x] `appendExpectedCashChange` → błąd w SETUP
  - [x] `appendPaidCashChange` → błąd w SETUP
  - [x] `editCashChange` → błąd w SETUP
  - [x] `confirmCashChange` → błąd w SETUP
- [x] Testy integracyjne (4 testy w CashFlowControllerTest)

### Pliki zmienione:
- `CashFlowMonthlyForecast.java` - dodano IMPORT_PENDING do enum Status
- `OperationNotAllowedInSetupModeException.java` - nowy wyjątek
- `AppendExpectedCashChangeCommandHandler.java` - walidacja SETUP
- `AppendPaidCashChangeCommandHandler.java` - walidacja SETUP
- `EditCashChangeCommandHandler.java` - walidacja SETUP
- `ConfirmCashChangeCommandHandler.java` - walidacja SETUP
- `IntegrationTest.java` - dodano CashFlowMongoRepository
- `CashFlowControllerTest.java` - 4 nowe testy integracyjne

---

## PR 2: CreateCashFlowWithHistory command ✅ DONE
**Branch:** `VID-87`
**Tytuł:** `VID-87: Add createCashFlowWithHistory command`

### Zakres:
- [x] `CreateCashFlowWithHistoryCommand` + Handler
- [x] `CashFlowWithHistoryCreatedEvent`
- [x] Nowe parametry: `startPeriod`, `initialBalance`
- [x] Tworzenie miesięcy IMPORT_PENDING (historycznych) + ACTIVE + FORECASTED
- [x] REST endpoint: `POST /api/v1/cash-flow/with-history`
- [x] Testy integracyjne

### Zależności:
- PR 1 (SETUP status)

---

## PR 3: Import pojedynczej transakcji historycznej ✅ DONE
**Branch:** `VID-88`
**Tytuł:** `VID-88: Add importHistoricalCashChange command`

### Zakres:
- [x] `ImportHistoricalCashChangeCommand` + Handler
- [x] `HistoricalCashChangeImportedEvent`
- [x] Walidacje:
  - [x] Tylko w trybie SETUP (`ImportNotAllowedInNonSetupModeException`)
  - [x] `paidDate` przed `activePeriod` (`ImportDateOutsideSetupPeriodException`)
  - [x] `paidDate` >= `startPeriod` (`ImportDateBeforeStartPeriodException`)
  - [x] `paidDate` <= `now()` (`ImportDateInFutureException`)
- [x] REST endpoint: `POST /api/v1/cash-flow/{id}/import-historical`
- [x] Event handler w Forecast Processor (`HistoricalCashChangeImportedEventHandler`)
- [x] Testy integracyjne (walidacje + forecast updates)

### Zależności:
- PR 2 (createCashFlowWithHistory)

---

## PR 4: Rename SETUP_PENDING → IMPORT_PENDING + Add IMPORTED status ✅ DONE
**Branch:** `VID-89`
**Tytuł:** `VID-89: Rename SETUP_PENDING to IMPORT_PENDING and add IMPORTED status`

### Zakres:
- [x] Zmiana nazwy statusu `SETUP_PENDING` → `IMPORT_PENDING` (miesiące oczekujące na import)
- [x] Dodanie nowego statusu `IMPORTED` (miesiące z zaimportowanymi danymi po aktywacji)
- [x] Aktualizacja wszystkich event handlerów i testów
- [x] Dodanie walidacji `paidDate <= now()` w `ImportHistoricalCashChangeCommandHandler`
- [x] `ImportDateInFutureException` - nowy wyjątek
- [x] Boundary tests dla walidacji cutoff date (8 testów)

### Pliki zmienione:
- `CashFlowMonthlyForecast.java` - zmiana enum Status
- `ImportHistoricalCashChangeCommandHandler.java` - walidacja paidDate <= now
- `ImportDateInFutureException.java` - nowy wyjątek
- `CashFlowWithHistoryCreatedEventHandler.java` - IMPORT_PENDING
- `HistoricalCashChangeImportedEventHandler.java` - IMPORT_PENDING
- `CashFlowControllerTest.java` - nowe testy boundary

### Zależności:
- PR 3 (importHistoricalCashChange)

---

## PR 5: Attestacja historycznego importu ✅ DONE
**Branch:** `VID-90`
**Tytuł:** `VID-90: Attestation of imported historical data`

### Zakres:
- [x] `AttestHistoricalImportCommand` + Handler
- [x] `HistoricalImportAttestedEvent`
- [x] Walidacja balance (calculated vs confirmed)
- [x] Opcje: `forceAttestation`, `createAdjustment`
- [x] Zmiana statusów: SETUP → OPEN, IMPORT_PENDING → IMPORTED
- [x] Ustawienie `importCutoffDateTime` (moment attestacji jako granica importu)
- [x] REST endpoint: `POST /api/v1/cash-flow/{id}/attest-historical-import`
- [x] Testy

### Zależności:
- PR 4 (IMPORT_PENDING status)

---

## PR 6: Rollback importu ✅ DONE
**Branch:** `VID-91`
**Tytuł:** `VID-91: Add rollback of historical import`

### Zakres:
- [x] `RollbackImportCommand` + Handler
- [x] `ImportRolledBackEvent`
- [x] Usuwanie transakcji z IMPORT_PENDING miesięcy
- [x] Opcjonalne usuwanie kategorii
- [x] REST endpoint: `POST /api/v1/cash-flow/{id}/rollback-import`
- [x] Testy

### Zależności:
- PR 5 (attestation) - rollback tylko przed attestacją

---

## PR 7: Fix calculation of flow of money ✅ DONE
**Branch:** `VID-92`
**Tytuł:** `VID-92: Fix calculation of flow of money`

### Zakres:
- [x] Poprawki w kalkulacji przepływów pieniężnych

---

## PR 8: createAdjustment i importCutoffDateTime ✅ DONE
**Branch:** `VID-93`
**Tytuł:** `VID-93: Add createAdjustment and importCutoffDateTime to historical import attestation`

### Zakres:
- [x] Opcja `createAdjustment` przy attestacji - automatyczne tworzenie transakcji korygującej
- [x] Pole `importCutoffDateTime` w CashFlow - granica między danymi historycznymi a nowymi

---

## PR 9: Walidacja transakcji do zarchiwizowanych kategorii ✅ DONE
**Branch:** `VID-94`
**Tytuł:** `VID-94: Add validation to prevent adding transactions to archived categories`

### Zakres:
- [x] Walidacja w `AppendExpectedCashChangeCommandHandler`
- [x] Walidacja w `AppendPaidCashChangeCommandHandler`
- [x] `CategoryIsArchivedException`
- [x] Obsługa wersjonowanych kategorii (findActiveCategory, findArchivedCategory)

---

## PR 10: Archiwizacja subkategorii (forceArchiveChildren) ✅ DONE
**Branch:** `VID-95`
**Tytuł:** `VID-95: Making subcategories archived as well`

### Zakres:
- [x] Parametr `forceArchiveChildren` w `ArchiveCategoryCommand`
- [x] Rekurencyjna archiwizacja subkategorii gdy `forceArchiveChildren=true`
- [x] Zachowanie aktywnych subkategorii gdy `forceArchiveChildren=false`
- [x] Testy integracyjne

---

## PR 11: Category validity period (validFrom/validTo) ✅ DONE
**Wcześniejsze PR-y**
**Tytuł:** `Category origin, validFrom, validTo, archived fields`

### Zakres:
- [x] Rozszerzenie `Category` o `validFrom`, `validTo`, `archived`, `origin`
- [x] `CategoryOrigin` enum: SYSTEM, IMPORTED, USER_CREATED
- [x] Archive/Unarchive commands
- [x] Event handlers w ForecastProcessor

---

# NOWY MODUŁ: Bank Data Ingestion Pipeline

Poniższe PR-y dotyczą nowego modułu `bank_data_ingestion` opisanego w [bank-data-ingestion-pipeline.md](./bank-data-ingestion-pipeline.md).

---

## PR 12: Category Mappings - podstawa ⏳ NEXT
**Branch:** `VID-96`
**Tytuł:** `VID-96: Add category mappings collection and API`

### Zakres:
- [ ] Nowy moduł `bank_data_ingestion`
- [ ] `CategoryMapping` domain object
- [ ] `CategoryMappingEntity` + `CategoryMappingMongoRepository`
- [ ] `MappingAction` enum: CREATE_NEW, CREATE_SUBCATEGORY, MAP_TO_EXISTING, MAP_TO_UNCATEGORIZED
- [ ] CRUD dla mappings:
  - [ ] `ConfigureCategoryMappingCommand` + Handler
  - [ ] `DeleteCategoryMappingCommand` + Handler
  - [ ] `GetCategoryMappingsQuery` + Handler
- [ ] REST endpoints:
  - [ ] `POST /api/v1/cash-flow/{id}/ingestion/mappings`
  - [ ] `GET /api/v1/cash-flow/{id}/ingestion/mappings`
  - [ ] `DELETE /api/v1/cash-flow/{id}/ingestion/mappings/{mappingId}`
  - [ ] `DELETE /api/v1/cash-flow/{id}/ingestion/mappings`
- [ ] Testy

### Zależności:
- Brak (pierwszy PR nowego modułu)

---

## PR 13: Staged Transactions - staging z preview
**Branch:** `VID-97`
**Tytuł:** `VID-97: Add staged transactions collection with preview`

### Zakres:
- [ ] `StagedTransaction` domain object
- [ ] `StagedTransactionEntity` + `StagedTransactionMongoRepository`
- [ ] TTL index (konfigurowalne, default 24h)
- [ ] `StageTransactionsCommand` + Handler:
  - [ ] Walidacja transakcji
  - [ ] Aplikowanie mappings
  - [ ] Detekcja duplikatów
  - [ ] Generowanie preview summary
- [ ] `GetStagingPreviewQuery` + Handler
- [ ] `DeleteStagingSessionCommand` + Handler
- [ ] REST endpoints:
  - [ ] `POST /api/v1/cash-flow/{id}/ingestion/stage`
  - [ ] `GET /api/v1/cash-flow/{id}/ingestion/stage/{sessionId}`
  - [ ] `DELETE /api/v1/cash-flow/{id}/ingestion/stage/{sessionId}`
- [ ] Testy

### Zależności:
- PR 12 (category mappings)

---

## PR 14: Import Jobs - import z progress tracking
**Branch:** `VID-98`
**Tytuł:** `VID-98: Add import jobs with progress tracking`

### Zakres:
- [ ] `ImportJob` aggregate
- [ ] `ImportJobEntity` + `ImportJobMongoRepository`
- [ ] `ImportJobStatus` enum: PENDING, PROCESSING, COMPLETED, FAILED, ROLLED_BACK, FINALIZED
- [ ] `ImportPhase` enum: CREATING_CATEGORIES, IMPORTING_TRANSACTIONS
- [ ] `StartImportJobCommand` + Handler:
  - [ ] Phase 1: Create missing categories
  - [ ] Phase 2: Import transactions (używa istniejącego ImportHistoricalCashChangeCommand)
  - [ ] Progress update co N transakcji
- [ ] `GetImportProgressQuery` + Handler
- [ ] REST endpoints:
  - [ ] `POST /api/v1/cash-flow/{id}/ingestion/import`
  - [ ] `GET /api/v1/cash-flow/{id}/ingestion/import/{jobId}`
  - [ ] `GET /api/v1/cash-flow/{id}/ingestion/import` (list)
- [ ] Testy

### Zależności:
- PR 13 (staged transactions)

---

## PR 15: Import Rollback i Finalize
**Branch:** `VID-99`
**Tytuł:** `VID-99: Add import rollback and finalize`

### Zakres:
- [ ] `RollbackImportJobCommand` + Handler:
  - [ ] Usuwanie zaimportowanych cashChanges
  - [ ] Usuwanie utworzonych kategorii (jeśli bez innych transakcji)
  - [ ] Walidacja: tylko przed attestacją CashFlow
- [ ] `FinalizeImportJobCommand` + Handler:
  - [ ] Usuwanie staged_transactions
  - [ ] Opcjonalne usuwanie mappings
  - [ ] Oznaczenie job jako FINALIZED
- [ ] REST endpoints:
  - [ ] `POST /api/v1/cash-flow/{id}/ingestion/import/{jobId}/rollback`
  - [ ] `POST /api/v1/cash-flow/{id}/ingestion/import/{jobId}/finalize`
- [ ] Testy

### Zależności:
- PR 14 (import jobs)

---

## Przyszłe PR-y (opcjonalne)

| Branch | Tytuł | Opis |
|--------|-------|------|
| VID-100 | CSV Parser dla banków | Parsery dla ING, mBank, PKO |
| VID-101 | Bank API integration (Nordigen/PSD2) | Integracja z API banków |
| VID-102 | AI kategoryzacja transakcji | Automatyczna kategoryzacja przez LLM |
| VID-103 | Dedicated HTTP exception handlers | Lepsze kody HTTP (409, 422) |

---

## Diagram zależności

```
ZAIMPLEMENTOWANE:
──────────────────
PR1 (SETUP status) ✅
  └─> PR2 (createCashFlowWithHistory) ✅
        └─> PR3 (importHistoricalCashChange) ✅
              └─> PR4 (IMPORT_PENDING + IMPORTED) ✅
                    ├─> PR5 (attestation) ✅
                    └─> PR6 (rollback) ✅

PR7-PR11 (kategorie: validFrom/To, archived, forceArchiveChildren) ✅


DO ZAIMPLEMENTOWANIA (Bank Data Ingestion Pipeline):
────────────────────────────────────────────────────
PR12 (category mappings) ⏳ NEXT
  └─> PR13 (staged transactions)
        └─> PR14 (import jobs)
              └─> PR15 (rollback & finalize)

PRZYSZŁE (opcjonalne):
──────────────────────
PR16+ (CSV parser, Bank API, AI categorization)
```

---

## Sugerowana kolejność implementacji

### Zaimplementowane:
1. **PR1** - fundament (SETUP status) ✅
2. **PR2** - tworzenie CashFlow z historią ✅
3. **PR3** - import pojedynczy ✅
4. **PR4** - IMPORT_PENDING + IMPORTED status ✅
5. **PR5** - attestacja CashFlow ✅
6. **PR6** - rollback importu ✅
7. **PR7-PR11** - kategorie (archived, validFrom/To, forceArchiveChildren) ✅

### Do zaimplementowania:
8. **PR12** - category mappings (nowy moduł `bank_data_ingestion`) ⏳ NEXT
9. **PR13** - staged transactions z preview
10. **PR14** - import jobs z progress tracking
11. **PR15** - rollback & finalize importu

### Przyszłe:
12. CSV Parser dla banków
13. Bank API integration (Nordigen/PSD2)
14. AI kategoryzacja

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2026-01-06 | Utworzenie dokumentu z podziałem na PR-y |
| 2026-01-06 | Aktualizacja: PR1-PR4 ukończone |
| 2026-01-06 | Zamiana kolejności: PR5 aktywacja, PR6 rollback |
| 2026-01-07 | Aktualizacja: PR5-PR11 ukończone (attestation, rollback, categories) |
| 2026-01-07 | Dodanie nowego modułu Bank Data Ingestion Pipeline (PR12-PR15) |
| 2026-01-07 | Link do dokumentacji: bank-data-ingestion-pipeline.md |
