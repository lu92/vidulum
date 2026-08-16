# VID-152: Enrichment Prompt Design

## Problem Statement

Po transformacji CSV (Etap 1) mamy niespójne dane między bankami:

| Pole | PEKAO | NEST BANK |
|------|-------|-----------|
| **bankCategory** | 100% (ma w CSV) | 0% (nie ma w CSV) |
| **merchant** | 0% | 0% |
| **description** | 93% | 100% |
| **paymentMethod** | 100% | 0% |

**Merchant nigdy nie jest wyliczany** - kod `extractMerchant()` w `LocalCsvTransformer` jest martwy bo:
1. Prompt mówi "for card transactions" → AI nie generuje mappingu MERCHANT_EXTRACT
2. Kod zwraca "" gdy name nie jest bankiem-pośrednikiem

## Proponowane Rozwiązanie: 3-Etapowy Pipeline

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  ETAP 1: TRANSFORMATION PROMPT (już istnieje)                                   │
│                                                                                  │
│  Input:  Raw bank CSV (Pekao/Nest/dowolny format)                               │
│  Output: Canonical CSV (struktura ustandaryzowana, ale puste pola)              │
│  AI:     Analizuje SAMPLE → zwraca MappingRules → LocalCsvTransformer           │
│                                                                                  │
│  PEKAO:     bankCategory=✅  merchant=❌  paymentMethod=✅                       │
│  NEST BANK: bankCategory=❌  merchant=❌  paymentMethod=❌                       │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       ↓
┌─────────────────────────────────────────────────────────────────────────────────┐
│  ETAP 2: ENRICHMENT PROMPT (NOWY)                                               │
│                                                                                  │
│  Input:  Canonical CSV (może mieć puste bankCategory, merchant)                 │
│  Output: Enriched CSV (uzupełnione bankCategory + merchant)                     │
│  AI:     Widzi WSZYSTKIE wiersze, uzupełnia brakujące pola                      │
│                                                                                  │
│  PEKAO:     bankCategory=✅  merchant=✅  paymentMethod=✅                       │
│  NEST BANK: bankCategory=✅  merchant=✅  paymentMethod=❌                       │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       ↓
┌─────────────────────────────────────────────────────────────────────────────────┐
│  ETAP 3: CATEGORIZATION PROMPT (UPROSZCZONY)                                    │
│                                                                                  │
│  Input:  Enriched CSV (ustandaryzowany, pełne dane)                             │
│  Output: Category suggestions + pattern mappings                                │
│  AI:     Prostsze zadanie - dane już znormalizowane                             │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## Etap 2: Enrichment Prompt - Szczegółowy Design

### Cel

Dla każdej transakcji w canonical CSV:
1. **Jeśli bankCategory = EMPTY** → AI wylicza na podstawie name + description
2. **ZAWSZE wylicza merchant** → znormalizowany identyfikator WHO

### Input Format

AI otrzymuje canonical CSV jako JSON array:

```json
{
  "transactions": [
    {
      "id": "TXN-123",
      "name": "Silva Silva, Warszawa",
      "description": "czynsz Lokal: 00-070 -020",
      "bankCategory": "",
      "amount": -2500.00,
      "type": "OUTFLOW"
    },
    {
      "id": "TXN-456",
      "name": "BANK PEKAO S.A.",
      "description": "ROZLICZENIE TRANSAKCJI Badoo help@badoo.com Dublin",
      "bankCategory": "Inne",
      "amount": -21.99,
      "type": "OUTFLOW"
    }
  ]
}
```

### Output Format

```json
{
  "enrichedTransactions": [
    {
      "id": "TXN-123",
      "merchant": "SILVA SILVA",
      "merchantConfidence": 0.9,
      "bankCategory": "Mieszkanie",
      "bankCategorySource": "AI_INFERRED"
    },
    {
      "id": "TXN-456",
      "merchant": "BADOO",
      "merchantConfidence": 0.95,
      "bankCategory": "Inne",
      "bankCategorySource": "ORIGINAL"
    }
  ]
}
```

### System Prompt (Draft)

```
You are a transaction data enrichment specialist.

Your task: For each transaction, determine:
1. MERCHANT - normalized identifier of WHO the transaction is with
2. BANK_CATEGORY - semantic category if missing

## MERCHANT EXTRACTION RULES

MERCHANT = the clean, human-recognizable name of the business or person.

Examples:
- "SHIVAGO SPOLKA Z OGRAN MIELEC" → "SHIVAGO"
- "Silva Silva, Warszawa" → "SILVA SILVA"
- "BANK PEKAO S.A." + desc: "Badoo help@badoo.com" → "BADOO"
- "ZUS" → "ZUS"
- "Przelew od Jan Kowalski" → "JAN KOWALSKI"
- "BIEDRONKA SKLEP 4521 WARSZAWA UL..." → "BIEDRONKA"
- "NETFLIX.COM 866-579-7172" → "NETFLIX"

Rules:
- Remove legal suffixes (S.A., SP. Z O.O., SPOLKA, etc.)
- Remove addresses, cities, terminal IDs
- Remove transaction codes and references
- Keep only the recognizable entity name
- UPPERCASE for consistency
- When name is a bank intermediary, extract real merchant from description

## BANK_CATEGORY INFERENCE RULES

Only infer bankCategory if the original is EMPTY.
If original bankCategory exists, keep it unchanged (bankCategorySource: "ORIGINAL").

Infer from name + description context:
- "czynsz", "Lokal", "mieszkanie" → "Mieszkanie"
- "składki ZUS", "podatek" → "Podatki i składki"
- "Netflix", "Spotify", "HBO" → "Rozrywka"
- "Biedronka", "Lidl", "Żabka" → "Zakupy spożywcze"
- "prowizja", "opłata bankowa" → "Opłaty bankowe"

## MERCHANT_CONFIDENCE

Score 0.0 to 1.0:
- 0.95+ = exact business name found (email domain, known brand)
- 0.8-0.95 = clear company/person name extracted
- 0.5-0.8 = inferred from context
- <0.5 = uncertain, used fallback to name

## OUTPUT

Return JSON array with id, merchant, merchantConfidence, bankCategory, bankCategorySource.
```

### Batching Strategy

Dla dużych plików (800+ transakcji):

```
Option A: Single large prompt
- Pros: AI sees full context, can detect patterns
- Cons: Token limit (~128k), cost

Option B: Batch processing (100 transactions per call)
- Pros: Fits token limits, parallel processing
- Cons: AI doesn't see cross-batch patterns

Recommendation: Option B with pattern aggregation
- Process in batches of 100
- Aggregate unique merchants across batches
- Second pass for consistency normalization
```

### Implementation Components

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  NEW CLASSES                                                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│  EnrichmentPromptBuilder.java     - builds system + user prompt             │
│  TransactionEnrichmentService.java - orchestrates enrichment                │
│  EnrichmentResult.java            - DTO for AI response                     │
│  CsvEnricher.java                 - applies enrichment to CSV               │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  MODIFIED FLOW                                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  AiBankCsvTransformService.transform()                                       │
│    │                                                                         │
│    ├── 1. obtainMappingRules() → MappingRules                               │
│    ├── 2. localCsvTransformer.transform() → canonical CSV                   │
│    ├── 3. NEW: enrichmentService.enrich() → enriched CSV  ← NOWE           │
│    └── 4. save to MongoDB                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Przykłady Enrichment

### Nest Bank (brak bankCategory)

**Input:**
```csv
name,description,bankCategory,merchant
"Silva Silva, Warszawa","czynsz Lokal: 00-070","",""
"ZUS","składki ZUS","",""
"Lucjan Bik Pekao","zycie","",""
```

**Output (po Etapie 2):**
```csv
name,description,bankCategory,merchant,merchantConfidence
"Silva Silva, Warszawa","czynsz Lokal: 00-070","Mieszkanie","SILVA SILVA",0.9
"ZUS","składki ZUS","Podatki i składki","ZUS",0.95
"Lucjan Bik Pekao","zycie","Inne","LUCJAN BIK",0.7
```

### Pekao (ma bankCategory, brak merchant)

**Input:**
```csv
name,description,bankCategory,merchant
"BANK PEKAO S.A.","ROZLICZENIE... Badoo help@badoo.com","Inne",""
"SHIVAGO SPOLKA Z OGRAN MIELEC","*****0015010","Uroda, fryzjer",""
"BIEDRONKA 4521 WARSZAWA","","Zakupy spożywcze",""
```

**Output (po Etapie 2):**
```csv
name,description,bankCategory,merchant,merchantConfidence
"BANK PEKAO S.A.","ROZLICZENIE... Badoo help@badoo.com","Inne","BADOO",0.95
"SHIVAGO SPOLKA Z OGRAN MIELEC","*****0015010","Uroda, fryzjer","SHIVAGO",0.85
"BIEDRONKA 4521 WARSZAWA","","Zakupy spożywcze","BIEDRONKA",0.95
```

## Korzyści

| Aspekt | Opis |
|--------|------|
| **Ustandaryzowane dane** | Po Etapie 2 KAŻDY bank ma te same pola wypełnione |
| **Merchant zawsze dostępny** | Lepsze grupowanie w PatternDeduplicator |
| **AI widzi pełny kontekst** | AI analizuje WSZYSTKIE transakcje naraz |
| **Prostszy Etap 3** | Categorization nie musi "zgadywać" - ma czyste dane |
| **Separacja odpowiedzialności** | Każdy prompt ma jedno zadanie |

## Wyzwania i Mitigation

| Wyzwanie | Rozwiązanie |
|----------|-------------|
| 800 wierszy w jednym prompt | Podzielić na batche (100 wierszy) |
| Koszt API | Jeden dodatkowy call, ale prostszy categorization |
| Spójność merchant | Post-processing: normalizacja (NETFLIX vs Netflix Inc) |
| Kolejność w pipeline | Enrichment MUSI być przed staging session |

## Alternatywy Rozważane

### A: Rozszerzyć extractMerchant() w LocalCsvTransformer

**Odrzucone bo:**
- Hardcoded pattern matching nie skaluje się
- Nie rozumie semantyki ("BIEDRONKA SKLEP 4521" nie ma patternu)
- Nie widzi kontekstu innych transakcji

### B: Merchant w Categorization Prompt

**Odrzucone bo:**
- Categorization prompt już jest złożony
- Mieszanie odpowiedzialności (enrichment vs categorization)
- Trudniejsze debugowanie

### C: Merchant w Transformation Prompt

**Odrzucone bo:**
- AI widzi tylko SAMPLE (10-20 wierszy), nie wszystkie
- MappingRules to reguły, nie dane per-transaction

## Next Steps

1. [ ] Implementacja `EnrichmentPromptBuilder.java`
2. [ ] Implementacja `TransactionEnrichmentService.java`
3. [ ] Modyfikacja `AiBankCsvTransformService` - dodanie etapu enrichment
4. [ ] Testy jednostkowe z sample Pekao + Nest Bank
5. [ ] Testy integracyjne end-to-end
6. [ ] Uproszczenie Categorization Prompt (Etap 3)

---

## Architektura: Porównanie Obecnego vs Nowego Flow

### OBECNA ARCHITEKTURA (bez Enrichment)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              OBECNA ARCHITEKTURA                                    │
└─────────────────────────────────────────────────────────────────────────────────────┘

 [USER]
    │
    │  POST /api/v1/bank-data-adapter/transform
    │  file=pekao.csv, bankHint="Pekao"
    ▼
┌───────────────────────────────────────┐
│   AiBankCsvController                 │  rest/AiBankCsvController.java:36
│   @PostMapping("/transform")          │
└───────────────┬───────────────────────┘
                │
                │ transformService.transform()
                ▼
┌───────────────────────────────────────┐
│   AiBankCsvTransformService           │  app/AiBankCsvTransformService.java:100
│   transform()                         │
│                                       │
│   1. Check cache (bankIdentifier)     │  :147-161
│   2. If miss → AI for MappingRules    │  :164-167
│   3. LocalCsvTransformer.transform()  │  :176
│   4. Save AiCsvTransformationDocument │  :210
└───────────────┬───────────────────────┘
                │
                │ localCsvTransformer.transform()
                ▼
┌───────────────────────────────────────┐
│   LocalCsvTransformer                 │  infrastructure/LocalCsvTransformer.java:38
│   transform()                         │
│                                       │
│   - Parses bank CSV rows              │  :55-78
│   - Applies MappingRules              │  :123-209
│   - extractMerchant() ← 🔴 BROKEN!    │  :488-542
│                                       │
│   Output: Canonical CSV               │
│   (merchant ZAWSZE pusty!)            │
└───────────────┬───────────────────────┘
                │
                │ Canonical CSV saved in document
                ▼
┌───────────────────────────────────────┐
│   AiCsvTransformationDocument         │  domain/AiCsvTransformationDocument.java
│   (MongoDB)                           │
│                                       │
│   transformedCsvContent = CSV         │
│   detectedBank = "Pekao"              │
│   ...                                 │
└───────────────────────────────────────┘

              ═══════════════════════════════════════════════════════
                         USER: POST /{transformationId}/import
              ═══════════════════════════════════════════════════════

┌───────────────────────────────────────┐
│   AiBankCsvController                 │  rest/AiBankCsvController.java:109
│   @PostMapping("/{id}/import")        │
│                                       │
│   ingestionClient.sendToIngestion()   │  :134
└───────────────┬───────────────────────┘
                │
                │ HTTP call to BankDataIngestionRestController
                ▼
┌───────────────────────────────────────┐
│   BankDataIngestionRestController     │  app/BankDataIngestionRestController.java
│   (staging session created)           │
│                                       │
│   CSV → StagedTransactions            │
│   merchant = "" (puste!)              │
│   bankCategory = "" (Nest) / ok (Pekao)│
└───────────────┬───────────────────────┘
                │
                │ POST .../staging/{id}/ai-categorize
                ▼
┌───────────────────────────────────────┐
│   AiCategorizationService             │  app/categorization/AiCategorizationService.java
│   categorize()                        │
│                                       │
│   1. PatternDeduplicator.deduplicate()│  :85 ← uses effectiveMerchant()
│      → groupuje po NAME bo merchant   │       (= fallback do name gdy puste)
│        jest zawsze pusty!             │
│                                       │
│   2. AI prompt (568 linii!)           │  AiCategorizationPromptBuilder.java
│      → skomplikowany bo musi          │
│        obsłużyć niespójne dane        │
└───────────────────────────────────────┘

🔴 PROBLEMY OBECNEGO FLOW:
1. merchant ZAWSZE pusty → groupowanie po `name` (brak normalizacji)
2. bankCategory puste dla Nest Bank → AI musi sam kategoryzować
3. Categorization Prompt 568 linii - zbyt skomplikowany
4. Różne banki → różne dane → niestabilne wyniki
```

---

### NOWA ARCHITEKTURA (z Enrichment Prompt)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                        NOWA ARCHITEKTURA (z Enrichment)                             │
└─────────────────────────────────────────────────────────────────────────────────────┘

 [USER]
    │
    │  POST /api/v1/bank-data-adapter/transform
    ▼
┌───────────────────────────────────────┐
│   AiBankCsvController                 │  (bez zmian)
│   @PostMapping("/transform")          │
└───────────────┬───────────────────────┘
                │
                ▼
┌───────────────────────────────────────┐
│   AiBankCsvTransformService           │  ⬅ ZMIANY TUTAJ
│   transform()                         │
│                                       │
│   [ETAP 1: Transformation - bez zmian]│
│   1. Check cache                      │
│   2. If miss → AI for MappingRules    │
│   3. LocalCsvTransformer.transform()  │
│      Output: RAW Canonical CSV        │
│              (może mieć puste pola)   │
│                                       │
│   ┌─────────────────────────────────┐ │
│   │  🆕 ETAP 2: Enrichment          │ │  ⬅ NOWY KOD
│   │                                 │ │
│   │  Input: Canonical CSV           │ │
│   │  (z Etapu 1)                    │ │
│   │                                 │ │
│   │  transactionEnrichmentService   │ │  🆕 NOWY KOMPONENT
│   │    .enrich(canonicalCsv)        │ │
│   │                                 │ │
│   │  Output: Enriched CSV           │ │
│   │  - merchant: ZAWSZE uzupełniony │ │
│   │  - bankCategory: ZAWSZE        │ │
│   │  - paymentMethod: jeśli brak   │ │
│   └─────────────────────────────────┘ │
│                                       │
│   [Normalization Validation]          │  🆕 checkpoint
│   - Validate all required fields      │
│   - Reject if merchant still empty    │
│                                       │
│   4. Save document (Enriched CSV)     │
└───────────────┬───────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────────────────────────────┐
│   🆕 TransactionEnrichmentService     │  NOWY PLIK                                │
│   (bank_data_adapter/app/)            │                                           │
│                                       │                                           │
│   enrich(String canonicalCsv):        │                                           │
│   │                                   │                                           │
│   │  1. Parse Canonical CSV           │                                           │
│   │  2. Identify missing fields:      │                                           │
│   │     - merchant empty?             │                                           │
│   │     - bankCategory empty?         │                                           │
│   │                                   │                                           │
│   │  3. If any missing → call AI:     │                                           │
│   │     enrichmentPromptBuilder       │  🆕 EnrichmentPromptBuilder.java          │
│   │       .build(transactions)        │                                           │
│   │                                   │                                           │
│   │  4. AI Response → update CSV      │                                           │
│   │                                   │                                           │
│   │  5. Return Enriched CSV           │                                           │
│   │     (wszystkie pola uzupełnione)  │                                           │
│   └───────────────────────────────────┘                                           │
└───────────────────────────────────────────────────────────────────────────────────┘
                │
                │ Enriched CSV saved
                ▼
┌───────────────────────────────────────┐
│   AiCsvTransformationDocument         │
│   (MongoDB)                           │
│                                       │
│   transformedCsvContent =             │
│     ENRICHED CSV (pełne dane!)        │
│   enrichmentApplied = true     🆕     │
│   enrichedAt = timestamp       🆕     │
└───────────────────────────────────────┘

              ═══════════════════════════════════════════════════════
                         USER: POST /{transformationId}/import
              ═══════════════════════════════════════════════════════

┌───────────────────────────────────────┐
│   BankDataIngestionRestController     │
│   (staging session created)           │
│                                       │
│   CSV → StagedTransactions            │
│   merchant = "NETFLIX" ✅             │  ⬅ teraz uzupełniony!
│   bankCategory = "Rozrywka" ✅        │  ⬅ teraz uzupełniony!
└───────────────┬───────────────────────┘
                │
                │ POST .../staging/{id}/ai-categorize
                ▼
┌───────────────────────────────────────┐
│   AiCategorizationService             │  ⬅ UPROSZCZONY
│   categorize()                        │
│                                       │
│   1. PatternDeduplicator.deduplicate()│
│      → groupuje po MERCHANT           │  ⬅ teraz działa!
│        (nie fallback do name)         │
│                                       │
│   2. AI prompt (~200 linii)           │  ⬅ prostszy!
│      → dane znormalizowane            │
│      → tylko mapowanie kategorii      │
└───────────────────────────────────────┘

✅ KORZYŚCI NOWEGO FLOW:
1. merchant ZAWSZE uzupełniony → prawidłowe grupowanie
2. bankCategory ZAWSZE → łatwiejsze mapowanie
3. Categorization Prompt ~200 linii (z 568)
4. Stabilne wyniki niezależnie od banku
```

---

### Komponenty do dodania/modyfikacji

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          NOWE KOMPONENTY (proponowane)                              │
└─────────────────────────────────────────────────────────────────────────────────────┘

📁 src/main/java/com/multi/vidulum/bank_data_adapter/
│
├── 📁 app/
│   │
│   ├── AiBankCsvTransformService.java     ← MODIFY: dodaj etap enrichment
│   │   │
│   │   │  // Po linii 176:
│   │   │  // Step 3a: ENRICHMENT (new!)
│   │   │  if (needsEnrichment(transformedCsv)) {
│   │   │      transformedCsv = transactionEnrichmentService.enrich(
│   │   │          transformedCsv, rules.getBankName(), rules.getLanguage()
│   │   │      );
│   │   │  }
│   │   │
│   │   └── // Step 3b: Validation (new!)
│   │       validateNormalization(transformedCsv);
│   │
│   ├── 🆕 TransactionEnrichmentService.java     ← NOWY KOMPONENT
│   │   │
│   │   │  @Service
│   │   │  public class TransactionEnrichmentService {
│   │   │      private final ChatModel chatModel;
│   │   │      private final EnrichmentPromptBuilder promptBuilder;
│   │   │      private final EnrichmentResponseProcessor responseProcessor;
│   │   │
│   │   │      public String enrich(String canonicalCsv, String bank, String lang) {
│   │   │          // 1. Parse CSV
│   │   │          // 2. Identify transactions needing enrichment
│   │   │          // 3. Batch by 50 (cost optimization)
│   │   │          // 4. Call AI for each batch
│   │   │          // 5. Merge results into CSV
│   │   │          // 6. Return enriched CSV
│   │   │      }
│   │   │  }
│   │   │
│   │   └── ...
│   │
│   └── ...
│
├── 📁 infrastructure/
│   │
│   ├── 🆕 EnrichmentPromptBuilder.java          ← NOWY
│   │   │
│   │   │  Odpowiedzialność:
│   │   │  - Buduje prompt dla AI do uzupełnienia merchant/bankCategory
│   │   │  - ~150 linii (prosty, specyficzne zadanie)
│   │   │  - Bank-agnostic (nie zależy od formatu banku)
│   │   │
│   │   │  Input: List<CanonicalTransaction> (batch 50)
│   │   │  Output: System prompt + User prompt
│   │   │
│   │   └── ...
│   │
│   ├── 🆕 EnrichmentResponseProcessor.java      ← NOWY
│   │   │
│   │   │  Odpowiedzialność:
│   │   │  - Parsuje odpowiedź AI (JSON)
│   │   │  - Waliduje merchant/bankCategory
│   │   │  - Zwraca List<EnrichedTransaction>
│   │   │
│   │   └── ...
│   │
│   ├── 🆕 NormalizationValidator.java           ← NOWY
│   │   │
│   │   │  Odpowiedzialność:
│   │   │  - Waliduje że wszystkie wymagane pola są uzupełnione
│   │   │  - Checkpoint przed przejściem do Categorization
│   │   │
│   │   │  public void validate(String enrichedCsv) throws NormalizationException {
│   │   │      // Check: merchant not empty for all rows
│   │   │      // Check: bankCategory not empty for all rows
│   │   │      // Check: paymentMethod not empty for all rows
│   │   │  }
│   │   │
│   │   └── ...
│   │
│   └── LocalCsvTransformer.java          ← BEZ ZMIAN (Etap 1)
│
└── 📁 domain/
    │
    └── AiCsvTransformationDocument.java  ← MODIFY: dodaj pola
        │
        │  // Nowe pola:
        │  private boolean enrichmentApplied;
        │  private Date enrichedAt;
        │  private int enrichmentBatchCount;
        │  private long enrichmentTimeMs;
        │
        └── ...
```

---

### Tabela zmian w komponentach

| Komponent | Akcja | Lokalizacja |
|-----------|-------|-------------|
| `AiBankCsvTransformService` | MODIFY | `app/AiBankCsvTransformService.java:176+` |
| `TransactionEnrichmentService` | **NEW** | `app/TransactionEnrichmentService.java` |
| `EnrichmentPromptBuilder` | **NEW** | `infrastructure/EnrichmentPromptBuilder.java` |
| `EnrichmentResponseProcessor` | **NEW** | `infrastructure/EnrichmentResponseProcessor.java` |
| `NormalizationValidator` | **NEW** | `infrastructure/NormalizationValidator.java` |
| `AiCsvTransformationDocument` | MODIFY | `domain/AiCsvTransformationDocument.java` |

---

## Diagram Sekwencji: Pełny Flow z Enrichment

```
┌─────────┐     ┌─────────────┐     ┌─────────────────┐     ┌─────────────┐     ┌────────────────┐
│  User   │     │ Controller  │     │ TransformService│     │ Enrichment  │     │ ChatModel (AI) │
└────┬────┘     └──────┬──────┘     └────────┬────────┘     │  Service    │     └───────┬────────┘
     │                 │                     │              └──────┬──────┘             │
     │  POST /transform│                     │                     │                    │
     │ ───────────────>│                     │                     │                    │
     │                 │                     │                     │                    │
     │                 │  transform(csv)     │                     │                    │
     │                 │ ───────────────────>│                     │                    │
     │                 │                     │                     │                    │
     │                 │                     │ ┌─────────────────────────────────────┐  │
     │                 │                     │ │ ETAP 1: Transformation              │  │
     │                 │                     │ │                                     │  │
     │                 │                     │ │ 1. Check cache                      │  │
     │                 │                     │ │ 2. AI → MappingRules (if miss)      │──┼─> (AI call)
     │                 │                     │ │ 3. LocalCsvTransformer.transform()  │  │
     │                 │                     │ │    Output: RAW Canonical CSV        │  │
     │                 │                     │ └─────────────────────────────────────┘  │
     │                 │                     │                     │                    │
     │                 │                     │ ┌─────────────────────────────────────┐  │
     │                 │                     │ │ ETAP 2: Enrichment (NEW)            │  │
     │                 │                     │ │                                     │  │
     │                 │                     │ │ 4. needsEnrichment(csv)?            │  │
     │                 │                     │ │    ↓ YES                            │  │
     │                 │                     │ │    enrich(canonicalCsv)             │  │
     │                 │                     │ └─────────────┬───────────────────────┘  │
     │                 │                     │               │                          │
     │                 │                     │               │  enrich(csv)             │
     │                 │                     │               │ <────────────────────────│
     │                 │                     │               │                          │
     │                 │                     │               │  [Parse, identify gaps]  │
     │                 │                     │               │                          │
     │                 │                     │               │  [Batch 50 transactions] │
     │                 │                     │               │                          │
     │                 │                     │               │  AI: "Fill merchant,     │
     │                 │                     │               │       bankCategory"      │
     │                 │                     │               │ ─────────────────────────>│
     │                 │                     │               │                          │
     │                 │                     │               │  JSON response           │
     │                 │                     │               │ <─────────────────────────│
     │                 │                     │               │                          │
     │                 │                     │               │  [Merge into CSV]        │
     │                 │                     │               │                          │
     │                 │                     │  enrichedCsv  │                          │
     │                 │                     │ <─────────────┘                          │
     │                 │                     │                                          │
     │                 │                     │ ┌─────────────────────────────────────┐  │
     │                 │                     │ │ VALIDATION CHECKPOINT               │  │
     │                 │                     │ │                                     │  │
     │                 │                     │ │ 5. validateNormalization(csv)       │  │
     │                 │                     │ │    - merchant ≠ empty for all rows  │  │
     │                 │                     │ │    - bankCategory ≠ empty           │  │
     │                 │                     │ └─────────────────────────────────────┘  │
     │                 │                     │                                          │
     │                 │                     │  6. Save document (enriched CSV)         │
     │                 │                     │                                          │
     │                 │  TransformResponse  │                                          │
     │                 │ <───────────────────│                                          │
     │                 │                                                                │
     │  JSON response  │                                                                │
     │ <───────────────│                                                                │
     │                 │                                                                │
```

---

### Szczegółowy diagram wywołań metod

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│  WYWOŁANIA METOD W NOWYM FLOW                                                       │
└─────────────────────────────────────────────────────────────────────────────────────┘

AiBankCsvController.transform(file, bankHint)
    │
    └─→ AiBankCsvTransformService.transform(csvContent, fileName, bankHint, userId)
            │
            ├─→ validateFile(csvContent, fileName)
            │
            ├─→ isCanonicalFormat(csvString)
            │       └─→ [if true] handleCanonicalFormat() → return
            │
            ├─→ mappingRulesCacheService.computeBankIdentifier(csvString)
            │
            ├─→ mappingRulesCacheService.findByBankIdentifier(bankIdentifier)
            │       └─→ [if miss] obtainMappingRulesFromAi()
            │                         ├─→ csvAnonymizer.anonymizeAndSample()
            │                         ├─→ mappingRulesPromptBuilder.buildUserPrompt()
            │                         ├─→ chatModel.call(prompt)
            │                         └─→ mappingRulesProcessor.process()
            │
            ├─→ localCsvTransformer.transform(csvString, rules)
            │       ├─→ parseCsvLine()
            │       ├─→ transformRow()
            │       │       ├─→ applyTransformation() [dla każdej kolumny]
            │       │       ├─→ extractMerchant()  ← 🔴 BROKEN (obecnie)
            │       │       └─→ ...
            │       └─→ return TransformResult (RAW Canonical CSV)
            │
            │   ════════════════════════════════════════════════════════════════════
            │   🆕 NOWY ETAP: ENRICHMENT
            │   ════════════════════════════════════════════════════════════════════
            │
            ├─→ 🆕 enrichmentService.needsEnrichment(transformedCsv)
            │       ├─→ parseCsv(transformedCsv)
            │       ├─→ checkMerchantEmpty()
            │       ├─→ checkBankCategoryEmpty()
            │       └─→ return boolean
            │
            ├─→ 🆕 [if needsEnrichment] enrichmentService.enrich(transformedCsv, bank, lang)
            │       │
            │       ├─→ prepareInput(canonicalCsv)
            │       │       └─→ parseCSV() → List<TransactionForEnrichment>
            │       │
            │       ├─→ batchTransactions(transactions, batchSize=50)
            │       │       └─→ List<List<TransactionForEnrichment>>
            │       │
            │       ├─→ [for each batch]
            │       │       ├─→ enrichmentPromptBuilder.buildSystemPrompt()
            │       │       ├─→ enrichmentPromptBuilder.buildUserPrompt(batch)
            │       │       ├─→ chatModel.call(prompt)
            │       │       └─→ responseProcessor.parse(aiResponse)
            │       │               └─→ List<EnrichedTransaction>
            │       │
            │       ├─→ mergeEnrichments(originalCsv, allEnrichments)
            │       │
            │       └─→ return EnrichmentResult(enrichedCsv, warnings, timeMs)
            │
            ├─→ 🆕 normalizationValidator.validate(transformedCsv)
            │       ├─→ checkMerchantNotEmpty()
            │       ├─→ checkBankCategoryNotEmpty()
            │       ├─→ checkMerchantConfidenceValid()
            │       └─→ return ValidationResult
            │
            │   ════════════════════════════════════════════════════════════════════
            │   KONIEC NOWEGO ETAPU
            │   ════════════════════════════════════════════════════════════════════
            │
            ├─→ extractDateRangeAndUpdate(document, transformedCsv)
            │
            ├─→ document.setEnrichmentApplied(true) 🆕
            ├─→ document.setEnrichedAt(now) 🆕
            ├─→ document.setEnrichmentTimeMs(timeMs) 🆕
            │
            └─→ transformationRepository.save(document)
```

---

### Miejsce wstawienia kodu w AiBankCsvTransformService

W pliku `AiBankCsvTransformService.java`, po linii **176** (po `localCsvTransformer.transform()`):

```java
// Step 3: Transform using rules (or fallback to direct AI)
String transformedCsv;
int rowCount;
List<String> warnings = new ArrayList<>();

if (rules != null) {
    // Use local transformer
    LocalCsvTransformer.TransformResult localResult = localCsvTransformer.transform(csvString, rules);
    if (localResult.success()) {
        transformedCsv = localResult.csvContent();
        rowCount = localResult.rowCount();
        warnings.addAll(localResult.warnings());
        document.setDetectedBank(rules.getBankName());
        document.setDetectedLanguage(rules.getLanguage());
        document.setDetectedCountry(rules.getBankCountry());

        // ─────────────────────────────────────────────────────────────
        // 🆕 ETAP 2: ENRICHMENT (NOWY KOD TUTAJ)
        // ─────────────────────────────────────────────────────────────
        if (enrichmentService.needsEnrichment(transformedCsv)) {
            log.info("Enrichment needed - calling AI to fill merchant/bankCategory");

            EnrichmentResult enrichmentResult = enrichmentService.enrich(
                transformedCsv,
                rules.getBankName(),
                rules.getLanguage()
            );

            transformedCsv = enrichmentResult.enrichedCsv();
            warnings.addAll(enrichmentResult.warnings());

            document.setEnrichmentApplied(true);
            document.setEnrichedAt(new Date());
            document.setEnrichmentTimeMs(enrichmentResult.processingTimeMs());
        }

        // 🆕 ETAP 2b: VALIDATION CHECKPOINT
        NormalizationValidator.ValidationResult validation =
            normalizationValidator.validate(transformedCsv);

        if (!validation.isValid()) {
            warnings.add("Normalization incomplete: " + validation.issues());
        }
        // ─────────────────────────────────────────────────────────────

    } else {
        // Local transform failed - fallback to direct AI
        log.warn("Local transform failed: {}, falling back to direct AI", localResult.errorMessage());
        return transformWithDirectAi(document, csvString, bankHint, startTime);
    }
}
```

## Related Files

- `AiMappingRulesPromptBuilder.java` - current transformation prompt
- `LocalCsvTransformer.java` - current extractMerchant() (unused)
- `AiCategorizationPromptBuilder.java` - current categorization prompt
- `PatternDeduplicator.java` - uses effectiveMerchant() for grouping

---

## Appendix: Analiza Danych Nest Bank CSV

### Struktura danych w pliku źródłowym

Nest Bank CSV ma następujące kolumny:
- `Data księgowania`, `Data operacji` - daty
- `Rodzaj operacji` - **TO NIE JEST bankCategory!**
- `Kwota`, `Waluta` - wartości
- `Dane kontrahenta` - **IDEALNY merchant**
- `Numer rachunku kontrahenta` - IBAN
- `Tytuł operacji` - **IDEALNY do wnioskowania bankCategory**
- `Saldo po operacji` - saldo

### "Rodzaj operacji" - BEZUŻYTECZNE jako bankCategory

| Wartość | Liczba | Znaczenie |
|---------|--------|-----------|
| Przelewy wychodzące | 334 | To jest `paymentMethod`, nie kategoria! |
| Przelewy przychodzące | 37 | To jest `paymentMethod` |
| Opłaty i prowizje | 30 | To jest `paymentMethod` |
| Płatności kartą | 1 | To jest `paymentMethod` |

**Wniosek**: Kolumna "Rodzaj operacji" opisuje JAK (metoda płatności), nie CO (kategoria wydatku).

### "Dane kontrahenta" - IDEALNY merchant

Top kontrahenci w pliku (402 transakcje):

| Kontrahent | Liczba | merchant | bankCategory |
|------------|--------|----------|--------------|
| Lucjan Bik Pekao | 74 | LUCJAN BIK | Przelewy własne |
| Urzad skarbowy w Mielcu | 51 | URZĄD SKARBOWY MIELEC | Podatki |
| ZUS | 37 | ZUS | Składki ZUS |
| Ikano | 36 | IKANO | Kredyty/Raty |
| IFIRMA SA | 35 | IFIRMA | Usługi biznesowe |
| MINDBOX SPÓŁKA AKCYJNA | 34 | MINDBOX | Przychód z pracy |
| Lucjan Bik mbank | 32 | LUCJAN BIK | Przelewy własne |
| Mindbox S.A. | 16 | MINDBOX | Przychód z pracy |
| Silva Silva, Warszawa | 15 | SILVA SILVA | Mieszkanie/Czynsz |
| Santander | 9 | SANTANDER | Kredyty/Raty |
| Credit Agricole | 9 | CREDIT AGRICOLE | Kredyty/Raty |

**Obserwacja**: Dane kontrahenta są już CZYSTE - nie wymagają ekstrakcji jak w Pekao.

### "Tytuł operacji" - klucz do wnioskowania bankCategory

Przykłady:
- `składki ZUS` → Składki ZUS
- `czynsz Lokal: 00-070 -020 (Lokal mieszkalny)` → Mieszkanie
- `Faktura VAT nr 8348/11/BR/2025` → Usługi biznesowe
- `rata kredytu` → Kredyty
- `PIT28`, `VAT7K` → Podatki
- `zycie` → Przelewy własne

### Porównanie: Nest Bank vs Pekao

| Aspekt | Nest Bank | Pekao |
|--------|-----------|-------|
| **merchant w name** | ✅ Już czysty: "ZUS", "MINDBOX" | ❌ Często "BANK PEKAO S.A." |
| **bankCategory** | ❌ Brak w CSV | ✅ Jest w CSV |
| **Wnioskowanie bankCategory** | ✅ Łatwe z name+description | ➖ Niepotrzebne (już jest) |
| **Agregacja po kontrahentach** | ✅ Bardzo skuteczna (74x ten sam) | ➖ Mniej powtórzeń |

### Rekomendacja dla Enrichment Prompt

Dla Nest Bank enrichment jest **ŁATWIEJSZY** niż dla Pekao:

1. **merchant**: Prawie bez przetwarzania - wystarczy usunąć adresy i sufiksy prawne
2. **bankCategory**: Łatwe wnioskowanie z kombinacji name + description
3. **Agregacja**: Bardzo skuteczna - ~30 unikalnych kontrahentów dla 402 transakcji

**Strategia agregacji dla Nest Bank:**

```
Zamiast kategoryzować 402 transakcje osobno:

1. Zgrupuj po `name` (kontrahent) → ~30 grup
2. Dla każdej grupy wyznacz merchant + bankCategory
3. Propaguj do wszystkich transakcji w grupie

Rezultat: 90%+ pokrycie z ~30 reguł zamiast 402 decyzji
```

| Grupa (name) | Transakcji | merchant | bankCategory |
|--------------|------------|----------|--------------|
| Lucjan Bik * | 106 | LUCJAN BIK | Przelewy własne |
| Urzad skarbowy * | 51 | URZĄD SKARBOWY | Podatki |
| ZUS | 37 | ZUS | Składki ZUS |
| Ikano | 36 | IKANO | Kredyty |
| IFIRMA SA * | 35 | IFIRMA | Usługi biznesowe |
| MINDBOX * | 52 | MINDBOX | Przychód z pracy |
| Silva Silva * | 15 | SILVA SILVA | Mieszkanie |
| Santander/Credit Agricole | 18 | (bank) | Kredyty |
| **Razem** | **350/402** | | **87% pokrycia** |

---

## Appendix: Canonical CSV jako Input do Enrichment (Generyczne Rozwiązanie)

### Decyzja architektoniczna

**Input do Enrichment Prompt pochodzi z Canonical CSV, NIE z oryginalnych plików bankowych.**

Jest to świadoma decyzja architektoniczna zapewniająca:
- **Generyczność** - jeden Enrichment Prompt dla WSZYSTKICH banków
- **Separację odpowiedzialności** - Etap 1 = konwersja formatu, Etap 2 = wzbogacanie danych
- **Testowalność** - można testować enrichment niezależnie od transformacji
- **Debugowalność** - Canonical CSV jest checkpointem, widać co weszło do enrichment

### Struktura Canonical CSV (output Etapu 1)

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,merchant,merchantConfidence,paymentMethod
```

### Dostępność pól w Canonical CSV po transformacji

| Pole | Pekao | Nest Bank | Źródło |
|------|-------|-----------|--------|
| `bankTransactionId` | ✅ TXN-xxx | ✅ TXN-xxx | Generated |
| `name` | ✅ "SHIVAGO SPOLKA..." | ✅ "Silva Silva, Warszawa" | Mapped |
| `description` | ✅ "*****0015010" | ✅ "czynsz Lokal..." | Mapped |
| `bankCategory` | ✅ "Uroda, fryzjer" | ❌ "" (puste) | Mapped (jeśli istnieje) |
| `amount` | ✅ -140.00 | ✅ -2500.00 | Parsed |
| `currency` | ✅ PLN | ✅ PLN | Extracted |
| `type` | ✅ OUTFLOW | ✅ OUTFLOW | Detected |
| `operationDate` | ✅ 2026-01-09 | ✅ 2025-12-23 | Parsed |
| `merchant` | ❌ "" | ❌ "" | (puste - do enrichment) |
| `merchantConfidence` | ❌ "" | ❌ "" | (puste - do enrichment) |
| `paymentMethod` | ✅ CARD | ❌ "" | Mapped (jeśli istnieje) |

### Czy dane w Canonical CSV są wystarczające dla Enrichment?

**TAK** - Canonical CSV zawiera wszystko co potrzebne:

| Potrzeba Enrichment | Dostępne w Canonical CSV | Pole(a) |
|---------------------|--------------------------|---------|
| WHO (do wyliczenia merchant) | ✅ | `name` + `description` |
| WHAT (do wyliczenia bankCategory) | ✅ | `name` + `description` + `amount` + `type` |
| Kontekst finansowy | ✅ | `amount`, `type` (INFLOW/OUTFLOW) |
| Istniejąca kategoria bankowa | ✅ | `bankCategory` (może być puste) |
| Identyfikator transakcji | ✅ | `bankTransactionId` |

### Diagram przepływu danych (generyczny)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  GENERYCZNE ROZWIĄZANIE - ENRICHMENT NIE ZNA FORMATU BANKU                      │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Raw Pekao CSV ─┐                                                               │
│                 │                                                               │
│  Raw Nest CSV ──┼──→ ETAP 1 ──→ Canonical CSV ──→ ETAP 2 ──→ Enriched CSV      │
│                 │   (różne      (USTANDARYZOWANY   (JEDEN      (gotowy do      │
│  Raw ING CSV ───┘    prompty)    FORMAT)            prompt)     staging)       │
│                                                                                  │
│  Enrichment Prompt NIE MUSI znać formatu banku - widzi tylko Canonical CSV!    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Szczegółowy diagram przepływu

```
┌─────────────────┐
│  Raw Bank CSV   │  (Pekao: "Typ operacji", Nest: "Rodzaj operacji", etc.)
│  (dowolny       │  (różne kodowania, delimitery, formaty dat)
│   format)       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ETAP 1:        │  AI analizuje SAMPLE → MappingRules
│  Transformation │  LocalCsvTransformer stosuje reguły do WSZYSTKICH wierszy
│  Prompt         │  (bank-specific)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Canonical CSV  │  USTANDARYZOWANY FORMAT:
│  (checkpoint)   │  - name, description, bankCategory, amount, type, operationDate...
│                 │  - merchant/merchantConfidence = PUSTE (do uzupełnienia)
│                 │  - bankCategory może być puste (Nest Bank) lub wypełnione (Pekao)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ETAP 2:        │  AI widzi WSZYSTKIE wiersze Canonical CSV
│  Enrichment     │  Uzupełnia: merchant, merchantConfidence
│  Prompt         │  Uzupełnia: bankCategory (jeśli puste)
│  (GENERYCZNY)   │  NIE MUSI znać formatu oryginalnego banku!
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Enriched CSV   │  PEŁNE DANE:
│                 │  - merchant = "SILVA SILVA", "BADOO", "ZUS"
│                 │  - merchantConfidence = 0.95, 0.8, etc.
│                 │  - bankCategory = wypełnione (oryginalne lub AI-inferred)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Staging        │  Gotowe do kategoryzacji użytkownika
│  Session        │  PatternDeduplicator może grupować po merchant
└─────────────────┘
```

### Przykładowy input do Enrichment Prompt (z Canonical CSV)

Enrichment Prompt otrzymuje dane w formacie JSON, przekonwertowane z Canonical CSV:

```json
{
  "transactions": [
    {
      "id": "TXN-1117590056",
      "name": "UL. WOLNOSCI 23B MIELEC",
      "description": "*********0015010",
      "bankCategory": "Opieka medyczna",
      "amount": -50.00,
      "currency": "PLN",
      "type": "OUTFLOW",
      "operationDate": "2026-01-09",
      "paymentMethod": "CARD"
    },
    {
      "id": "TXN-357838421",
      "name": "Silva Silva, Warszawa",
      "description": "czynsz Lokal: 00-070 -020",
      "bankCategory": "",
      "amount": -1689.50,
      "currency": "PLN",
      "type": "OUTFLOW",
      "operationDate": "2025-12-23",
      "paymentMethod": ""
    }
  ]
}
```

**Kluczowa obserwacja**: AI widzi ustandaryzowane dane. Nie wie czy transakcja pochodzi z Pekao czy Nest Bank - i nie musi tego wiedzieć.

### Korzyści podejścia "Canonical CSV → Enrichment"

| Aspekt | Korzyść |
|--------|---------|
| **Generyczność** | Jeden Enrichment Prompt dla WSZYSTKICH banków |
| **Separacja** | Etap 1 = konwersja formatu, Etap 2 = wzbogacanie semantyczne |
| **Testowalność** | Można testować enrichment niezależnie od transformacji |
| **Debugowanie** | Canonical CSV jest checkpointem - widać co weszło do enrichment |
| **Spójność** | Enrichment zawsze widzi te same pola w tym samym formacie |
| **Rozszerzalność** | Dodanie nowego banku wymaga tylko nowego Transformation Prompt |
| **Maintenance** | Zmiany w enrichment nie wymagają zmian w transformacji i odwrotnie |

### Potencjalne problemy i rozwiązania

| Problem | Rozwiązanie |
|---------|-------------|
| Błędna transformacja w Etapie 1 | Enrichment nie naprawi - ale błąd widoczny w Canonical CSV (checkpoint) |
| Utrata oryginalnych danych | Canonical CSV zachowuje `name` i `description` w pełnej formie |
| Różna jakość `description` między bankami | Enrichment radzi sobie - używa `name` + `description` łącznie |
| Brak `bankCategory` (Nest Bank) | Enrichment wnioskuje z `name` + `description` |
| Brak `paymentMethod` (Nest Bank) | Pozostaje puste - nie jest krytyczne dla enrichment |

### Implementacja - konwersja Canonical CSV → JSON

```java
// W TransactionEnrichmentService.java

public EnrichmentInput prepareInput(String canonicalCsv) {
    List<TransactionForEnrichment> transactions = new ArrayList<>();

    try (CSVReader reader = new CSVReader(new StringReader(canonicalCsv))) {
        String[] header = reader.readNext();
        String[] row;

        while ((row = reader.readNext()) != null) {
            transactions.add(new TransactionForEnrichment(
                row[0],  // bankTransactionId
                row[1],  // name
                row[2],  // description
                row[3],  // bankCategory (może być puste)
                parseAmount(row[4]),  // amount
                row[5],  // currency
                row[6],  // type
                row[7],  // operationDate
                row[13]  // paymentMethod (może być puste)
            ));
        }
    }

    return new EnrichmentInput(transactions);
}
```

### Alternatywa odrzucona: Input z oryginalnego CSV

**Dlaczego NIE używamy oryginalnego CSV jako inputu do Enrichment:**

| Problem | Opis |
|---------|------|
| Różne formaty | Każdy bank ma inne kolumny, delimitery, kodowania |
| Brak standaryzacji | AI musiałoby rozumieć "Dane kontrahenta" vs "Nazwa odbiorcy" |
| Duplikacja logiki | Enrichment musiałby zawierać logikę parsowania dat, kwot |
| Niemożliwa generyczność | Osobny prompt dla każdego banku |
| Trudne testowanie | Nie można testować enrichment bez pliku bankowego |

---

## Appendix: Normalizacja Danych - Walidacja po Enrichment

### Cel normalizacji

Po Enrichment Prompt dane MUSZĄ być **znormalizowane** - czyli ustandaryzowane, kompletne i gotowe do kolejnych etapów. Jest to kluczowe dla **prostszej architektury rozwiązania**.

### Dlaczego normalizacja jest ważna?

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  BEZ NORMALIZACJI (obecny stan)                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Categorization Prompt musi:                                                    │
│  - Obsługiwać puste bankCategory (Nest Bank)                                   │
│  - Obsługiwać puste merchant (oba banki)                                       │
│  - Grupować po name (fallback gdy brak merchant)                               │
│  - Wnioskować kategorię z name+description (gdy brak bankCategory)             │
│  - Radzić sobie z różną jakością danych                                        │
│                                                                                  │
│  Rezultat: ZŁOŻONY prompt, trudny do debugowania, niespójne wyniki             │
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│  Z NORMALIZACJĄ (proponowane rozwiązanie)                                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Categorization Prompt otrzymuje:                                               │
│  - merchant ZAWSZE wypełniony (znormalizowany identyfikator)                   │
│  - bankCategory ZAWSZE wypełniony (oryginalny lub AI-inferred)                 │
│  - Dane SPÓJNE między bankami                                                  │
│                                                                                  │
│  Rezultat: PROSTY prompt, łatwy do debugowania, przewidywalne wyniki           │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Definicja "Normalized Canonical CSV"

Dane są **znormalizowane** gdy spełniają następujące warunki:

| Pole | Warunek normalizacji | Przykład |
|------|----------------------|----------|
| `merchant` | NOT NULL, NOT EMPTY, UPPERCASE | "NETFLIX", "ZUS", "SILVA SILVA" |
| `merchantConfidence` | NOT NULL, wartość 0.0-1.0 | 0.95, 0.7, 0.5 |
| `bankCategory` | NOT NULL, NOT EMPTY | "Rozrywka", "Mieszkanie", "Inne" |
| `bankCategorySource` | "ORIGINAL" lub "AI_INFERRED" | "ORIGINAL" |
| `name` | NOT NULL (bez zmian) | "Silva Silva, Warszawa" |
| `description` | może być puste (bez zmian) | "czynsz Lokal..." |
| `amount` | NOT NULL, liczba | -1689.50 |
| `type` | "INFLOW" lub "OUTFLOW" | "OUTFLOW" |

### Walidacja po Enrichment - Normalization Check

Po zakończeniu Enrichment Prompt, system MUSI zwalidować czy dane są znormalizowane:

```java
public class NormalizationValidator {

    public NormalizationResult validate(List<EnrichedTransaction> transactions) {
        List<NormalizationError> errors = new ArrayList<>();

        for (EnrichedTransaction tx : transactions) {
            // merchant MUSI być wypełniony
            if (isBlank(tx.getMerchant())) {
                errors.add(new NormalizationError(tx.getId(), "merchant", "EMPTY"));
            }

            // merchantConfidence MUSI być w zakresie 0.0-1.0
            if (tx.getMerchantConfidence() == null ||
                tx.getMerchantConfidence() < 0 ||
                tx.getMerchantConfidence() > 1) {
                errors.add(new NormalizationError(tx.getId(), "merchantConfidence", "INVALID"));
            }

            // bankCategory MUSI być wypełniony
            if (isBlank(tx.getBankCategory())) {
                errors.add(new NormalizationError(tx.getId(), "bankCategory", "EMPTY"));
            }

            // bankCategorySource MUSI być ORIGINAL lub AI_INFERRED
            if (!Set.of("ORIGINAL", "AI_INFERRED").contains(tx.getBankCategorySource())) {
                errors.add(new NormalizationError(tx.getId(), "bankCategorySource", "INVALID"));
            }
        }

        return new NormalizationResult(
            errors.isEmpty(),
            transactions.size(),
            errors.size(),
            errors
        );
    }
}
```

### Przepływ z walidacją normalizacji

```
┌─────────────────┐
│  Canonical CSV  │  (po Etapie 1 - może mieć puste pola)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ETAP 2:        │
│  Enrichment     │  AI uzupełnia merchant, bankCategory
│  Prompt         │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  NORMALIZATION CHECK                                                 │
│                                                                      │
│  ✓ merchant NOT EMPTY for all transactions?                        │
│  ✓ merchantConfidence in range 0.0-1.0?                            │
│  ✓ bankCategory NOT EMPTY for all transactions?                    │
│  ✓ bankCategorySource is ORIGINAL or AI_INFERRED?                  │
│                                                                      │
│  IF ALL PASS → Normalized CSV (gotowe do Categorization)           │
│  IF ANY FAIL → Retry enrichment / Manual review / Fallback         │
└────────┬────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────┐
│  Normalized     │  GWARANCJA: wszystkie wymagane pola wypełnione
│  Canonical CSV  │  Categorization Prompt może polegać na danych
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ETAP 3:        │  PROSTSZY prompt - nie musi obsługiwać edge cases
│  Categorization │  Może skupić się na strukturze kategorii
│  Prompt         │
└─────────────────┘
```

### Strategia obsługi błędów normalizacji

| Błąd | Strategia | Akcja |
|------|-----------|-------|
| `merchant` EMPTY | Fallback | Użyj `name` jako merchant (z confidence 0.3) |
| `merchantConfidence` INVALID | Default | Ustaw na 0.5 |
| `bankCategory` EMPTY | Fallback | Ustaw na "Inne" (z source "FALLBACK") |
| >5% transakcji z błędami | Retry | Ponów Enrichment z większym context |
| >20% transakcji z błędami | Alert | Powiadom użytkownika, wymagaj manual review |

### Przykład: Przed i po normalizacji

**PRZED normalizacją (output Etapu 1):**

| id | name | bankCategory | merchant | merchantConfidence |
|----|------|--------------|----------|--------------------|
| TXN-1 | Silva Silva, Warszawa | | | |
| TXN-2 | ZUS | | | |
| TXN-3 | BANK PEKAO S.A. | Inne | | |
| TXN-4 | BIEDRONKA 4521 | Zakupy | | |

**PO normalizacji (output Etapu 2 + validation):**

| id | name | bankCategory | bankCategorySource | merchant | merchantConfidence |
|----|------|--------------|---------------------|----------|--------------------|
| TXN-1 | Silva Silva, Warszawa | Mieszkanie | AI_INFERRED | SILVA SILVA | 0.9 |
| TXN-2 | ZUS | Składki ZUS | AI_INFERRED | ZUS | 0.95 |
| TXN-3 | BANK PEKAO S.A. | Inne | ORIGINAL | BADOO | 0.85 |
| TXN-4 | BIEDRONKA 4521 | Zakupy | ORIGINAL | BIEDRONKA | 0.95 |

**Validation result:** ✅ ALL NORMALIZED (4/4 transactions valid)

### Korzyści normalizacji dla Categorization Prompt

| Aspekt | Bez normalizacji | Z normalizacją |
|--------|------------------|----------------|
| **Grupowanie** | Po `name` (surowe, różne formaty) | Po `merchant` (znormalizowane) |
| **Wnioskowanie kategorii** | Z name+description (złożone) | Z `bankCategory` (już wyliczone) |
| **Prompt complexity** | Wysoka (obsługa edge cases) | Niska (dane gwarantowane) |
| **Debugowanie** | Trudne (nie wiadomo co puste) | Łatwe (checkpoint z walidacją) |
| **Spójność wyników** | Niska (zależy od jakości danych) | Wysoka (dane znormalizowane) |

### Kontrakt między Enrichment a Categorization

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  KONTRAKT: Enrichment → Categorization                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Enrichment GWARANTUJE:                                                         │
│  1. Każda transakcja ma merchant (NOT EMPTY, UPPERCASE)                        │
│  2. Każda transakcja ma merchantConfidence (0.0-1.0)                           │
│  3. Każda transakcja ma bankCategory (NOT EMPTY)                               │
│  4. Każda transakcja ma bankCategorySource (ORIGINAL/AI_INFERRED)              │
│                                                                                  │
│  Categorization MOŻE ZAŁOŻYĆ:                                                   │
│  - Grupowanie po merchant działa (nie ma pustych wartości)                     │
│  - bankCategory istnieje (może być "Inne" ale nie puste)                       │
│  - Dane są spójne między bankami (ten sam format)                              │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Uproszczenie Categorization Prompt dzięki normalizacji

**PRZED (bez gwarancji normalizacji):**

```
Analyze transactions and suggest categories.

Handle edge cases:
- If merchant is empty, use name field
- If bankCategory is empty, infer from name+description
- Group by merchant OR name (whichever available)
- Some transactions may have incomplete data
...
```

**PO (z gwarancją normalizacji):**

```
Analyze transactions and suggest categories.

You can assume:
- Every transaction has merchant (normalized, uppercase)
- Every transaction has bankCategory (original or AI-inferred)
- Group by merchant for pattern detection
- Focus on category STRUCTURE, not data extraction
...
```

### Metryki normalizacji

System powinien śledzić metryki normalizacji:

| Metryka | Opis | Target |
|---------|------|--------|
| `normalization_success_rate` | % transakcji znormalizowanych bez fallback | >95% |
| `merchant_extraction_rate` | % transakcji z merchant (nie fallback) | >90% |
| `bankCategory_inference_rate` | % transakcji z AI-inferred bankCategory | depends on bank |
| `enrichment_retry_rate` | % sesji wymagających retry | <5% |
| `manual_review_rate` | % sesji wymagających manual review | <1% |

---

## Appendix: Wpływ na Jakość i Stabilność Kategoryzacji

### Analiza obecnego stanu

Obecny kod w `PatternDeduplicator.java` (linia 64):

```java
// Use effectiveMerchant for grouping: merchant if available, otherwise name
String patternSource = transaction.originalData().effectiveMerchant();
```

Gdzie `effectiveMerchant()` zwraca:
```java
merchant != null && !merchant.isBlank() ? merchant : name
```

**Problem**: `merchant` jest ZAWSZE pusty (0% transakcji), więc `effectiveMerchant()` ZAWSZE zwraca surowe `name`.

### Konsekwencje dla jakości grupowania (obecne)

| Transakcja | `name` (surowe) | Grupowanie | Problem |
|------------|-----------------|------------|---------|
| Pekao karta | "BANK PEKAO S.A." | Wszystkie karty w jednej grupie | ❌ Netflix, Badoo, Spotify razem |
| Nest czynsz | "Silva Silva, Warszawa" | OK | ✅ |
| Pekao sklep | "BIEDRONKA SKLEP 4521 WARSZAWA" | Osobna grupa dla każdego sklepu | ❌ Fragmentacja |
| Pekao sklep | "BIEDRONKA SKLEP 1234 KRAKOW" | Inna grupa | ❌ Powinny być razem |

### Konsekwencje dla jakości grupowania (po Enrichment)

| Transakcja | `merchant` (po enrichment) | Grupowanie | Efekt |
|------------|---------------------------|------------|-------|
| Pekao karta Badoo | "BADOO" | Osobna grupa BADOO | ✅ |
| Pekao karta Netflix | "NETFLIX" | Osobna grupa NETFLIX | ✅ |
| Pekao sklep Warszawa | "BIEDRONKA" | Razem | ✅ |
| Pekao sklep Kraków | "BIEDRONKA" | Razem | ✅ |

### Metryki jakości - porównanie

| Metryka | Obecne | Po Enrichment | Poprawa |
|---------|--------|---------------|---------|
| **Poprawność grupowania** | ~60% | ~95% | +35% |
| **Fragmentacja** (za dużo grup) | Wysoka | Niska | ✅ |
| **Złączanie** (za mało grup) | Wysokie (BANK PEKAO) | Brak | ✅ |
| **Spójność między bankami** | Niska | Wysoka | ✅ |
| **Stabilność wyników** | Niska | Wysoka | ✅ |

### Przykład 1: Transakcje kartą przez Pekao

**OBECNE:**
```
Wszystkie te transakcje trafiają do JEDNEJ grupy "BANK PEKAO S.A.":
- Netflix 49.99 PLN
- Badoo 21.99 PLN
- Spotify 29.99 PLN
- OpenAI 23.00 USD

AI widzi: "BANK PEKAO S.A." (150 transakcji)
→ Sugeruje: "Inne" lub "Płatności kartą" (BŁĘDNE!)
```

**PO ENRICHMENT:**
```
Osobne grupy:
- NETFLIX (12 transakcji) → "Rozrywka/Streaming"
- BADOO (5 transakcji) → "Rozrywka/Aplikacje"
- SPOTIFY (12 transakcji) → "Rozrywka/Muzyka"
- OPENAI (8 transakcji) → "Technologia/AI"

AI widzi znormalizowane merchanty
→ Precyzyjne kategorie
```

### Przykład 2: Sklepy sieci handlowych

**OBECNE:**
```
Fragmentacja - osobne grupy dla każdego sklepu:
- "BIEDRONKA SKLEP 4521 WARSZAWA UL. MARSZALKOWSKA 12"
- "BIEDRONKA SKLEP 1234 KRAKOW UL. DLUGA 5"
- "BIEDRONKA 9999 MIELEC"

AI widzi: 3 osobne patterny
→ Może nie rozpoznać że to ta sama sieć
```

**PO ENRICHMENT:**
```
Jedna znormalizowana grupa:
- BIEDRONKA (wszystkie sklepy)

AI widzi: "BIEDRONKA" (45 transakcji)
→ Jednoznacznie: "Zakupy spożywcze"
```

### Przykład 3: Nest Bank vs Pekao - spójność między bankami

**OBECNE:**
```
Nest Bank:  name = "ZUS"             → grupa "ZUS"
Pekao:      name = "ZUS I ODDZIAŁ"   → grupa "ZUS I ODDZIAŁ" (osobna!)

AI może traktować jako różne kategorie
```

**PO ENRICHMENT:**
```
Nest Bank:  merchant = "ZUS" → grupa "ZUS"
Pekao:      merchant = "ZUS" → grupa "ZUS" (ta sama!)

Spójne traktowanie między bankami
```

### Wpływ na stabilność kategoryzacji

**Definicja stabilności**: ten sam input daje ten sam output przy wielokrotnym uruchomieniu.

#### Czynniki destabilizujące (obecne rozwiązanie)

| Czynnik | Dlaczego destabilizuje |
|---------|------------------------|
| Surowe `name` | AI interpretuje różnie przy każdym uruchomieniu |
| Brak `bankCategory` (Nest) | AI musi wnioskować - może dać różne wyniki |
| Złożony prompt (568 linii) | Więcej miejsca na "halucynacje" AI |
| Brak walidacji | Nie wiadomo czy dane wejściowe są kompletne |

#### Czynniki stabilizujące (proponowane rozwiązanie)

| Czynnik | Dlaczego stabilizuje |
|---------|----------------------|
| Znormalizowany `merchant` | Jednoznaczny identyfikator, brak wariantów |
| Zawsze wypełniony `bankCategory` | AI nie musi wnioskować kategorii bazowej |
| Prostszy prompt (~200 linii) | Mniej miejsca na błędy i halucynacje |
| Walidacja normalizacji | Gwarancja kompletności danych wejściowych |
| Checkpoint (Enriched CSV) | Można debugować, powtarzać, porównywać |

### Złożoność Categorization Prompt

**OBECNE:**
- Plik: `AiCategorizationPromptBuilder.java`
- Rozmiar: **568 linii**
- Odpowiedzialności: ekstrakcja merchantów, grupowanie, wnioskowanie kategorii, budowanie struktury

**PO ENRICHMENT:**
- Szacowany rozmiar: **~200 linii**
- Odpowiedzialności: tylko budowanie struktury kategorii (dane już znormalizowane)

### Podsumowanie wpływu na jakość i stabilność

| Aspekt | Obecne | Po Enrichment | Zmiana |
|--------|--------|---------------|--------|
| **Jakość kategoryzacji** | ~60-70% | ~90-95% | **+25-35%** |
| **Stabilność wyników** | Niska | Wysoka | **✅** |
| **Debugowalność** | Trudna | Łatwa (checkpointy) | **✅** |
| **Spójność między bankami** | Niska | Wysoka | **✅** |
| **Złożoność promptu** | 568 linii | ~200 linii | **-65%** |
| **Poprawność grupowania** | ~60% | ~95% | **+35%** |
| **Fragmentacja grup** | Wysoka | Niska | **✅** |

### Wniosek

**TAK** - jakość i stabilność kategoryzacji znacząco wzrosną dzięki:

1. **Znormalizowanym merchantom** → poprawne grupowanie (BIEDRONKA zamiast 10 wariantów)
2. **Wypełnionym bankCategory** → mniej wnioskowania przez AI (mniej błędów)
3. **Prostszemu promptowi** → mniej halucynacji AI
4. **Walidacji normalizacji** → gwarancja jakości danych wejściowych
5. **Rozdzieleniu odpowiedzialności** → łatwiejsze debugowanie i maintenance

Szacowana poprawa jakości kategoryzacji: **+25-35%**
Szacowana poprawa stabilności: **z "niskiej" na "wysoką"**

---

*Created: 2026-04-20*
*Updated: 2026-04-20 - dodano analizę Nest Bank + Canonical CSV + Normalizacja + Jakość/Stabilność*
*Status: DESIGN*
