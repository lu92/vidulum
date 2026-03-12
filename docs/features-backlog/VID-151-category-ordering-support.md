# VID-151: Category Ordering Support

**Priorytet:** 🟡 MEDIUM
**Szacowany czas:** 3-4 godziny
**Status:** TODO
**Zależności:** VID-144 (Move Category) - DONE

---

## Kontekst

Feature request od UI developera - potrzebują wsparcia dla drag-and-drop z zachowaniem kolejności.

---

## Problem

Aktualny endpoint `POST /cash-flow/cf={cashFlowId}/category/move` pozwala przenieść kategorię do innego rodzica, ale **nie wspiera określenia pozycji** wśród "rodzeństwa" (siblings).

### Aktualny Request Body
```json
{
  "categoryName": "ChildCategory",
  "categoryType": "OUTFLOW",
  "newParentCategoryName": "NewParent"
}
```

### Brakująca funkcjonalność
Gdy użytkownik przeciąga kategorię w UI, oczekuje że pojawi się ona **dokładnie w miejscu** gdzie ją upuścił, nie tylko pod nowym rodzicem.

---

## Analiza aktualnej implementacji

### Model domenowy (`Category.java`)
```java
List<Category> subCategories;  // LinkedList - zachowuje kolejność
```

**Wniosek:** Model już wspiera kolejność (LinkedList), ale brak API do jej kontrolowania.

### Obecne zachowanie przy move
- Kategoria jest usuwana ze starej lokalizacji
- Kategoria jest dodawana do nowej listy (prawdopodobnie `add()` = na koniec)
- Kolejność nie jest kontrolowana

---

## Proponowane rozwiązania

### Opcja A: Rozszerzenie Move Endpoint (REKOMENDOWANE)

Dodać opcjonalne pole `position`:

```json
{
  "categoryName": "ChildCategory",
  "categoryType": "OUTFLOW",
  "newParentCategoryName": "NewParent",
  "position": 2
}
```

**Zachowanie:**
| Przypadek | Działanie |
|-----------|-----------|
| `position` pominięte | Dodaj na koniec (obecne zachowanie) |
| `position` podane | Wstaw na danej pozycji, przesuń pozostałe |
| `position` > ilość dzieci | Dodaj na koniec |
| `position` < 0 | Błąd walidacji |

**Zalety:**
- Jedno wywołanie API per drag-drop
- Atomowa operacja (zmiana rodzica + pozycji)
- Proste UI - brak konieczności dwóch wywołań

### Opcja B: Osobny endpoint Reorder

```
POST /cash-flow/cf={cashFlowId}/category/reorder
```

```json
{
  "parentCategoryName": "ParentCategory",
  "categoryType": "OUTFLOW",
  "orderedChildren": ["Child1", "Child2", "Child3"]
}
```

**Wady:**
- Wymaga dwóch wywołań przy move + reorder
- Większy payload
- Race conditions przy równoległych edycjach

---

## Przypadki użycia UI

### 1. Reorder w tym samym rodzicu
Użytkownik przeciąga "Groceries" nad "Utilities" (oba pod "Expenses")

```json
{
  "categoryName": "Groceries",
  "categoryType": "OUTFLOW",
  "newParentCategoryName": "Expenses",
  "position": 0
}
```

### 2. Move do innego rodzica na określoną pozycję
Użytkownik przeciąga "Subscriptions" z roota do "Entertainment" jako pierwsze dziecko

```json
{
  "categoryName": "Subscriptions",
  "categoryType": "OUTFLOW",
  "newParentCategoryName": "Entertainment",
  "position": 0
}
```

### 3. Move do roota na określoną pozycję
Użytkownik przeciąga "Rent" z "Housing" na poziom główny jako 3. element

```json
{
  "categoryName": "Rent",
  "categoryType": "OUTFLOW",
  "newParentCategoryName": null,
  "position": 2
}
```

---

## Zmiany w logice błędów

### Obecny błąd do zmodyfikowania:
`CATEGORY_MOVE_TO_SAME_PARENT` - aktualnie zwracany gdy rodzic się nie zmienia

**Nowe zachowanie:**
- Jeśli rodzic ten sam **I** pozycja ta sama → błąd (no-op)
- Jeśli rodzic ten sam **ALE** pozycja inna → dozwolone (reorder)

### Pseudokod walidacji:
```java
if (currentParent.equals(newParent)) {
    if (position == null || currentPosition == position) {
        throw new CategoryMoveToSameParentException(...);
    }
    // else: reorder allowed
}
```

---

## Plan implementacji

### 1. Zmiany w DTO (`CashFlowDto.MoveCategoryJson`)
```java
@Data
@Builder
public static class MoveCategoryJson {
    @NotBlank
    private String categoryName;
    @NotNull
    private Type categoryType;
    private String newParentCategoryName;
    @Min(0)
    private Integer position;  // NEW - nullable, 0-based
}
```

### 2. Zmiany w Command
```java
public record MoveCategoryCommand(
    CashFlowId cashFlowId,
    CategoryName categoryName,
    CategoryName newParentCategoryName,
    Type categoryType,
    Integer position  // NEW - nullable
) {}
```

### 3. Zmiany w Event
```java
public record CategoryMovedEvent(
    CashFlowId cashFlowId,
    CategoryName categoryName,
    CategoryName previousParent,
    CategoryName newParent,
    Type type,
    Integer newPosition,  // NEW - nullable
    ZonedDateTime occurredAt
) implements CashFlowEvent {}
```

### 4. Zmiany w Handler (`MoveCategoryCommandHandler`)
- Dodać logikę wstawiania na pozycję w liście
- Zmodyfikować walidację `CATEGORY_MOVE_TO_SAME_PARENT`

### 5. Zmiany w Forecast Processor (`CategoryMovedEventHandler`)
- Obsłużyć nowe pole `position` przy aktualizacji struktury

### 6. Testy
- `shouldMoveCategoryWithPosition`
- `shouldReorderCategoryWithinSameParent`
- `shouldRejectMoveToSamePositionUnderSameParent`
- `shouldAppendToEndWhenPositionExceedsSiblingCount`

---

## Estymacja

| Zadanie | Czas |
|---------|------|
| Zmiany DTO/Command/Event | 30 min |
| Handler logic | 1h |
| Walidacja position | 30 min |
| Forecast processor | 30 min |
| Testy jednostkowe | 30 min |
| Testy integracyjne | 1h |
| **RAZEM** | **4h** |

---

## Alternatywne podejście: displayOrder field

Zamiast manipulować pozycją w liście, można dodać pole `displayOrder: int` do `Category`:

```java
@Data
class Category {
    CategoryName categoryName;
    int displayOrder;  // NEW
    List<Category> subCategories;
    // ...
}
```

**Zalety:**
- Łatwiejsze zapytania (sortowanie po displayOrder)
- Brak konieczności przesuwania elementów w liście

**Wady:**
- Wymaga reindeksacji przy każdej zmianie
- Większa złożoność przy dodawaniu/usuwaniu kategorii
- Więcej pól w modelu

**Rekomendacja:** Zostać przy manipulacji pozycją w liście (LinkedList) - prostsze i wystarczające dla płytkiej hierarchii (max 1 poziom zagnieżdżenia).

---

## Uwagi końcowe

- Feature jest **nice-to-have**, nie blokuje UI MVP
- UI może tymczasowo sortować kategorie alfabetycznie po stronie klienta
- Implementacja może być odłożona do późniejszej iteracji
