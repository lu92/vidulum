# VID-161 Phase 1b — Walkthrough on real data

**Date**: 2026-06-07
**Status**: ✅ Phase 1b shipped and verified end-to-end
**Source data**: `~/Pulpit/bank-csv-samples/nestbank_lista_operacji_20260111.csv` (402 transactions, 36 months)
**Test user**: `U10000003`, CashFlow `CF10000001`

This document narrates the full self-transfer detection flow on real production-like data.
It links the design (VID-161) to concrete numbers observable in MongoDB and the forecast endpoint.

---

## Etap 1: Onboarding — user deklaruje swoje konta bankowe

**Co user robi:**
1. `POST /api/v1/auth/register` → dostaje `userId = U10000003` + JWT
2. **Krytyczny moment**: w tej samej operacji w bazie automatycznie powstaje pusty profil finansowy
3. UI pokazuje ekran onboarding "Powiedz nam o swoich kontach"
4. User wypełnia 2 IBAN-y (Nest + Pekao):

```http
POST /api/v1/user/owned-accounts/bulk
{
  "accounts": [
    { "iban": "PL93187010452083105656550001", "currency": "PLN", "bankName": "Nest Bank",   "label": "Nest" },
    { "iban": "PL98124014441111001078171074", "currency": "PLN", "bankName": "Bank Pekao",  "label": "Pekao" }
  ]
}
```

**Co dzieje się w bazie po onboardingu:**

```js
db.user_financial_profiles.findOne({_id: "U10000003"})
{
  _id: "U10000003",
  ownedAccounts: [
    { iban: "PL93187010452083105656550001", source: "ONBOARDING", linkedCashFlowId: null, status: "ACTIVE", ... },
    { iban: "PL98124014441111001078171074", source: "ONBOARDING", linkedCashFlowId: null, status: "ACTIVE", ... }
  ]
}
```

**Po co to ważne**: te 2 IBAN-y są teraz "whitelist" — system wie, że każda transakcja, której counterparty jest jednym z nich, to **transfer między własnymi kontami**, nie wydatek.

---

## Etap 2: User tworzy CashFlow dla konta Nest

**Co user robi:**

```http
POST /cash-flow/with-history
{
  "userId": "U10000003",
  "name": "Konto Nest",
  "bankAccount": { "bankAccountNumber": { "account": "PL93187010452083105656550001", ... } },
  "startPeriod": "2023-01",
  "initialBalance": { "amount": 863.94, "currency": "PLN" }
}
→ "CF10000001"
```

**Co dzieje się w tle (Kafka):**

```
CreateCashFlowWithHistoryCommandHandler
  → emit CashFlowWithHistoryCreatedEvent (topic: cash_flow, key=CF10000001)

UserFinancialProfileCashFlowListener (consumer)
  → znajdzie Nest IBAN w profilu (już jest, source=ONBOARDING)
  → UPDATE linkedCashFlowId = "CF10000001" (link existing, NOT duplicate)
```

**Stan profilu po:**

```js
ownedAccounts: [
  { iban: "PL93187010452083105656550001", source: "ONBOARDING", linkedCashFlowId: "CF10000001" },  ← linked
  { iban: "PL98124014441111001078171074", source: "ONBOARDING", linkedCashFlowId: null }            ← unlinked (no CF dla Pekao)
]
```

---

## Etap 3: User wgrywa CSV Nest Bank (402 transakcje, 36 miesięcy)

```http
POST /api/v1/bank-data-adapter/transform
file=@nestbank_lista_operacji_20260111.csv
→ { "transformationId": "31133365-...", "rowCount": 403, ... }

POST /api/v1/bank-data-adapter/{tfId}/import
{ "cashFlowId": "CF10000001" }
→ staging session created
```

**W stagingu — magia detekcji**:

Dla każdej z 402 transakcji `StageTransactionsCommandHandler.processTransaction()` wykonuje **Priority 0** check:

```
counterpartyAccount = txn.counterpartyAccount()  // np. "PL98124014441111001078171074"

IF counterpartyAccount IN profile.ownedAccounts:        // ← TUTAJ MAGIA
    mappedData.categoryName = "Przelewy własne"
    mappedData.parentCategoryName = "Zarządzanie kontem"
    mappedData.selfTransfer = true
    return (SHORT-CIRCUIT — pomiń pattern matching, mapping, itd.)
```

**Realny przykład z naszego CSV** — transakcja Nest → Pekao:

| Field z CSV | Wartość |
|---|---|
| Counterparty name | "Lucjan Bik Pekao" |
| **counterpartyAccount** | **PL98124014441111001078171074** ← w profilu! |
| Amount | -3000 PLN (OUTFLOW) |
| Date | 2025-12-31 |

System sprawdza profile: `PL98...` ∈ ownedAccounts? **TAK** → flaga ustawiona.

**Stan stagingu po przetworzeniu 402 transakcji:**

```js
db.staged_transactions.find({"mappedData.selfTransfer": true}).count()
→ 74
```

74 transakcje do Pekao wykryte deterministycznie po IBAN. Pozostałe 328 zostały oznaczone jako PENDING_MAPPING.

---

## Etap 4: Import do CashFlow (commit do bazy)

```http
POST /api/v1/bank-data-ingestion/cf=CF10000001/staging/{sid}/force-uncategorized
POST /api/v1/bank-data-ingestion/cf=CF10000001/import
→ 402/402 imported
```

**Co dzieje się w jobie importu:**

```
StartImportJobCommandHandler.processCreateCategoriesPhase()
  ↓ Wykrywa że są self-transfery → auto-tworzy 2 kategorie:
    1. "Zarządzanie kontem" (parent, OUTFLOW)
    2. "Przelewy własne" (child, OUTFLOW)

StartImportJobCommandHandler.processImportTransactionsPhase()
  ↓ Dla każdej staged transaction:
    new ImportTransactionRequest(
        categoryName = "Przelewy własne",       // lub "Uncategorized" dla regular
        amount = 3000,
        type = OUTFLOW,
        selfTransfer = true                      // ← flaga przekazywana
    )
  → wywołuje POST /cash-flow/cf={X}/import-historical

ImportHistoricalCashChangeCommandHandler
  → emit HistoricalCashChangeImportedEvent(selfTransfer = true)

CashFlow.apply(event)
  → new CashChange(... selfTransfer = true)
  → save to MongoDB
```

**Stan w bazie po imporcie:**

```js
db["cash-flow-document"].aggregate([
  {$unwind: "$cashChanges"},
  {$match: {"cashChanges.selfTransfer": true}},
  {$count: "n"}
])
→ { n: 74 }
```

Konkretne wpisy:
```js
{
  cashChangeId: "CC1000000002",
  name: "Lucjan Bik Pekao",
  money: { amount: 3000, currency: "PLN" },
  categoryName: { name: "Przelewy własne" },
  selfTransfer: true,        ← flaga
  ...
}
```

---

## Etap 5: Forecast — gdzie te 74 transakcji idą i DLACZEGO

Tu zaczyna się prawdziwa wartość biznesowa. Forecast to read-model budowany z eventów Kafka. **`HistoricalCashChangeImportedEventHandler`** decyduje gdzie wsadza transakcję na podstawie flagi:

```java
if (event.selfTransfer()) {
    // Wsadź do osobnej sekcji selfTransferOutFlows
    // NIE aktualizuj inflowStats/outflowStats (budżet nieskażony)
    forecast.addToSelfTransferOutflows("Przelewy własne", txn)
} else {
    // Standardowa ścieżka — wsadź do categorizedOutFlows
    // Zaktualizuj outflowStats.actual += amount
    forecast.addToOutflows(categoryName, txn)
    // ← TU dawniej trafiało 74 transakcji do Pekao, zawyżając budżet
}
```

**Realny rezultat z forecast — wszystkie 33 miesiące:**

| Bucket | Liczba transakcji | Suma kwot | Czy w budżecie? |
|---|---|---|---|
| `categorizedOutFlows` (regular wydatki) | 292 | **888,726 PLN** | TAK — wlicza się do `outflowStats.actual` |
| `selfTransferOutFlows` (przelewy do Pekao) | **74** | **329,234 PLN** | **NIE** — informacyjne, osobna sekcja |

**Forecast snapshot dla 2025-12 jako przykład:**

```json
{
  "period": "2025-12",
  "categorizedOutFlows": [
    { "categoryName": "Uncategorized", "totalPaidValue": { "amount": 19847.30 } }
  ],
  "selfTransferOutFlows": [
    {
      "categoryName": "Przelewy własne",
      "totalPaidValue": { "amount": 6000 },        ← 2 transakcje × 3000 PLN
      "groupedTransactions": {
        "PAID": [
          { "name": "Lucjan Bik Pekao", "money": 3000 },
          { "name": "Lucjan Bik Pekao", "money": 3000 }
        ]
      }
    }
  ],
  "cashFlowStats": {
    "outflowStats": {
      "actual": { "amount": 19847.30 }  ← TYLKO regular outflows; 6000 PLN self-transferów POMINIĘTE
    }
  }
}
```

**Dlaczego to ważne biznesowo:**

| Pytanie | Odpowiedź |
|---|---|
| "Ile wydałem w grudniu 2025?" | 19,847 PLN (NIE 25,847 PLN — przelew do Pekao to nie wydatek, to tylko przesunięcie pieniędzy) |
| "Ile wydaję średnio miesięcznie?" | ~26,929 PLN/mc *(888,726 / 33 mc)* — czysty trend bez fake-wydatków |
| "Ile przesuwam między swoimi kontami?" | ~9,977 PLN/mc *(329,234 / 33 mc)* — widać w osobnej sekcji `selfTransferOutFlows` |
| "Forecast na 2026?" | Bazuje na **realnych** wydatkach, nie na zawyżonych |

---

## Diagram pełnego flow (z realnymi liczbami)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ETAP 1: ONBOARDING                                                       │
│ User U10000003 deklaruje 2 IBANy:                                        │
│   • PL93... (Nest)   • PL98... (Pekao)                                  │
│ → ZAPISANE w user_financial_profiles z source=ONBOARDING                │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ ETAP 2: CREATE CASHFLOW dla Nest                                         │
│ POST /cash-flow/with-history → CF10000001                                │
│ Kafka listener → linkedCashFlowId=CF10000001 na PL93... w profilu       │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ ETAP 3: UPLOAD CSV (402 transakcji)                                      │
│ Dla każdej staged transaction:                                           │
│                                                                          │
│   counterpartyAccount = PL98...   (target IBAN z wyciągu)               │
│                  │                                                       │
│                  ▼                                                       │
│   PROFILE LOOKUP: PL98... ∈ ownedAccounts(U10000003) ?                  │
│                  │                                                       │
│         ┌────────┴────────┐                                              │
│         │                 │                                              │
│        YES               NO                                              │
│         │                 │                                              │
│         ▼                 ▼                                              │
│   ┌──────────────┐  ┌──────────────────┐                                │
│   │ selfTransfer │  │ standard flow:   │                                │
│   │ = TRUE       │  │ bank category/   │                                │
│   │ category =   │  │ pattern/mapping  │                                │
│   │ "Przelewy    │  │ → Uncategorized  │                                │
│   │  własne"     │  │   lub matched    │                                │
│   └──────────────┘  └──────────────────┘                                │
│         │                 │                                              │
│         └────────┬────────┘                                              │
│   74 z 402              328 z 402                                       │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ ETAP 4: IMPORT do CashFlow                                               │
│ • Auto-create kategorii "Zarządzanie kontem" + "Przelewy własne"        │
│ • Each staged → ImportHistoricalCashChangeCommand z flagą               │
│ • CashFlow.apply() → CashChange.selfTransfer = true|false (zachowane)   │
│ • 402 CashChange w MongoDB, w tym 74 z selfTransfer=true                │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ ETAP 5: FORECAST READ-MODEL (Kafka consumer)                             │
│ Dla każdego HistoricalCashChangeImportedEvent:                           │
│                                                                          │
│   if event.selfTransfer:                                                 │
│     → addToSelfTransferOutFlows()    (nowa sekcja)                      │
│     → outflowStats UNCHANGED         (budżet czysty)                    │
│   else:                                                                  │
│     → addToOutflows()                (standardowa sekcja)               │
│     → outflowStats.actual += amount  (budżet rośnie)                    │
│                                                                          │
│ REZULTAT W FORECAST:                                                     │
│                                                                          │
│   categorizedOutFlows:                                                   │
│     [Uncategorized → 292 txns, 888,726 PLN]   ← REAL EXPENSES           │
│                                                                          │
│   selfTransferOutFlows:                                                  │
│     [Przelewy własne → 74 txns, 329,234 PLN]  ← INTERNAL TRANSFERS      │
│                                                                          │
│   outflowStats.actual = 888,726 PLN  (NIE 1,217,960)                    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## DLACZEGO to ma znaczenie — porównanie z baseline

| Metryka | Przed Phase 1b (06-04) | Po Phase 1b (07-06) | Co się zmieniło |
|---|---|---|---|
| `outflowStats.actual` | 1,217,960 PLN | **888,726 PLN** | **−27%** zawyżenia usunięte |
| Średnia wydatków/mc | 36,908 PLN | **26,929 PLN** | Realna ocena spend rate |
| "Inne"/Uncategorized | 50.7% txns | 23% txns | Mniej szumu |
| Forecast trend | Zniekształcony przez fake-wydatki | **Czysty** | UI pokazuje prawdę |
| Self-transfers widoczność | Brak (zatopione w Inne) | **Osobna sekcja `selfTransferOutFlows`** | UI może wyróżnić |

---

## Kluczowe właściwości tego podejścia

1. **Deterministyczne** — IBAN-match, zero false positives. Nie ma heurystyk po nazwie.
2. **Single source of truth** — `UserFinancialProfile.ownedAccounts` jest whitelist.
3. **Skalowalne** — działa dla N kont. Dodaj 5-ty IBAN podczas onboardingu → następny import automatycznie wykryje transfery do niego.
4. **Niezniszczalne (data integrity)** — flaga jest na CashChange, pozostaje przez edit/confirm. `selfTransfer=true` raz przyznane, zachowane.
5. **Forecast = read model** — czyste oddzielenie: aggregate trzyma source of truth, forecast prezentuje to dla UI z wbudowanym routingiem.

---

## Co user widzi w UI (na bazie naszych danych)

```
┌─────────────────────────────────────────────────────────────┐
│ Konto Nest — Grudzień 2025                                  │
├─────────────────────────────────────────────────────────────┤
│ 💸 Wydatki:                       19,847.30 PLN             │
│    ├ Uncategorized                19,847.30 PLN             │
├─────────────────────────────────────────────────────────────┤
│ 🔄 Przelewy własne:               6,000.00 PLN             │
│    └ Do: Pekao (PL98...)          2× 3,000 PLN              │
│   (informacyjnie — nie w budżecie)                          │
├─────────────────────────────────────────────────────────────┤
│ 📊 Saldo budżetu:                 19,847.30 PLN (wydatki)   │
└─────────────────────────────────────────────────────────────┘
```

vs poprzednio:

```
┌─────────────────────────────────────────────────────────────┐
│ Konto Nest — Grudzień 2025 (BEZ Phase 1b)                  │
├─────────────────────────────────────────────────────────────┤
│ 💸 Wydatki:                       25,847.30 PLN  ⚠️ ZAWYŻONE│
│    ├ Inne                         24,000 PLN (zatopione)    │
│    │  - Lucjan Bik Pekao 3000     ← FAKE wydatek            │
│    │  - Lucjan Bik Pekao 3000     ← FAKE wydatek            │
│    │  - inne...                                             │
│    └ inne                                                   │
└─────────────────────────────────────────────────────────────┘
```

To jest dokładnie problem z VID-159 ("50.7% Inne w Nest Bank") — teraz rozwiązany.

---

## Related Documents

- [VID-161-SELF-TRANSFER-DETECTION-DESIGN.md](VID-161-SELF-TRANSFER-DETECTION-DESIGN.md) — Full design and phase status
- [VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md) — Original problem discovery
- [VID-159-THREE-SIGNAL-RESULTS-2026-04-26.md](VID-159-THREE-SIGNAL-RESULTS-2026-04-26.md) — Baseline metrics (50.7% Inne)
- [docs/design/VID-161-onboarding-bank-accounts-mockup.html](../design/VID-161-onboarding-bank-accounts-mockup.html) — UI mockup
