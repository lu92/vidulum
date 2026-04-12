# AI Transaction Categorization Feature

**Status:** Design Ready
**Priority:** HIGH
**Module:** `bank_data_ingestion`

---

## Overview

AI Categorization is an **optional feature** that helps users automatically categorize bank transactions using AI. It works alongside the existing manual mapping flow - users can choose either approach.

### Key Benefits

| Benefit | Description |
|---------|-------------|
| **Time Savings** | 402 transactions categorized in ~2 seconds vs 10+ minutes manual |
| **Nested Categories** | AI suggests hierarchical structure (e.g., Housing → Rent, Utilities) |
| **Learning Cache** | Subsequent imports use cached patterns (FREE, instant) |
| **Low Cost** | ~0.31 gr per import (~$0.01 USD) |

---

## User Choice: AI vs Manual

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CATEGORIZATION OPTIONS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  After staging transactions, user sees unmapped categories.                │
│  They can choose:                                                           │
│                                                                             │
│  ┌─────────────────────────────┐    ┌─────────────────────────────┐        │
│  │      🤖 AI Categorize       │    │      ✏️ Manual Mapping      │        │
│  │                             │    │                             │        │
│  │  • AI analyzes transaction  │    │  • User maps each bank     │        │
│  │    names (not just bank     │    │    category to CashFlow    │        │
│  │    categories)              │    │    category manually       │        │
│  │  • Suggests nested          │    │  • Full control            │        │
│  │    category structure       │    │  • No AI cost              │        │
│  │  • ~2 seconds               │    │  • Takes 5-15 minutes      │        │
│  │  • Cost: ~0.01 USD          │    │                             │        │
│  │                             │    │                             │        │
│  └─────────────────────────────┘    └─────────────────────────────┘        │
│                                                                             │
│  Both paths lead to the same result: mapped categories ready for import    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## API Design

### Existing Endpoints (unchanged)

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/mappings
     → Manual category mapping (existing)

POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}/revalidate
     → Revalidate after adding mappings (existing)

POST /api/v1/bank-data-ingestion/cf={cashFlowId}/import
     → Start import job (existing)
```

### New Endpoint: AI Categorize

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}/ai-categorize
Authorization: Bearer <token>

Response: 200 OK
{
  "sessionId": "abc-123",
  "status": "AI_SUGGESTIONS_READY",

  "suggestedStructure": {
    "outflow": [
      {
        "name": "Housing",
        "subCategories": ["Rent", "Utilities", "Insurance"],
        "transactionCount": 24,
        "totalAmount": 48000.00
      },
      {
        "name": "Mandatory Fees",
        "subCategories": ["Social Security (ZUS)", "Income Tax", "VAT"],
        "transactionCount": 28,
        "totalAmount": 85000.00
      }
    ],
    "inflow": [
      {
        "name": "Salary",
        "subCategories": ["Base Salary", "Bonuses"],
        "transactionCount": 12,
        "totalAmount": 180000.00
      }
    ]
  },

  "patternSuggestions": [
    {
      "pattern": "ZUS",
      "sampleTransaction": "ZUS SKŁADKI 01/2026",
      "suggestedCategory": "Social Security (ZUS)",
      "parentCategory": "Mandatory Fees",
      "type": "OUTFLOW",
      "confidence": 99,
      "source": "GLOBAL_CACHE",
      "transactionCount": 12,
      "totalAmount": 21254.04
    },
    {
      "pattern": "BIEDRONKA",
      "sampleTransaction": "BIEDRONKA WARSZAWA UL. MARSZALKOWSKA",
      "suggestedCategory": "Groceries",
      "parentCategory": "Food",
      "type": "OUTFLOW",
      "confidence": 98,
      "source": "GLOBAL_CACHE",
      "transactionCount": 15,
      "totalAmount": 2340.50
    },
    {
      "pattern": "LUCJAN BIK PEKAO",
      "sampleTransaction": "LUCJAN BIK PEKAO - zycie",
      "suggestedCategory": null,
      "parentCategory": null,
      "type": "OUTFLOW",
      "confidence": 25,
      "source": "AI",
      "transactionCount": 20,
      "totalAmount": 60000.00,
      "needsUserInput": true
    }
  ],

  "stats": {
    "totalPatterns": 45,
    "autoAccepted": 32,
    "suggested": 8,
    "needsManual": 5,
    "fromGlobalCache": 15,
    "fromUserCache": 10,
    "fromAi": 20
  },

  "cost": {
    "tokensUsed": 1250,
    "estimatedCost": "0.01 USD"
  }
}
```

### New Endpoint: Accept AI Suggestions

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}/accept-ai
Authorization: Bearer <token>

Request Body:
{
  "structureOverrides": {
    "removed": ["Entertainment"],
    "renamed": [
      { "from": "Food", "to": "Groceries & Dining" }
    ],
    "added": [
      { "name": "Investments", "type": "OUTFLOW", "parent": null }
    ]
  },

  "mappingOverrides": [
    {
      "pattern": "LUCJAN BIK PEKAO",
      "category": "Savings",
      "parentCategory": null,
      "type": "OUTFLOW"
    }
  ],

  "acceptedPatterns": ["ZUS", "BIEDRONKA", "NETFLIX", "..."],
  "rejectedPatterns": []
}

Response: 200 OK
{
  "status": "MAPPINGS_APPLIED",
  "categoriesCreated": 12,
  "mappingsCreated": 45,
  "patternsLearnedToCache": 20,
  "nextStep": {
    "action": "IMPORT",
    "url": "/api/v1/bank-data-ingestion/cf={cashFlowId}/import"
  }
}
```

---

## Processing Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ POST /ai-categorize                                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. GET STAGED TRANSACTIONS                                                 │
│     └── 402 transactions from staging session                              │
│                                                                             │
│  2. NORMALIZE TRANSACTION NAMES                                             │
│     └── "BIEDRONKA WARSZAWA UL. MARSZALKOWSKA 123" → "BIEDRONKA"           │
│     └── "ZUS SKŁADKI 01/2026 NR 123456" → "ZUS"                            │
│                                                                             │
│  3. DEDUPLICATE TO PATTERNS                                                 │
│     └── 402 transactions → 45 unique patterns                              │
│                                                                             │
│  4. CHECK GLOBAL CACHE (FREE)                                               │
│     └── Known patterns: BIEDRONKA, ZUS, NETFLIX, ALLEGRO, etc.             │
│     └── Found: 15 patterns                                                  │
│                                                                             │
│  5. CHECK USER CACHE (FREE)                                                 │
│     └── User's previous categorizations                                    │
│     └── Found: 10 patterns                                                  │
│                                                                             │
│  6. CALL AI FOR REMAINING (PAID)                                            │
│     └── 20 unknown patterns → AI                                           │
│     └── AI returns: suggestions + confidence scores                        │
│                                                                             │
│  7. RETURN COMBINED RESULTS                                                 │
│     └── suggestedStructure (nested categories)                             │
│     └── patternSuggestions (with confidence)                               │
│     └── stats (how many from cache vs AI)                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Confidence Levels

| Level | Score | Handling | Example |
|-------|-------|----------|---------|
| **AUTO_ACCEPT** | 90-100 | Applied automatically | BIEDRONKA → Groceries |
| **SUGGESTED** | 50-89 | Shown to user for confirmation | ALLEGRO → Online Shopping (?) |
| **MANUAL** | 0-49 | User must categorize | PRZELEW DO JAN KOWALSKI |

---

## Pattern Sources

| Source | Cost | Speed | Example |
|--------|------|-------|---------|
| **GLOBAL_CACHE** | FREE | <1ms | BIEDRONKA, ZUS, NETFLIX (known brands) |
| **USER_CACHE** | FREE | <1ms | User's previous categorizations |
| **AI** | ~$0.01 | 1-2s | Unknown patterns sent to GPT-4o-mini |

### Cache Learning

After user confirms AI suggestions, patterns are saved to cache:

```
User confirms: "MINDBOX" → "Salary" (parent: "Income")

Next import:
  - Same user: "MINDBOX" found in USER_CACHE (FREE)
  - If enough users confirm: added to GLOBAL_CACHE
```

---

## AI Prompt Structure

### System Prompt

```
You are a financial transaction categorizer for Polish bank statements.
Your task is to match transaction patterns to categories.

## Available Categories (if user has existing structure)

### OUTFLOW (Expenses)
- Housing
  - Rent
  - Utilities
- Transport
  - Fuel
  - Public Transit

### INFLOW (Income)
- Salary
  - Base Salary
  - Bonuses

## Instructions

1. For each pattern, suggest the most appropriate category
2. If a subcategory fits, use it (e.g., "Fuel" under "Transport")
3. Provide confidence score (0-100):
   - 90-100: Very confident (known brands, clear purpose)
   - 70-89: Confident (likely correct based on name)
   - 50-69: Uncertain (could be multiple categories)
   - Below 50: Cannot determine (generic names, personal transfers)
4. If no existing category fits well, suggest creating a new one
5. For personal transfers (e.g., "TRANSFER TO JAN KOWALSKI"), return low confidence

## Response Format

Respond ONLY with valid JSON:
{
  "mappings": [
    {
      "pattern": "BIEDRONKA",
      "category": "Groceries",
      "parentCategory": "Food",
      "confidence": 99,
      "reasoning": "Polish grocery store chain"
    }
  ],
  "suggestedNewCategories": [
    {
      "name": "Online Shopping",
      "parent": "Shopping",
      "type": "OUTFLOW",
      "forPatterns": ["ALLEGRO", "AMAZON"]
    }
  ]
}
```

### User Prompt

```
Categorize these transaction patterns:

- Pattern: "ZUS"
  Type: OUTFLOW
  Count: 12
  Sample: "ZUS SKŁADKI 01/2026"

- Pattern: "MINDBOX"
  Type: INFLOW
  Count: 3
  Sample: "MINDBOX S.A. WYNAGRODZENIE"

- Pattern: "SILVA"
  Type: OUTFLOW
  Count: 12
  Sample: "SILVA WARSZAWA CZYNSZ LOKAL 3/20"

...
```

---

## Cost Analysis

### Per Import (402 transactions)

| Step | Patterns | Cost |
|------|----------|------|
| Global Cache | 15 | FREE |
| User Cache | 10 | FREE |
| AI Call | 20 | ~0.01 USD |
| **Total** | 45 | **~0.01 USD** |

### Per User Per Year

| Import # | Cache Hit Rate | AI Cost |
|----------|----------------|---------|
| 1st | 40% | 0.31 gr |
| 2nd | 75% | 0.08 gr |
| 3rd | 85% | 0.05 gr |
| 4th+ | 90%+ | 0.02 gr |
| **Year Total** | - | **~0.60 gr** |

### At Scale

| Users | Year 1 Cost | Year 2+ Cost |
|-------|-------------|--------------|
| 1,000 | 6 PLN | 2 PLN |
| 10,000 | 60 PLN | 20 PLN |
| 100,000 | 600 PLN | 200 PLN |

---

## Package Structure

```
com.multi.vidulum.bank_data_ingestion/
├── app/
│   ├── BankDataIngestionRestController.java  ← add new endpoints
│   ├── commands/
│   │   ├── ai_categorize/                     ← NEW
│   │   │   ├── AiCategorizeCommand.java
│   │   │   └── AiCategorizeCommandHandler.java
│   │   └── accept_ai/                         ← NEW
│   │       ├── AcceptAiSuggestionsCommand.java
│   │       └── AcceptAiSuggestionsCommandHandler.java
│   └── categorization/                        ← NEW PACKAGE
│       ├── AiCategorizationService.java
│       ├── TransactionNameNormalizer.java
│       ├── PatternDeduplicator.java
│       └── AiCategorizationPromptBuilder.java
├── domain/
│   ├── PatternMapping.java                    ← NEW
│   └── AiCategorizationResult.java            ← NEW
└── infrastructure/
    ├── PatternMappingRepository.java          ← NEW
    └── GlobalPatternSeeder.java               ← NEW
```

---

## Implementation Checklist

### Phase 1: Core Infrastructure
- [ ] `PatternMapping` MongoDB document
- [ ] `PatternMappingRepository`
- [ ] `GlobalPatternSeeder` with 50+ known patterns (BIEDRONKA, ZUS, etc.)

### Phase 2: Pattern Processing
- [ ] `TransactionNameNormalizer` - normalize transaction names
- [ ] `PatternDeduplicator` - group transactions by pattern

### Phase 3: AI Integration
- [ ] `AiCategorizationPromptBuilder` - build SYSTEM + USER prompts
- [ ] `AiCategorizationService` - call AI, parse response
- [ ] Retry logic and fallbacks

### Phase 4: REST Endpoints
- [ ] `POST /staging/{sessionId}/ai-categorize`
- [ ] `POST /staging/{sessionId}/accept-ai`

### Phase 5: Testing
- [ ] Unit tests for normalizer, deduplicator
- [ ] Integration tests with mock AI
- [ ] E2E test with real CSV

---

## Related Documents

- [VID-UNIFIED-AI-IMPORT-DETAILED-DESIGN.md](./features-backlog/VID-UNIFIED-AI-IMPORT-DETAILED-DESIGN.md) - Full import flow design
- [AI_TRANSACTION_CATEGORIZATION_ANALYSIS.md](./features-backlog/AI_TRANSACTION_CATEGORIZATION_ANALYSIS.md) - Cost analysis and patterns
- [BANK_DATA_INGESTION_GUIDE.md](./BANK_DATA_INGESTION_GUIDE.md) - Existing ingestion API
