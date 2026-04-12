# Backend Task: Add Date Range to TransformResponse

## Problem Statement

### Current User Flow (Problematic)

```
1. User wants to create CashFlow from bank CSV file
2. User must GUESS startPeriod (e.g., "2024-01") BEFORE seeing CSV dates
3. User creates CashFlow with guessed startPeriod
4. User uploads CSV → AI transforms it
5. User imports to ingestion
6. ❌ ERROR: Transactions from 2023-06 are REJECTED
   (because startPeriod="2024-01" is too late)
7. User must DELETE CashFlow and start over
```

### Root Cause

The `TransformResponse` DTO does not include date range information from the transformed CSV. The user has no way to know the actual date coverage of their bank export until the import fails.

### Business Impact

- **Poor UX**: User discovers mistake only after CashFlow creation
- **Wasted effort**: Must delete and recreate CashFlow
- **Confusion**: User doesn't understand why import fails
- **Support burden**: Users contact support for "import not working"

---

## Proposed Solution

### Extend TransformResponse with Date Range Fields

Add the following fields to `TransformResponse`:

```java
public record TransformResponse(
    // Existing fields
    String transformationId,
    boolean success,
    String detectedBank,
    String detectedLanguage,
    String detectedCountry,
    int rowCount,
    List<String> warnings,
    AiImportStatus importStatus,
    String errorCode,
    String errorMessage,
    boolean cacheHit,
    long processingTimeMs,

    // NEW: Date range fields
    LocalDate minTransactionDate,      // Earliest transaction date in CSV
    LocalDate maxTransactionDate,      // Latest transaction date in CSV
    String suggestedStartPeriod,       // YearMonth string, e.g., "2023-06"
    int monthsOfData,                  // Number of distinct months covered
    List<String> monthsCovered         // List of "YYYY-MM" strings in order
) {}
```

### Why Each Field is Needed

| Field | Purpose | UI Usage |
|-------|---------|----------|
| `minTransactionDate` | Earliest date in CSV | Show to user: "Data from: June 15, 2023" |
| `maxTransactionDate` | Latest date in CSV | Show to user: "Data to: January 20, 2024" |
| `suggestedStartPeriod` | Auto-calculated from minDate | Pre-fill startPeriod in Create CashFlow form |
| `monthsOfData` | Quick count | Show: "8 months of transaction history" |
| `monthsCovered` | Detailed coverage | Detect gaps, show timeline visualization |

---

## Implementation Details

### 1. Modify AiCsvTransformationDocument

The transformation document already stores `transformedCsvContent`. After AI transformation, parse the CSV and extract date statistics:

```java
// In AiBankCsvAdapter or AiCsvTransformationService

private DateRangeStats extractDateRange(String transformedCsvContent) {
    List<BankCsvRow> rows = csvParser.parse(transformedCsvContent);

    if (rows.isEmpty()) {
        return DateRangeStats.empty();
    }

    LocalDate minDate = rows.stream()
        .map(BankCsvRow::operationDate)
        .filter(Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElse(null);

    LocalDate maxDate = rows.stream()
        .map(BankCsvRow::operationDate)
        .filter(Objects::nonNull)
        .max(LocalDate::compareTo)
        .orElse(null);

    Set<YearMonth> months = rows.stream()
        .map(BankCsvRow::operationDate)
        .filter(Objects::nonNull)
        .map(YearMonth::from)
        .collect(Collectors.toCollection(TreeSet::new));

    return new DateRangeStats(
        minDate,
        maxDate,
        minDate != null ? YearMonth.from(minDate) : null,
        months.size(),
        months.stream().map(YearMonth::toString).toList()
    );
}

public record DateRangeStats(
    LocalDate minDate,
    LocalDate maxDate,
    YearMonth suggestedStartPeriod,
    int monthsOfData,
    List<String> monthsCovered
) {
    public static DateRangeStats empty() {
        return new DateRangeStats(null, null, null, 0, List.of());
    }
}
```

### 2. Store in AiCsvTransformationDocument

Add fields to the MongoDB document:

```java
@Document(collection = "ai_csv_transformations")
public class AiCsvTransformationDocument {
    // Existing fields...

    // NEW fields
    private LocalDate minTransactionDate;
    private LocalDate maxTransactionDate;
    private String suggestedStartPeriod;  // "YYYY-MM"
    private int monthsOfData;
    private List<String> monthsCovered;
}
```

### 3. Update Transform Endpoint Response

In `AiBankCsvController.transform()`:

```java
@PostMapping("/transform")
public TransformResponse transform(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "bankHint", required = false) String bankHint,
        @RequestParam(value = "cashFlowId", required = false) String cashFlowId) {

    // ... existing transformation logic ...

    // After successful transformation, extract date range
    DateRangeStats dateStats = extractDateRange(transformation.getTransformedCsvContent());

    // Store in document
    transformation.setMinTransactionDate(dateStats.minDate());
    transformation.setMaxTransactionDate(dateStats.maxDate());
    transformation.setSuggestedStartPeriod(
        dateStats.suggestedStartPeriod() != null
            ? dateStats.suggestedStartPeriod().toString()
            : null
    );
    transformation.setMonthsOfData(dateStats.monthsOfData());
    transformation.setMonthsCovered(dateStats.monthsCovered());

    repository.save(transformation);

    return new TransformResponse(
        // existing fields...
        dateStats.minDate(),
        dateStats.maxDate(),
        transformation.getSuggestedStartPeriod(),
        dateStats.monthsOfData(),
        dateStats.monthsCovered()
    );
}
```

### 4. Update GET Transformation Endpoint

The `GET /api/v1/bank-data-adapter/{transformationId}` endpoint should also return date range:

```java
@GetMapping("/{transformationId}")
public TransformResponse getTransformation(@PathVariable String transformationId) {
    AiCsvTransformationDocument doc = repository.findById(transformationId)
        .orElseThrow(() -> new TransformationNotFoundException(transformationId));

    return new TransformResponse(
        doc.getTransformationId(),
        doc.isSuccess(),
        doc.getDetectedBank(),
        // ... other fields ...
        doc.getMinTransactionDate(),
        doc.getMaxTransactionDate(),
        doc.getSuggestedStartPeriod(),
        doc.getMonthsOfData(),
        doc.getMonthsCovered()
    );
}
```

---

## Edge Cases to Handle

### 1. Empty CSV
```java
if (rows.isEmpty()) {
    // Return null dates, 0 months
    // UI will show appropriate message
}
```

### 2. CSV with Invalid/Unparseable Dates
```java
// Filter out null dates from statistics
// Log warning if many dates couldn't be parsed
// Include in warnings list: "X transactions had unparseable dates"
```

### 3. Single Transaction
```java
// minDate == maxDate is valid
// monthsOfData = 1
// suggestedStartPeriod = that month
```

### 4. Very Old Data (e.g., 10 years)
```java
// No special handling needed
// UI can warn user about unusually old data
// warnings.add("CSV contains transactions from 10+ years ago")
```

### 5. Future Dates
```java
// Some bank exports include scheduled/pending transactions
// Include in maxDate calculation
// Add warning: "CSV contains future-dated transactions"
if (maxDate.isAfter(LocalDate.now())) {
    warnings.add("CSV contains future-dated transactions (scheduled payments)");
}
```

---

## Gap Detection (Optional Enhancement)

To help users identify missing months in their data:

```java
public record DateRangeStats(
    // ... existing fields ...
    List<String> missingMonths  // Months between min and max that have no data
) {}

private List<String> findGaps(Set<YearMonth> monthsPresent) {
    if (monthsPresent.size() < 2) return List.of();

    YearMonth min = monthsPresent.stream().min(YearMonth::compareTo).get();
    YearMonth max = monthsPresent.stream().max(YearMonth::compareTo).get();

    List<String> gaps = new ArrayList<>();
    YearMonth current = min.plusMonths(1);
    while (current.isBefore(max)) {
        if (!monthsPresent.contains(current)) {
            gaps.add(current.toString());
        }
        current = current.plusMonths(1);
    }
    return gaps;
}
```

UI can then show:
```
⚠️ Warning: No transactions found for March 2024
   Your bank export may be incomplete.
```

---

## API Response Example

### Request
```http
POST /api/v1/bank-data-adapter/transform
Content-Type: multipart/form-data

file: ing_export_2024.csv
bankHint: ING
```

### Response (with new fields)
```json
{
  "transformationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "success": true,
  "detectedBank": "ING Bank Śląski",
  "detectedLanguage": "pl",
  "detectedCountry": "PL",
  "rowCount": 147,
  "warnings": [],
  "importStatus": "PENDING",
  "errorCode": null,
  "errorMessage": null,
  "cacheHit": false,
  "processingTimeMs": 2340,

  "minTransactionDate": "2023-06-15",
  "maxTransactionDate": "2024-01-20",
  "suggestedStartPeriod": "2023-06",
  "monthsOfData": 8,
  "monthsCovered": [
    "2023-06",
    "2023-07",
    "2023-08",
    "2023-09",
    "2023-10",
    "2023-11",
    "2023-12",
    "2024-01"
  ]
}
```

---

## UI Integration

After this backend change, the UI can:

1. **Show date range after transformation:**
   ```
   ✅ Transformation complete!

   📊 147 transactions detected
   📅 Date range: June 15, 2023 → January 20, 2024
   📆 8 months of history

   Suggested start period: June 2023
   ```

2. **Pre-fill Create CashFlow form:**
   - `startPeriod` = `suggestedStartPeriod` from response
   - User can adjust if needed, but default is correct

3. **Warn about gaps:**
   ```
   ⚠️ Missing data for: March 2024
   ```

4. **Enable "Create CashFlow from CSV" flow:**
   - Single dialog that transforms CSV and creates CashFlow
   - No guessing required

---

## Testing Checklist

- [ ] Transform CSV with valid dates → returns correct date range
- [ ] Transform CSV with single transaction → minDate == maxDate
- [ ] Transform CSV with gaps in months → missingMonths populated
- [ ] Transform CSV with future dates → warning added
- [ ] Transform CSV with unparseable dates → partial stats + warning
- [ ] Transform empty CSV → null dates, 0 months
- [ ] GET transformation → returns stored date range
- [ ] Cache hit transformation → still returns date range

---

## Migration Consideration

For existing transformations in database without date fields:

```java
// Option 1: Lazy migration
@GetMapping("/{transformationId}")
public TransformResponse getTransformation(@PathVariable String transformationId) {
    AiCsvTransformationDocument doc = repository.findById(transformationId)...;

    // If date fields are null but CSV content exists, calculate on-the-fly
    if (doc.getMinTransactionDate() == null && doc.getTransformedCsvContent() != null) {
        DateRangeStats stats = extractDateRange(doc.getTransformedCsvContent());
        // Optionally save back to document
    }

    return new TransformResponse(...);
}

// Option 2: Migration script (run once)
// Iterate all documents, extract dates, save back
```

---

## Summary

| Change | File | Description |
|--------|------|-------------|
| Add fields | `TransformResponse.java` | 5 new date-related fields |
| Add fields | `AiCsvTransformationDocument.java` | Store date stats in MongoDB |
| Add method | `AiBankCsvAdapter.java` or new service | `extractDateRange()` helper |
| Update | `AiBankCsvController.transform()` | Calculate and return date stats |
| Update | `AiBankCsvController.getTransformation()` | Return stored date stats |
| Add | Unit tests | Test date extraction logic |
| Add | Integration tests | Test full transform flow with dates |

**Estimated effort:** 2-4 hours

**Dependencies:** None (self-contained change)

**Breaking changes:** None (new fields are additive)
