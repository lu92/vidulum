# VID-151: Structure Optimizations Design

## Status: NOT IMPLEMENTED (Design Only)

Data utworzenia: 2026-04-08

---

## 1. Co to jest i kiedy się pojawia

`StructureOptimizations` to sugestie AI dotyczące reorganizacji struktury kategorii. Pojawiają się gdy:

1. **Przy kolejnym imporcie** - AI widzi z cache że kategoria była zamierzona pod jakimś parentem, ale obecnie jest gdzie indziej
2. **Single-child hierarchy** - AI wykrywa że parent ma tylko jedną podkategorię (np. `Opłaty obowiązkowe > ZUS`) i sugeruje spłaszczenie
3. **Nowy wspólny parent** - AI widzi że kilka kategorii mogłoby mieć wspólnego parenta (np. `ZUS`, `Urząd Skarbowy` → pod `Podatki i opłaty`)

---

## 2. Przykłady scenariuszy

### Scenariusz A: Przywrócenie oryginalnej hierarchii

```
Stan początkowy (po pierwszym imporcie):
OUTFLOW:
├── Opłaty obowiązkowe
│   └── ZUS (37 transakcji)

User ręcznie przeniósł ZUS na top-level:
OUTFLOW:
├── Opłaty obowiązkowe (pusta!)
├── ZUS (37 transakcji)

Przy drugim imporcie AI widzi z cache:
  - Pattern "ZUS" miał intendedParentCategory="Opłaty obowiązkowe"
  - Ale teraz ZUS jest na top-level

AI zwraca structureOptimization:
{
  "categoryName": "ZUS",
  "suggestedParent": "Opłaty obowiązkowe",
  "currentParent": null,
  "type": "OUTFLOW",
  "affectedTransactionCount": 37,
  "reason": "Restore original hierarchy - ZUS was originally under 'Opłaty obowiązkowe'"
}
```

### Scenariusz B: Spłaszczenie single-child hierarchy

```
Stan:
OUTFLOW:
├── Opłaty obowiązkowe (0 transakcji)
│   └── ZUS (37 transakcji)

AI sugeruje:
{
  "categoryName": "ZUS",
  "suggestedParent": null,  // przenieś na top-level
  "currentParent": "Opłaty obowiązkowe",
  "type": "OUTFLOW",
  "affectedTransactionCount": 37,
  "reason": "Flatten single-child hierarchy - 'Opłaty obowiązkowe' has only one child"
}

Efekt po akceptacji:
OUTFLOW:
├── ZUS (37 transakcji)
// Opłaty obowiązkowe zostaje usunięte (pusta i bez dzieci)
```

### Scenariusz C: Grupowanie pod wspólnym parentem

```
Stan:
OUTFLOW:
├── ZUS (37 transakcji)
├── Urząd Skarbowy (12 transakcji)
├── UNIQA (5 transakcji)

AI sugeruje utworzenie wspólnego parenta:
{
  "categoryName": "ZUS",
  "suggestedParent": "Podatki i opłaty",  // nowa kategoria
  "currentParent": null,
  "type": "OUTFLOW",
  "affectedTransactionCount": 37,
  "reason": "Group related categories - ZUS, Urząd Skarbowy are tax-related"
},
{
  "categoryName": "Urząd Skarbowy",
  "suggestedParent": "Podatki i opłaty",
  "currentParent": null,
  "type": "OUTFLOW",
  "affectedTransactionCount": 12,
  "reason": "Group related categories"
}
```

---

## 3. Rozszerzenie Response z AI Categorize

```json
// GET /api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/ai-categorize

{
  "sessionId": "...",
  "status": "AI_SUGGESTIONS_READY",
  "suggestedStructure": { ... },
  "patternSuggestions": [ ... ],
  "bankCategorySuggestions": [ ... ],

  "structureOptimizations": [
    {
      "id": "opt-1",
      "categoryName": "ZUS",
      "suggestedParent": "Opłaty obowiązkowe",
      "currentParent": null,
      "type": "OUTFLOW",
      "action": "MOVE_TO_PARENT",
      "affectedTransactionCount": 37,
      "reason": "Restore original hierarchy from previous import",
      "autoApply": false
    },
    {
      "id": "opt-2",
      "categoryName": "Opłaty obowiązkowe",
      "suggestedParent": null,
      "currentParent": null,
      "type": "OUTFLOW",
      "action": "DELETE_EMPTY",
      "affectedTransactionCount": 0,
      "reason": "Remove empty category after reorganization",
      "autoApply": true
    }
  ]
}
```

### Action Types

| Action | Opis |
|--------|------|
| `MOVE_TO_PARENT` | Przenieś kategorię pod wskazanego parenta |
| `MOVE_TO_TOP_LEVEL` | Przenieś kategorię na top-level (usuń z parenta) |
| `DELETE_EMPTY` | Usuń pustą kategorię (0 transakcji, 0 dzieci) |
| `CREATE_PARENT_AND_MOVE` | Utwórz nowego parenta i przenieś pod niego |

---

## 4. Nowy Endpoint: Apply Structure Optimizations

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/apply-structure-optimizations
```

### Request

```json
{
  "optimizationIds": ["opt-1", "opt-2"],
  "applyAll": false
}
```

### Response

```json
{
  "cashFlowId": "CF10000005",
  "applied": [
    {
      "optimizationId": "opt-1",
      "categoryName": "ZUS",
      "action": "MOVE_TO_PARENT",
      "newParent": "Opłaty obowiązkowe",
      "success": true
    },
    {
      "optimizationId": "opt-2",
      "categoryName": "Opłaty obowiązkowe",
      "action": "DELETE_EMPTY",
      "success": true
    }
  ],
  "failed": [],
  "newStructure": {
    "outflow": [
      {"name": "Opłaty obowiązkowe", "subCategories": ["ZUS"]},
      {"name": "Inne wydatki", "subCategories": []}
    ]
  }
}
```

---

## 5. Logika przenoszenia kategorii

### Command: MoveCategoryCommand

```java
public record MoveCategoryCommand(
    CashFlowId cashFlowId,
    CategoryName categoryToMove,
    CategoryName newParent,  // null = move to top-level
    Type type
) implements Command {}
```

### Handler: MoveCategoryCommandHandler

```java
@Component
@RequiredArgsConstructor
public class MoveCategoryCommandHandler implements CommandHandler<MoveCategoryCommand, MoveCategoryResult> {

    private final DomainCashFlowRepository cashFlowRepository;

    @Override
    public MoveCategoryResult handle(MoveCategoryCommand command) {
        CashFlow cashFlow = cashFlowRepository.findById(command.cashFlowId())
            .orElseThrow(() -> new CashFlowNotFoundException(command.cashFlowId()));

        // 1. Znajdź kategorię do przeniesienia
        Category categoryToMove = findCategory(cashFlow, command.categoryToMove(), command.type());
        if (categoryToMove == null) {
            return MoveCategoryResult.failure("Category not found: " + command.categoryToMove());
        }

        // 2. Sprawdź czy nowy parent istnieje (jeśli podany)
        Category newParent = null;
        if (command.newParent() != null) {
            newParent = findCategory(cashFlow, command.newParent(), command.type());
            if (newParent == null) {
                return MoveCategoryResult.failure("Parent category not found: " + command.newParent());
            }
        }

        // 3. Walidacja - nie można przenieść parenta pod jego własne dziecko
        if (newParent != null && isDescendantOf(newParent, categoryToMove)) {
            return MoveCategoryResult.failure("Cannot move category under its own descendant");
        }

        // 4. Usuń kategorię z obecnej lokalizacji
        Category oldParent = removeFromCurrentLocation(cashFlow, categoryToMove, command.type());

        // 5. Dodaj do nowej lokalizacji
        if (newParent != null) {
            // Dodaj jako podkategorię
            newParent.addSubCategory(categoryToMove);
        } else {
            // Dodaj na top-level
            if (command.type() == Type.OUTFLOW) {
                cashFlow.addOutflowCategory(categoryToMove);
            } else {
                cashFlow.addInflowCategory(categoryToMove);
            }
        }

        // 6. Zapisz zmiany
        cashFlowRepository.save(cashFlow);

        return MoveCategoryResult.success(
            command.categoryToMove(),
            oldParent != null ? oldParent.getName() : null,
            command.newParent()
        );
    }
}
```

### Command: DeleteEmptyCategoryCommand

```java
public record DeleteEmptyCategoryCommand(
    CashFlowId cashFlowId,
    CategoryName categoryName,
    Type type
) implements Command {}
```

```java
@Override
public DeleteCategoryResult handle(DeleteEmptyCategoryCommand command) {
    CashFlow cashFlow = cashFlowRepository.findById(command.cashFlowId())
        .orElseThrow();

    Category category = findCategory(cashFlow, command.categoryName(), command.type());

    // Walidacja - można usunąć tylko pustą kategorię bez dzieci
    if (!category.getSubCategories().isEmpty()) {
        return DeleteCategoryResult.failure("Category has subcategories");
    }

    long transactionCount = countTransactionsInCategory(cashFlow, command.categoryName());
    if (transactionCount > 0) {
        return DeleteCategoryResult.failure("Category has " + transactionCount + " transactions");
    }

    // Usuń kategorię
    removeCategory(cashFlow, category, command.type());
    cashFlowRepository.save(cashFlow);

    return DeleteCategoryResult.success(command.categoryName());
}
```

---

## 6. Modyfikacja CashFlow Aggregate

Nowe metody w `CashFlow`:

```java
public class CashFlow {

    /**
     * Moves a category to a new parent (or to top-level if newParent is null).
     * Emits CategoryMovedEvent.
     */
    public void moveCategory(CategoryName categoryName, CategoryName newParent, Type type) {
        // ... logika przenoszenia
        apply(new CategoryMovedEvent(
            this.cashFlowId,
            categoryName,
            oldParent,
            newParent,
            type,
            Instant.now()
        ));
    }

    /**
     * Deletes an empty category (no transactions, no subcategories).
     * Throws if category is not empty.
     */
    public void deleteEmptyCategory(CategoryName categoryName, Type type) {
        // ... walidacja i usunięcie
        apply(new CategoryDeletedEvent(
            this.cashFlowId,
            categoryName,
            type,
            Instant.now()
        ));
    }
}
```

---

## 7. UI Flow (przykład)

```
┌─────────────────────────────────────────────────────────────┐
│  Structure Optimization Suggestions                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ⚠️ AI detected potential improvements to your categories:  │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ 1. Move "ZUS" under "Opłaty obowiązkowe"               ││
│  │    Currently: Top-level                                 ││
│  │    Suggested: Opłaty obowiązkowe > ZUS                 ││
│  │    Reason: Restore original hierarchy from import       ││
│  │    Affects: 37 transactions                             ││
│  │    [Apply] [Skip]                                       ││
│  └─────────────────────────────────────────────────────────┘│
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ 2. Flatten "Opłaty obowiązkowe > ZUS"                  ││
│  │    Currently: Opłaty obowiązkowe > ZUS                 ││
│  │    Suggested: ZUS (top-level)                          ││
│  │    Reason: Parent has only one child                    ││
│  │    Affects: 37 transactions                             ││
│  │    [Apply] [Skip]                                       ││
│  └─────────────────────────────────────────────────────────┘│
│                                                              │
│  [Apply All]  [Skip All]  [Decide Later]                    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 8. Nowe Domain Events

```java
public record CategoryMovedEvent(
    CashFlowId cashFlowId,
    CategoryName categoryName,
    CategoryName oldParent,  // null if was top-level
    CategoryName newParent,  // null if moved to top-level
    Type type,
    Instant movedAt
) implements DomainEvent {}

public record CategoryDeletedEvent(
    CashFlowId cashFlowId,
    CategoryName categoryName,
    Type type,
    Instant deletedAt
) implements DomainEvent {}
```

---

## 9. Podsumowanie implementacji

| Element | Opis | Plik |
|---------|------|------|
| **StructureOptimization** | Record z sugestią | `AiCategorizationResult.java` |
| **Actions enum** | `MOVE_TO_PARENT`, `MOVE_TO_TOP_LEVEL`, `DELETE_EMPTY`, `CREATE_PARENT_AND_MOVE` | Nowy enum |
| **Endpoint** | `POST /cf={id}/apply-structure-optimizations` | `BankDataIngestionRestController.java` |
| **MoveCategoryCommand** | Command do przenoszenia | Nowy plik |
| **DeleteEmptyCategoryCommand** | Command do usuwania pustych | Nowy plik |
| **CategoryMovedEvent** | Event domenowy | Nowy plik |
| **CategoryDeletedEvent** | Event domenowy | Nowy plik |
| **CashFlow.moveCategory()** | Metoda w agregacie | `CashFlow.java` |
| **CashFlow.deleteEmptyCategory()** | Metoda w agregacie | `CashFlow.java` |

---

## 10. Powiązane dokumenty

- `docs/VID-151-PENDING_MAPPING_UI_INTEGRATION.md` - dokumentacja Phase 1 i Phase 2
- `docs/features-backlog/AI_CATEGORIZATION_PLAN.md` - ogólny plan AI kategoryzacji

---

## 11. Szacowany nakład pracy

| Zadanie | Estymacja |
|---------|-----------|
| Commands i Handlers | 2-3h |
| Modyfikacja CashFlow Aggregate | 2h |
| Endpoint REST | 1h |
| Testy jednostkowe | 2h |
| Testy integracyjne | 2h |
| **Razem** | **9-10h** |
