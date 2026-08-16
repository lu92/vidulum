# VID-151: Consolidated Backlog - AI Transaction Categorization

**Created:** 2026-04-12
**Source documents:** 6 VID-151 MD files analyzed and consolidated
**Status:** BACKLOG - Features/improvements NOT YET implemented

---

## Executive Summary

VID-151 implemented core AI-powered transaction categorization. This document consolidates all findings, unimplemented features, and improvement ideas from the VID-151 analysis period for future reference.

---

## Table of Contents

1. [Phase 2: Preserve AI Hierarchy Intent](#1-phase-2-preserve-ai-hierarchy-intent)
2. [Structure Optimizations](#2-structure-optimizations)
3. [Categorization Quality Improvements (Strategies A-E)](#3-categorization-quality-improvements)
4. [Category Ordering Support](#4-category-ordering-support)
5. [Empty Categories Problem](#5-empty-categories-problem)
6. [Priority Matrix](#6-priority-matrix)
7. [Source Documents](#7-source-documents)

---

## 1. Phase 2: Preserve AI Hierarchy Intent

**Source:** `docs/VID-151-PHASE2-IMPLEMENTATION-PLAN.md`
**Status:** NOT IMPLEMENTED (Design Only)
**Estimated effort:** ~445 LOC, 11-12 files, 8-10h

### Problem

When AI suggests hierarchy like `Food > Groceries` but it gets flattened (single child), the original intent is lost. On next import, AI doesn't know that `Groceries` was meant to be under `Food`.

### Solution

Add `intendedParentCategory` field to `PatternMapping` to preserve AI's original hierarchy suggestion.

### Implementation Steps

| Step | File | Change |
|------|------|--------|
| 1 | `PatternMapping.java` | Add `String intendedParentCategory` field |
| 2 | `PatternMappingEntity.java` | Add field + fromDomain/toDomain |
| 3 | `AcceptAiSuggestionsCommandHandler.java` | Save `parentCategory` as `intendedParentCategory` |
| 4 | `AiCategorizationResult.java` | Add `StructureOptimization` record |
| 5 | `AiCategorizationResponseParser.java` | Parse `structureOptimizations` from AI |
| 6 | `AiCategorizationService.java` | Add `getPatternIntentsForPrompt()` |
| 7 | `AiCategorizationPromptBuilder.java` | Add CACHED PATTERN INTENTS section |
| 8 | DTO/API | Return `structureOptimizations` to UI |
| 9-11 | Tests | Unit + integration tests |

### Benefits

- AI maintains hierarchy consistency across imports
- When second import adds `Restaurants` (same food domain), AI can suggest grouping both under `Food`
- User sees `structureOptimizations` suggestions in UI

---

## 2. Structure Optimizations

**Source:** `docs/features-backlog/VID-151-STRUCTURE_OPTIMIZATIONS_DESIGN.md`
**Status:** NOT IMPLEMENTED (Design Only)
**Estimated effort:** 9-10h

### Concept

AI suggests category reorganization:
- **MOVE_TO_PARENT** - Move category under a parent
- **MOVE_TO_TOP_LEVEL** - Flatten category to root
- **DELETE_EMPTY** - Remove empty category (no transactions, no children)
- **CREATE_PARENT_AND_MOVE** - Create new parent and move children under it

### New Endpoint

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/apply-structure-optimizations
```

### New Commands/Events

| Component | Description |
|-----------|-------------|
| `MoveCategoryCommand` | Move category to new parent |
| `DeleteEmptyCategoryCommand` | Delete empty category |
| `CategoryMovedEvent` | Domain event |
| `CategoryDeletedEvent` | Domain event |
| `CashFlow.moveCategory()` | Aggregate method |
| `CashFlow.deleteEmptyCategory()` | Aggregate method |

### Example Scenarios

**Scenario A:** Restore original hierarchy
```
User moved ZUS to top-level → AI suggests moving back under "Opaty obowiazkowe"
```

**Scenario B:** Flatten single-child
```
"Opaty obowiazkowe" has only ZUS → suggest flattening
```

**Scenario C:** Group related categories
```
ZUS + Urzad Skarbowy at root → suggest creating "Podatki i opaty" parent
```

---

## 3. Categorization Quality Improvements

**Source:** `docs/analysis/VID-151-IMPROVEMENT-STRATEGIES-DETAILED.md`, `docs/analysis/VID-151-UNCATEGORIZED-PROBLEM-ANALYSIS.md`
**Status:** PARTIALLY IMPLEMENTED / NEEDS VERIFICATION
**Problem:** 68% transactions (539/791) went to Uncategorized

### Strategy A: Extend TransactionNameNormalizer

**Effort:** 1h | **Impact:** ~50 transactions

Add missing patterns to `KNOWN_SINGLE_WORD_PATTERNS`:
```java
"XTREME", "ZDROFIT", "CLAUDE", "TRADINGVIEW", "JUNONA", "SHIVAGO", "MOL"
```

Add to `KNOWN_TWO_WORD_PATTERNS`:
```java
"XTREME FITNESS", "ZDROFIT OCHOTA", "FITNESS GYMS"
```

### Strategy B: Improve AI Prompt

**Effort:** 2h | **Impact:** ~300 transactions

Force AI to create pattern mappings for EVERY unique pattern:
```
CRITICAL: Create patternMapping for EVERY pattern you see.
Current problem: Only 4 mappings for 45 patterns = 91% uncategorized!

DO NOT rely on bankCategoryMappings - they are too generic.
"TRANSAKCJA KARTA PATNICZA" covers 62% of transactions!
```

### Strategy C: Validate bankCategoryMappings

**Effort:** 2h | **Impact:** Prevents bad mappings

AI created mappings where `bankCategoryName = "ZABKA"` but actual bank category is `"TRANSAKCJA KARTA PATNICZA"`.

```java
private List<BankCategorySuggestion> validateBankCategoryMappings(
        List<BankCategorySuggestion> mappings,
        Set<String> actualBankCategories) {
    return mappings.stream()
        .filter(m -> actualBankCategories.contains(m.bankCategory().toUpperCase()))
        .toList();
}
```

### Strategy D: Parse Description for Bank Intermediary

**Effort:** 3h | **Impact:** ~90 transactions

When `name = "BANK PEKAO S.A."`, extract merchant from `description`:
```
description: "ROZLICZENIE TRANSAKCJI... Badoo help@badoo.com..."
→ Extract "BADOO"
```

```java
public Optional<String> extractMerchantFromDescription(String description) {
    // Pattern 1: "WYKONANEJ: MerchantName"
    // Pattern 2: Known merchants in description
    // Pattern 3: Email domain (@badoo.com → BADOO)
}
```

### Strategy E: Fallback Description Matching in RevalidateStaging

**Effort:** 3h | **Impact:** ~80 transactions

In `RevalidateStagingCommandHandler.findMatchingPattern()`:
```java
// Priority 1: Pattern in normalizedName (current)
// Priority 2: Pattern in description (NEW - for bank intermediary)

if (isBankIntermediary(normalizedName)) {
    // Check description for pattern match
}
```

### Expected Results

| State | Uncategorized | % |
|-------|---------------|---|
| Before | 539 | 68% |
| After Phase 1 (A+B+C) | ~150 | ~19% |
| After Phase 2 (D+E) | ~80 | ~10% |

---

## 4. Category Ordering Support

**Source:** `docs/features-backlog/VID-151-category-ordering-support.md`
**Status:** TODO (Nice-to-have)
**Estimated effort:** 3-4h
**Priority:** MEDIUM

### Problem

Move category endpoint doesn't support specifying position among siblings. UI needs this for drag-and-drop.

### Solution

Add optional `position` field to move request:

```json
{
  "categoryName": "Groceries",
  "categoryType": "OUTFLOW",
  "newParentCategoryName": "Expenses",
  "position": 0  // NEW - insert at this position
}
```

### Behavior

| Case | Action |
|------|--------|
| `position` omitted | Append to end (current) |
| `position` specified | Insert at position |
| `position` > siblings count | Append to end |
| `position` < 0 | Validation error |

### Changes Required

- `MoveCategoryJson` - add `Integer position`
- `MoveCategoryCommand` - add `Integer position`
- `CategoryMovedEvent` - add `Integer newPosition`
- `MoveCategoryCommandHandler` - insert at position logic
- Modify `CATEGORY_MOVE_TO_SAME_PARENT` validation

---

## 5. Empty Categories Problem

**Source:** `docs/VID-151-EMPTY-CATEGORIES-ANALYSIS.md`
**Status:** ANALYSIS COMPLETE, FIX IN PHASE 1 (partial)

### Problem

After AI categorization with single-child flattening, `patternSuggestions` still contain old `parentCategory`, causing UI to create empty parent categories.

### Root Cause

`AiCategorizationResponseParser.flattenSingleChildCategories()` flattens structure but doesn't update `patternSuggestions`.

### Solution (Phase 1)

Update `patternSuggestions` and `bankCategorySuggestions` after flattening:
```java
FlattenResult flattenSingleChildCategories(
        SuggestedStructure structure,
        List<PatternSuggestion> patternSuggestions,
        List<BankCategorySuggestion> bankCategorySuggestions) {

    // Build map: parentName → childName (for flattened parents)
    Map<String, String> flattenedParents = new HashMap<>();

    // Flatten structure and collect info
    // ...

    // Update patternSuggestions - remove parentCategory for flattened parents
    List<PatternSuggestion> updated = patternSuggestions.stream()
        .map(ps -> flattenedParents.containsKey(ps.parentCategory())
            ? ps.withParentCategory(null)
            : ps)
        .toList();

    return new FlattenResult(structure, updated, updatedBank);
}
```

---

## 6. Priority Matrix

| Feature | Priority | Effort | Impact | Dependencies |
|---------|----------|--------|--------|--------------|
| Strategy B (Improve AI Prompt) | HIGH | 2h | ~300 txn | None |
| Strategy A (Extend Normalizer) | HIGH | 1h | ~50 txn | None |
| Strategy C (Validate Mappings) | HIGH | 2h | Prevents errors | None |
| Phase 2 (intendedParentCategory) | MEDIUM | 8-10h | Long-term consistency | Phase 1 complete |
| Strategy D (Description Parsing) | MEDIUM | 3h | ~90 txn | None |
| Strategy E (Fallback Matching) | MEDIUM | 3h | ~80 txn | None |
| Structure Optimizations | MEDIUM | 9-10h | UX improvement | Phase 2 |
| Category Ordering | LOW | 3-4h | UX improvement | VID-144 (DONE) |

### Recommended Implementation Order

**Quick Wins (5h):**
1. Strategy A - Extend normalizer patterns
2. Strategy B - Improve AI prompt
3. Strategy C - Validate bankCategoryMappings

**Deep Fixes (6h):**
4. Strategy D - Description parsing in PatternDeduplicator
5. Strategy E - Fallback in RevalidateStagingCommandHandler

**Future Iterations:**
6. Phase 2 - intendedParentCategory
7. Structure Optimizations
8. Category Ordering

---

## 7. Source Documents

| Document | Content |
|----------|---------|
| `docs/VID-151-EMPTY-CATEGORIES-ANALYSIS.md` | Deep analysis of empty categories problem, PatternMapping structure, CashFlow category operations |
| `docs/VID-151-PHASE2-IMPLEMENTATION-PLAN.md` | Detailed 11-step plan for preserving AI hierarchy intent, code samples, risk analysis |
| `docs/analysis/VID-151-IMPROVEMENT-STRATEGIES-DETAILED.md` | 5 strategies to reduce Uncategorized from 68% to ~10%, architecture diagrams |
| `docs/analysis/VID-151-UNCATEGORIZED-PROBLEM-ANALYSIS.md` | Root cause analysis, data flow diagrams, bankCategory distribution |
| `docs/features-backlog/VID-151-category-ordering-support.md` | Category position/ordering design for drag-and-drop UI |
| `docs/features-backlog/VID-151-STRUCTURE_OPTIMIZATIONS_DESIGN.md` | Structure optimization suggestions design, new commands/events |

---

## Technical Notes

### Key Code Locations

| Component | File | Purpose |
|-----------|------|---------|
| Pattern normalization | `TransactionNameNormalizer.java` | Normalizes merchant names |
| Pattern deduplication | `PatternDeduplicator.java` | Groups transactions by pattern |
| AI prompt building | `AiCategorizationPromptBuilder.java` | Builds prompt for AI |
| AI response parsing | `AiCategorizationResponseParser.java` | Parses AI JSON response |
| Accept suggestions | `AcceptAiSuggestionsCommandHandler.java` | Creates categories and mappings |
| Revalidate staging | `RevalidateStagingCommandHandler.java` | Applies mappings to transactions |
| Pattern cache | `PatternMapping.java` | Stores pattern → category mappings |
| Category mappings | `CategoryMapping.java` | Stores bankCategory → category mappings |

### CashChange Model

```
CashChange stores ONLY categoryName (String).
Does NOT store: parentCategory, categoryId, hierarchy.

→ Moving category doesn't require updating transactions!
→ Hierarchy is computed dynamically from CashFlow structure.
```

### Pattern Mapping Cache Design

```
PatternMapping does NOT store parentCategory.
Parent is looked up dynamically from CashFlow.

→ Cache is resilient to user category reorganization.
→ Phase 2 adds intendedParentCategory as HINT, not hard reference.
```

---

## Conclusion

VID-151 core functionality is implemented. This backlog contains:
- 5 concrete improvement strategies (A-E) to reduce Uncategorized from 68% to ~10%
- Phase 2 design for preserving AI hierarchy intent
- Structure Optimizations design for category reorganization suggestions
- Category ordering support for UI drag-and-drop

Estimated total effort: ~30-35h for all features.
Recommended starting point: Strategies A, B, C (quick wins, 5h total).
