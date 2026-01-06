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
- [x] Dodaj `SETUP_PENDING` do enum `Status` (Forecast)
- [x] Walidacje blokujące operacje w trybie SETUP:
  - [x] `appendExpectedCashChange` → błąd w SETUP
  - [x] `appendPaidCashChange` → błąd w SETUP
  - [x] `editCashChange` → błąd w SETUP
  - [x] `confirmCashChange` → błąd w SETUP
- [x] Testy integracyjne (4 testy w CashFlowControllerTest)

### Pliki zmienione:
- `CashFlowMonthlyForecast.java` - dodano SETUP_PENDING do enum Status
- `OperationNotAllowedInSetupModeException.java` - nowy wyjątek
- `AppendExpectedCashChangeCommandHandler.java` - walidacja SETUP
- `AppendPaidCashChangeCommandHandler.java` - walidacja SETUP
- `EditCashChangeCommandHandler.java` - walidacja SETUP
- `ConfirmCashChangeCommandHandler.java` - walidacja SETUP
- `IntegrationTest.java` - dodano CashFlowMongoRepository
- `CashFlowControllerTest.java` - 4 nowe testy integracyjne

---

## PR 2: CreateCashFlowWithHistory command
**Branch:** `VID-87`
**Tytuł:** `VID-87: Add createCashFlowWithHistory command`

### Zakres:
- [ ] `CreateCashFlowWithHistoryCommand` + Handler
- [ ] `CashFlowWithHistoryCreatedEvent`
- [ ] Nowe parametry: `startDate`, `initialBalance`, `currentBalance`
- [ ] Tworzenie miesięcy SETUP_PENDING (historycznych) + ACTIVE + FORECASTED
- [ ] REST endpoint: `POST /api/v1/cashflows/with-history`
- [ ] Testy

### Zależności:
- PR 1 (SETUP status)

---

## PR 3: Import pojedynczej transakcji historycznej
**Branch:** `VID-88`
**Tytuł:** `VID-88: Add importHistoricalCashChange command`

### Zakres:
- [ ] `ImportHistoricalCashChangeCommand` + Handler
- [ ] `HistoricalCashChangeImportedEvent`
- [ ] Walidacje:
  - [ ] Tylko w trybie SETUP
  - [ ] Tylko do SETUP_PENDING miesięcy
  - [ ] Kategoria musi istnieć
- [ ] REST endpoint: `POST /api/v1/cashflows/{id}/import-historical`
- [ ] Event handler w Forecast Processor
- [ ] Testy

### Zależności:
- PR 2 (createCashFlowWithHistory)

---

## PR 4: Rollback importu
**Branch:** `VID-89`
**Tytuł:** `VID-89: Add rollbackImport command`

### Zakres:
- [ ] `RollbackImportCommand` + Handler
- [ ] `ImportRolledBackEvent`
- [ ] Usuwanie transakcji z SETUP_PENDING miesięcy
- [ ] Opcjonalne usuwanie kategorii
- [ ] REST endpoint: `POST /api/v1/cashflows/{id}/rollback-import`
- [ ] Testy

### Zależności:
- PR 3 (importHistoricalCashChange)

---

## PR 5: Aktywacja CashFlow
**Branch:** `VID-90`
**Tytuł:** `VID-90: Add activateCashFlow command`

### Zakres:
- [ ] `ActivateCashFlowCommand` + Handler
- [ ] `CashFlowActivatedEvent`
- [ ] Walidacja balance (calculated vs confirmed)
- [ ] Opcje: `forceActivation`, `createAdjustment`
- [ ] Zmiana statusów: SETUP → OPEN, SETUP_PENDING → ATTESTED
- [ ] REST endpoint: `POST /api/v1/cashflows/{id}/activate`
- [ ] Testy

### Zależności:
- PR 2 (createCashFlowWithHistory)

---

## PR 6: Kategorie z okresem ważności
**Branch:** `VID-91`
**Tytuł:** `VID-91: Add category validity period (validFrom/validTo)`

### Zakres:
- [ ] Rozszerzenie `Category` o `validFrom`, `validTo`, `archived`, `origin`
- [ ] `CategoryOrigin` enum: SYSTEM, IMPORTED, USER_CREATED
- [ ] Walidacja przy wyborze kategorii (czy aktywna w danej dacie)
- [ ] Migracja istniejących kategorii
- [ ] Testy

### Zależności:
- Brak (niezależny)

---

## PR 7: Konfiguracja mapowania kategorii
**Branch:** `VID-92`
**Tytuł:** `VID-92: Add configureCategoryMapping command`

### Zakres:
- [ ] `ConfigureCategoryMappingCommand` + Handler
- [ ] `CategoryMappingConfiguredEvent`
- [ ] `CategoryFromMappingCreatedEvent`
- [ ] `MappingAction` enum: CREATE_NEW, CREATE_SUBCATEGORY, MAP_TO_UNCATEGORIZED
- [ ] REST endpoint: `POST /api/v1/cashflows/{id}/category-mapping`
- [ ] Testy

### Zależności:
- PR 6 (kategorie z validFrom/validTo)

---

## PR 8: Batch import transakcji
**Branch:** `VID-93`
**Tytuł:** `VID-93: Add batch import for historical transactions`

### Zakres:
- [ ] `BatchImportHistoricalCashChangesCommand` + Handler
- [ ] Response: `BatchImportResultJson` (success/failed/duplicates)
- [ ] Deduplikacja po `bankTransactionId`
- [ ] REST endpoint: `POST /api/v1/cashflows/{id}/import-historical/batch`
- [ ] Testy

### Zależności:
- PR 3 (importHistoricalCashChange)
- PR 7 (configureCategoryMapping)

---

## PR 9: CSV Parser dla banków
**Branch:** `VID-94`
**Tytuł:** `VID-94: Add CSV parser for bank statements`

### Zakres:
- [ ] Interfejs `BankStatementParser`
- [ ] Implementacje: `IngCsvParser`, `MBankCsvParser`, `PkoCsvParser`
- [ ] `BankDataParseResultJson` - lista transakcji + kategorii
- [ ] REST endpoint: `POST /api/v1/cashflows/{id}/parse-bank-data`
- [ ] Testy z przykładowymi plikami CSV

### Zależności:
- PR 8 (batch import)

---

## PR 10: Dedykowane handlery HTTP dla wyjątków CashFlow
**Branch:** `VID-99`
**Tytuł:** `VID-99: Add dedicated HTTP exception handlers for CashFlow exceptions`

### Zakres:
- [ ] Dedykowany handler dla `OperationNotAllowedInSetupModeException`
- [ ] Dedykowany handler dla `PaidDateInFutureException`
- [ ] Dedykowany handler dla `PaidDateNotInActivePeriodException`
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
| VID-95 | Korekty w miesiącach ATTESTED | Dodawanie korekt do zamkniętych miesięcy |
| VID-96 | Progress tracking dla batch import | Śledzenie postępu importu |
| VID-97 | Nordigen (PSD2) integration | Integracja z API banków przez PSD2 |
| VID-98 | AI kategoryzacja transakcji | Automatyczna kategoryzacja przez LLM |

---

## Diagram zależności

```
PR1 (SETUP status)
  └─> PR2 (createCashFlowWithHistory)
        ├─> PR3 (importHistoricalCashChange)
        │     └─> PR4 (rollbackImport)
        └─> PR5 (activateCashFlow)

PR6 (kategorie z validFrom/To) - niezależny
  └─> PR7 (configureCategoryMapping)

PR3 + PR7
  └─> PR8 (batch import)
        └─> PR9 (CSV parser)
```

---

## Sugerowana kolejność implementacji

1. **PR1** - fundament (SETUP status)
2. **PR2** - tworzenie CashFlow z historią
3. **PR6** - kategorie (równolegle z PR2, niezależny)
4. **PR3** - import pojedynczy
5. **PR7** - mapowanie kategorii
6. **PR5** - aktywacja
7. **PR4** - rollback
8. **PR8** - batch import
9. **PR9** - CSV parser

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2026-01-06 | Utworzenie dokumentu z podziałem na PR-y |
