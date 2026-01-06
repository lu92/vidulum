# Plan implementacji - Ładowanie danych historycznych

## Przegląd

Ten dokument zawiera podział implementacji funkcjonalności ładowania danych historycznych na małe, przyrostowe Pull Requesty.

Bazuje na: [historical-cashflow-setup.md](./historical-cashflow-setup.md)

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

## PR 5: Rollback importu
**Branch:** `VID-90`
**Tytuł:** `VID-90: Add rollbackImport command`

### Zakres:
- [ ] `RollbackImportCommand` + Handler
- [ ] `ImportRolledBackEvent`
- [ ] Usuwanie transakcji z IMPORT_PENDING miesięcy
- [ ] Opcjonalne usuwanie kategorii
- [ ] REST endpoint: `POST /api/v1/cash-flow/{id}/rollback-import`
- [ ] Testy

### Zależności:
- PR 4 (IMPORT_PENDING status)

---

## PR 6: Aktywacja CashFlow
**Branch:** `VID-91`
**Tytuł:** `VID-91: Add activateCashFlow command`

### Zakres:
- [ ] `ActivateCashFlowCommand` + Handler
- [ ] `CashFlowActivatedEvent`
- [ ] Walidacja balance (calculated vs confirmed)
- [ ] Opcje: `forceActivation`, `createAdjustment`
- [ ] Zmiana statusów: SETUP → OPEN, IMPORT_PENDING → IMPORTED
- [ ] Ustawienie `importCutoffDateTime = activatedAt` (moment aktywacji jako granica importu)
- [ ] REST endpoint: `POST /api/v1/cash-flow/{id}/activate`
- [ ] Testy

### Zależności:
- PR 4 (IMPORT_PENDING status)

---

## PR 7: Kategorie z okresem ważności
**Branch:** `VID-92`
**Tytuł:** `VID-92: Add category validity period (validFrom/validTo)`

### Zakres:
- [ ] Rozszerzenie `Category` o `validFrom`, `validTo`, `archived`, `origin`
- [ ] `CategoryOrigin` enum: SYSTEM, IMPORTED, USER_CREATED
- [ ] Walidacja przy wyborze kategorii (czy aktywna w danej dacie)
- [ ] Migracja istniejących kategorii
- [ ] Testy

### Zależności:
- Brak (niezależny)

---

## PR 8: Konfiguracja mapowania kategorii
**Branch:** `VID-93`
**Tytuł:** `VID-93: Add configureCategoryMapping command`

### Zakres:
- [ ] `ConfigureCategoryMappingCommand` + Handler
- [ ] `CategoryMappingConfiguredEvent`
- [ ] `CategoryFromMappingCreatedEvent`
- [ ] `MappingAction` enum: CREATE_NEW, CREATE_SUBCATEGORY, MAP_TO_UNCATEGORIZED
- [ ] REST endpoint: `POST /api/v1/cash-flow/{id}/category-mapping`
- [ ] Testy

### Zależności:
- PR 7 (kategorie z validFrom/validTo)

---

## PR 9: Batch import transakcji
**Branch:** `VID-94`
**Tytuł:** `VID-94: Add batch import for historical transactions`

### Zakres:
- [ ] `BatchImportHistoricalCashChangesCommand` + Handler
- [ ] Response: `BatchImportResultJson` (success/failed/duplicates)
- [ ] Deduplikacja po `bankTransactionId`
- [ ] REST endpoint: `POST /api/v1/cash-flow/{id}/import-historical/batch`
- [ ] Testy

### Zależności:
- PR 3 (importHistoricalCashChange)
- PR 8 (configureCategoryMapping)

---

## PR 10: CSV Parser dla banków
**Branch:** `VID-95`
**Tytuł:** `VID-95: Add CSV parser for bank statements`

### Zakres:
- [ ] Interfejs `BankStatementParser`
- [ ] Implementacje: `IngCsvParser`, `MBankCsvParser`, `PkoCsvParser`
- [ ] `BankDataParseResultJson` - lista transakcji + kategorii
- [ ] REST endpoint: `POST /api/v1/cash-flow/{id}/parse-bank-data`
- [ ] Testy z przykładowymi plikami CSV

### Zależności:
- PR 9 (batch import)

---

## PR 11: Dedykowane handlery HTTP dla wyjątków CashFlow
**Branch:** `VID-99`
**Tytuł:** `VID-99: Add dedicated HTTP exception handlers for CashFlow exceptions`

### Zakres:
- [ ] Dedykowany handler dla `OperationNotAllowedInSetupModeException`
- [ ] Dedykowany handler dla `ImportDateInFutureException`
- [ ] Dedykowany handler dla `ImportDateOutsideSetupPeriodException`
- [ ] Dedykowany handler dla `ImportDateBeforeStartPeriodException`
- [ ] Określenie odpowiednich HTTP status codes (np. 409 CONFLICT, 422 UNPROCESSABLE_ENTITY)
- [ ] Ujednolicony format odpowiedzi błędów
- [ ] Testy

### Zależności:
- Brak (niezależny)

### Uwagi:
- Obecnie wszystkie wyjątki zwracają 400 BAD_REQUEST przez generyczny handler
- RFC 7807 Problem Details format jest już używany

---

## Przyszłe PR-y (opcjonalne)

| Branch | Tytuł | Opis |
|--------|-------|------|
| VID-96 | Korekty w miesiącach ATTESTED/IMPORTED | Dodawanie korekt do zamkniętych miesięcy |
| VID-97 | Progress tracking dla batch import | Śledzenie postępu importu |
| VID-98 | Nordigen (PSD2) integration | Integracja z API banków przez PSD2 |
| VID-100 | AI kategoryzacja transakcji | Automatyczna kategoryzacja przez LLM |

---

## Diagram zależności

```
PR1 (SETUP status) ✅
  └─> PR2 (createCashFlowWithHistory) ✅
        ├─> PR3 (importHistoricalCashChange) ✅
        │     └─> PR4 (IMPORT_PENDING + IMPORTED) ✅
        │           └─> PR5 (rollbackImport)
        └─> PR6 (activateCashFlow)

PR7 (kategorie z validFrom/To) - niezależny
  └─> PR8 (configureCategoryMapping)

PR3 + PR8
  └─> PR9 (batch import)
        └─> PR10 (CSV parser)
```

---

## Sugerowana kolejność implementacji

1. **PR1** - fundament (SETUP status) ✅
2. **PR2** - tworzenie CashFlow z historią ✅
3. **PR3** - import pojedynczy ✅
4. **PR4** - IMPORT_PENDING + IMPORTED status ✅
5. **PR5** - rollback importu
6. **PR6** - aktywacja CashFlow
7. **PR7** - kategorie z validFrom/validTo (niezależny)
8. **PR8** - mapowanie kategorii
9. **PR9** - batch import
10. **PR10** - CSV parser

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2026-01-06 | Utworzenie dokumentu z podziałem na PR-y |
| 2026-01-06 | Aktualizacja: PR1-PR4 ukończone, zmiana SETUP_PENDING → IMPORT_PENDING, dodanie IMPORTED, walidacja paidDate <= now() |
