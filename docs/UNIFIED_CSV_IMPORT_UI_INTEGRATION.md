# Unified CSV Import - UI Integration Guide

This document provides instructions for frontend developers to integrate with the new **Unified CSV Import** endpoint.

## Overview

The Unified CSV Import endpoint simplifies CSV upload by auto-detecting the format and returning all information needed for CashFlow creation in a single call.

**Single Endpoint:**
```
POST /api/v1/csv-import/upload
```

## Detection Results

The endpoint returns `detectionResult` indicating how the file was processed:

| detectionResult | Description | Processing Time | Cost |
|----------------|-------------|-----------------|------|
| `CANONICAL` | Vidulum format CSV (pre-transformed) | ~2ms | Free |
| `CACHED` | Known bank format (cached rules) | ~10-50ms | Free |
| `AI_TRANSFORMED` | New bank format (AI processing) | 5-25 seconds | ~$0.01-0.02 |

## Request

### Endpoint
```
POST /api/v1/csv-import/upload
Content-Type: multipart/form-data
Authorization: Bearer <jwt_token>
```

### Parameters
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | File | Yes | CSV file (max 5MB) |
| `bankHint` | String | No | Optional bank name hint (e.g., "Nest Bank") |

### Example Request (JavaScript)
```javascript
async function uploadCSV(file, bankHint = null) {
  const formData = new FormData();
  formData.append('file', file);
  if (bankHint) {
    formData.append('bankHint', bankHint);
  }

  const response = await fetch('/api/v1/csv-import/upload', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${accessToken}`
    },
    body: formData
  });

  return await response.json();
}
```

## Response

### Success Response (200 OK)
```json
{
  "transformationId": "891e699b-2120-42bc-9ad5-5ab692854faa",
  "success": true,

  "detectionResult": "CANONICAL",    // CANONICAL | CACHED | AI_TRANSFORMED
  "fromCache": false,                // true if cached mapping rules were used
  "processingTimeMs": 2,             // Processing time in milliseconds

  "detectedBank": "Vidulum Format",  // Bank name for CashFlow form
  "detectedCurrency": "PLN",         // Currency for CashFlow form
  "detectedLanguage": "en",
  "detectedCountry": "XX",

  "rowCount": 4,                     // Number of transactions
  "warnings": [],                    // Any warnings from processing

  "minTransactionDate": "2026-03-01",     // Earliest transaction (YYYY-MM-DD)
  "maxTransactionDate": "2026-03-15",     // Latest transaction (YYYY-MM-DD)
  "suggestedStartPeriod": "2026-03",      // CRITICAL: Use for CashFlow startPeriod!
  "monthsOfData": 1,                      // Number of distinct months
  "monthsCovered": ["2026-03"],           // List of months with transactions

  "bankCategories": [                     // Categories for mapping preview
    {
      "name": "Przychody",
      "count": 1,
      "type": "INFLOW"
    },
    {
      "name": "Mieszkanie",
      "count": 1,
      "type": "OUTFLOW"
    }
  ],

  "importStatus": "PENDING",         // PENDING | IMPORTED
  "errorCode": null,
  "errorMessage": null
}
```

### Error Responses

#### 400 Bad Request - Empty/Invalid File
```json
{
  "timestamp": "2026-03-26T22:50:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "File is empty or has no valid CSV content"
}
```

#### 409 Conflict - Duplicate File
```json
{
  "timestamp": "2026-03-26T22:50:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "This file has already been uploaded",
  "details": {
    "existingTransformationId": "previous-id"
  }
}
```

#### 413 Payload Too Large - File Too Big
```json
{
  "timestamp": "2026-03-26T22:50:00Z",
  "status": 413,
  "error": "Payload Too Large",
  "message": "File size exceeds maximum allowed size of 5MB"
}
```

## UI Flow Recommendations

### Step 1: File Selection
- Accept `.csv` files only
- Show file size limit (5MB)
- Optional: Show bank selector dropdown to provide `bankHint`

### Step 2: Upload & Processing
Based on `detectionResult`, show appropriate feedback:

```javascript
function handleUploadResponse(response) {
  switch (response.detectionResult) {
    case 'CANONICAL':
      // Instant - no loading needed
      showSuccess('File processed instantly!');
      break;

    case 'CACHED':
      // Instant - show bank recognition
      showSuccess(`${response.detectedBank} format recognized!`);
      break;

    case 'AI_TRANSFORMED':
      // Show processing animation during upload
      // processingTimeMs will be 5000-25000
      showSuccess(`AI processed ${response.detectedBank} format`);
      break;
  }
}
```

### Step 3: Pre-fill CashFlow Form
Use response fields to pre-populate the CashFlow creation form:

```javascript
// Pre-fill CashFlow form
cashFlowForm = {
  name: response.detectedBank + ' Account',
  currency: response.detectedCurrency,
  startPeriod: response.suggestedStartPeriod,  // CRITICAL!
  // User can adjust if needed
};
```

### Step 4: Category Mapping Preview
Show `bankCategories` to let user preview/map categories before import:

```javascript
// Show category mapping interface
response.bankCategories.forEach(cat => {
  showCategoryMapping({
    bankCategoryName: cat.name,
    transactionCount: cat.count,
    suggestedType: cat.type  // INFLOW or OUTFLOW
  });
});
```

## Important Fields for CashFlow Creation

| Field | Usage | Required |
|-------|-------|----------|
| `suggestedStartPeriod` | Use as `startPeriod` for CashFlow | Yes |
| `detectedCurrency` | Use as `currency` for CashFlow | Yes |
| `detectedBank` | Use for CashFlow `name` (optional) | No |
| `transformationId` | Store for later import to CashFlow | Yes |
| `bankCategories` | Show for category mapping | Yes |

## Mobile Considerations

### Compact Response Display
For mobile, show condensed info:
- Bank name + Currency
- Transaction count
- Date range
- Category count with expand option

### Touch-Friendly Category Mapping
- Use swipe gestures for category type (INFLOW/OUTFLOW)
- Group similar categories automatically

## Example Integration Flow

```javascript
// 1. Upload CSV
const uploadResult = await uploadCSV(selectedFile, 'Nest Bank');

if (!uploadResult.success) {
  showError(uploadResult.errorMessage);
  return;
}

// 2. Show upload summary
showUploadSummary({
  bank: uploadResult.detectedBank,
  currency: uploadResult.detectedCurrency,
  transactions: uploadResult.rowCount,
  dateRange: `${uploadResult.minTransactionDate} - ${uploadResult.maxTransactionDate}`,
  months: uploadResult.monthsOfData,
  processingTime: uploadResult.processingTimeMs,
  detectionType: uploadResult.detectionResult
});

// 3. Pre-fill CashFlow form
preFillCashFlowForm({
  startPeriod: uploadResult.suggestedStartPeriod,
  currency: uploadResult.detectedCurrency,
  name: uploadResult.detectedBank + ' Account'
});

// 4. Show category mapping (optional, can be done later)
showCategoryMappingPreview(uploadResult.bankCategories);

// 5. Save transformationId for later import
sessionStorage.setItem('pendingTransformationId', uploadResult.transformationId);
```

## Testing the Endpoint

### cURL Examples

**Upload Nest Bank CSV:**
```bash
curl -X POST "http://localhost:9090/api/v1/csv-import/upload" \
  -H "Authorization: Bearer <token>" \
  -F "file=@lista_operacji.csv" \
  -F "bankHint=Nest Bank"
```

**Upload Canonical CSV:**
```bash
curl -X POST "http://localhost:9090/api/v1/csv-import/upload" \
  -H "Authorization: Bearer <token>" \
  -F "file=@canonical.csv"
```

## Related Documentation

- [UI Mockups](/docs/design/unified-csv-import-ui-mockups.html)
- [Bank Data Ingestion Guide](/docs/BANK_DATA_INGESTION_GUIDE.md)
- [Endpoints Reference](/docs/UNIFIED_CSV_IMPORT_ENDPOINTS.md)

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-03-26 | Initial release with CANONICAL, CACHED, AI_TRANSFORMED detection |
