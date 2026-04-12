# UI Integration Prompt: AI-Powered CSV Import for Vidulum CashFlow

## Context

You are building a **web frontend** for Vidulum CashFlow application. This prompt provides everything you need to integrate the new **AI-powered CSV import feature** that automatically detects bank formats and transforms CSV files.

## UI Mockups Reference

**IMPORTANT:** Before implementing, open and study the mockups file:
```
docs/design/ai-csv-import-ui-mockups.html
```

This HTML file contains:
- Complete user journey map (5 steps)
- Web and mobile responsive designs
- Polish (PL) and English (EN) language versions
- All error scenarios with exact UI states
- Interactive tab switching between platforms and languages

To view mockups:
```bash
open docs/design/ai-csv-import-ui-mockups.html
```

---

## Feature Overview

The AI CSV Import allows users to upload bank CSV exports (from any Polish bank) and have them automatically transformed to Vidulum's standard format using AI (OpenAI/Anthropic).

### User Flow Summary

```
1. Entry Point → User sees import options (AI recommended vs Manual)
2. Upload CSV → Drag & drop or file picker + optional bank hint
3. AI Processing → Loading state with progress steps (5-15 seconds)
4. Preview → Review transformed data, warnings, download option
5. Continue → Proceed to existing category mapping flow (bank-data-ingestion)
```

---

## API Endpoints

### Base URL
```
http://localhost:9090
```

### Authentication
All endpoints require JWT Bearer token:
```
Authorization: Bearer {access_token}
```

---

### 1. Transform CSV via AI

**Endpoint:** `POST /api/v1/bank-data-adapter/transform`

**Content-Type:** `multipart/form-data`

**Request:**
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | File | Yes | CSV file from bank (max 5MB) |
| `bankHint` | String | No | Bank name hint (e.g., "Nest Bank", "mBank", "ING") |

**Example Request (JavaScript):**
```javascript
const formData = new FormData();
formData.append('file', selectedFile);
formData.append('bankHint', 'Nest Bank'); // optional

const response = await fetch('/api/v1/bank-data-adapter/transform', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`
  },
  body: formData
});
```

**Success Response (200):**
```json
{
  "transformationId": "3e024450-60ae-4620-a3a6-4332c04d7dbe",
  "success": true,
  "detectedBank": "Nest Bank",
  "detectedLanguage": "pl",
  "detectedCountry": "PL",
  "rowCount": 402,
  "warnings": [
    "3 transactions have empty descriptions"
  ],
  "importStatus": "PENDING"
}
```

**Timing Notes:**
- **Cache HIT:** ~50-200ms (bank format already known)
- **Cache MISS:** ~5-15 seconds (AI needs to analyze format)

Display loading indicator with progress steps for better UX.

---

### 2. Download Transformed CSV

**Endpoint:** `GET /api/v1/bank-data-adapter/{transformationId}/download`

**Response:** Raw CSV file with `Content-Type: text/csv`

**Example:**
```javascript
const response = await fetch(
  `/api/v1/bank-data-adapter/${transformationId}/download`,
  {
    headers: { 'Authorization': `Bearer ${token}` }
  }
);
const csvBlob = await response.blob();
```

---

### 3. Get Transformation History

**Endpoint:** `GET /api/v1/bank-data-adapter/history`

**Response (200):**
```json
{
  "transformations": [
    {
      "transformationId": "3e024450-60ae-4620-a3a6-4332c04d7dbe",
      "originalFileName": "lista_operacji_2024.csv",
      "detectedBank": "Nest Bank",
      "rowCount": 402,
      "status": "COMPLETED",
      "createdAt": "2026-03-21T10:30:00Z",
      "importStatus": "PENDING"
    }
  ]
}
```

---

### 4. Create Staging Session from Transformation

**Endpoint:** `POST /api/v1/bank-data-adapter/{transformationId}/create-staging`

**Request:**
```json
{
  "cashFlowId": "CF10000001"
}
```

**Response (200):**
```json
{
  "stagingSessionId": "0bf301da-bbb5-49f3-acd5-b9c8964e9ff2",
  "cashFlowId": "CF10000001",
  "status": "HAS_UNMAPPED_CATEGORIES",
  "summary": {
    "totalTransactions": 402,
    "validTransactions": 0,
    "invalidTransactions": 0
  },
  "unmappedCategories": [
    {"bankCategory": "Przelewy przychodzące", "count": 37, "type": "INFLOW"},
    {"bankCategory": "Przelewy wychodzące", "count": 334, "type": "OUTFLOW"}
  ]
}
```

After this, redirect user to category mapping UI (existing `bank-data-ingestion` flow).

---

## Error Handling

### Error Response Format

All errors return this structure:
```json
{
  "code": "AI_ADAPTER_EMPTY_FILE",
  "message": "The uploaded CSV file is empty or contains no data",
  "details": {
    "fileName": "test.csv",
    "fileSize": 0
  }
}
```

### Error Codes Reference

| Code | HTTP Status | User Message (PL) | User Message (EN) |
|------|-------------|-------------------|-------------------|
| `AI_ADAPTER_EMPTY_FILE` | 400 | Pusty plik | Empty file |
| `AI_ADAPTER_FILE_TOO_LARGE` | 400 | Plik za duży (max 5MB) | File too large (max 5MB) |
| `AI_ADAPTER_INVALID_FILE_TYPE` | 400 | Niewłaściwy format (wymagany CSV) | Invalid format (CSV required) |
| `AI_ADAPTER_UNRECOGNIZED_FORMAT` | 400 | Nie udało się rozpoznać formatu | Could not recognize format |
| `AI_ADAPTER_DUPLICATE_FILE` | 409 | Plik już przetworzony | File already processed |
| `AI_ADAPTER_AI_SERVICE_UNAVAILABLE` | 503 | Serwis AI niedostępny | AI service unavailable |
| `AI_ADAPTER_RATE_LIMIT_EXCEEDED` | 429 | Zbyt wiele żądań, poczekaj | Too many requests, wait |
| `AI_ADAPTER_TRANSFORMATION_NOT_FOUND` | 404 | Transformacja nie znaleziona | Transformation not found |

### Error UI States

See mockups file section `#errors` for complete error UI designs:
- Empty file state
- File too large state
- Invalid file type state
- Unrecognized format state (with retry option)
- AI service unavailable (with retry button)
- Rate limit exceeded (with countdown timer)
- Duplicate file (with link to previous transformation)

---

## UI Components to Implement

### Step 0: Entry Point (Import Options)

Location: CashFlow Dashboard when status is `SETUP`

**Components:**
- Two cards: "AI CSV Import" (recommended, highlighted) and "Manual CSV Import"
- AI option shows benefits: automatic detection, all banks supported, caching
- Help text explaining where to download CSV from bank

**Actions:**
- Click AI Import → Navigate to Upload screen
- Click Manual Import → Navigate to existing manual import

---

### Step 1: Upload Screen

**Components:**
- Progress stepper: Upload → AI Processing → Preview → Categories
- Drag & drop zone with icon
- "Browse files" button
- File size limit info (Max 5MB, CSV only)
- Bank hint buttons (ING, mBank, PKO, Nest Bank, Other)
- Supported banks list

**File Validation (client-side):**
```javascript
const MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
const ALLOWED_TYPES = ['text/csv', 'application/vnd.ms-excel'];
const ALLOWED_EXTENSIONS = ['.csv'];

function validateFile(file) {
  if (file.size > MAX_FILE_SIZE) {
    return { valid: false, error: 'FILE_TOO_LARGE' };
  }
  if (!ALLOWED_EXTENSIONS.some(ext => file.name.toLowerCase().endsWith(ext))) {
    return { valid: false, error: 'INVALID_FILE_TYPE' };
  }
  return { valid: true };
}
```

---

### Step 2: AI Processing Screen

**Components:**
- Animated brain/magic icon
- "AI is analyzing file..." message
- Progress steps with status indicators:
  1. File uploaded ✓
  2. Bank detected ✓ (show bank name)
  3. Transforming data... (spinner)
  4. Validating results (pending)
- Cache info box: "First transformation for this bank - future imports will be instant"
- Time estimation: ~10 seconds for new bank format

**Loading Animation:**
```css
.ai-pulse {
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.7; transform: scale(1.05); }
}
```

---

### Step 3: Preview Screen

**Components:**
- Success header with checkmark
- Summary cards: Transactions count, Detected bank, Processing time, Categories count
- Data preview table (first 10 rows)
- Warnings banner (yellow) if any
- "Download CSV" button
- "Continue to category mapping" button (primary)
- "Cancel and go back" link

**Table Columns:**
| Column | Display Name (PL) | Display Name (EN) |
|--------|-------------------|-------------------|
| operationDate | Data | Date |
| description | Opis | Description |
| bankCategory | Kategoria bank | Bank category |
| amount + type | Kwota | Amount |

**Amount Formatting:**
```javascript
function formatAmount(amount, type, currency) {
  const sign = type === 'OUTFLOW' ? '-' : '+';
  const color = type === 'OUTFLOW' ? 'text-red-600' : 'text-green-600';
  return { text: `${sign}${amount.toLocaleString()} ${currency}`, color };
}
```

---

### Step 4: Success / Redirect

After clicking "Continue", call `POST /create-staging` endpoint with selected CashFlowId, then redirect to existing category mapping UI.

---

## State Management Suggestions

```typescript
interface AiImportState {
  // Step tracking
  currentStep: 'upload' | 'processing' | 'preview' | 'success';

  // Upload
  selectedFile: File | null;
  bankHint: string | null;

  // Processing
  isProcessing: boolean;
  processingSteps: {
    uploaded: boolean;
    bankDetected: boolean;
    bankName: string | null;
    transforming: boolean;
    validating: boolean;
  };

  // Result
  transformationResult: TransformationResult | null;
  error: ApiError | null;

  // Preview
  previewData: BankTransaction[];
}

interface TransformationResult {
  transformationId: string;
  success: boolean;
  detectedBank: string;
  rowCount: number;
  warnings: string[];
  processingTimeMs: number;
}

interface BankTransaction {
  bankTransactionId: string;
  name: string;
  description: string;
  bankCategory: string;
  amount: number;
  currency: string;
  type: 'INFLOW' | 'OUTFLOW';
  operationDate: string;
}
```

---

## Internationalization (i18n)

The mockups include both Polish and English text. Key strings:

```json
{
  "aiImport": {
    "title": {
      "pl": "Import AI z CSV",
      "en": "AI CSV Import"
    },
    "recommended": {
      "pl": "ZALECANE",
      "en": "RECOMMENDED"
    },
    "uploadTitle": {
      "pl": "Prześlij plik CSV z banku",
      "en": "Upload CSV file from your bank"
    },
    "uploadSubtitle": {
      "pl": "AI automatycznie rozpozna format i przekształci dane",
      "en": "AI will automatically recognize the format and transform the data"
    },
    "dragDrop": {
      "pl": "Przeciągnij plik CSV tutaj",
      "en": "Drag CSV file here"
    },
    "processing": {
      "pl": "AI analizuje plik...",
      "en": "AI is analyzing the file..."
    },
    "success": {
      "pl": "Transformacja zakończona!",
      "en": "Transformation complete!"
    },
    "continue": {
      "pl": "Kontynuuj do mapowania kategorii",
      "en": "Continue to category mapping"
    }
  }
}
```

---

## Testing Checklist

### Happy Path
- [ ] Upload valid CSV file
- [ ] See AI processing animation
- [ ] Preview transformed data
- [ ] Download transformed CSV
- [ ] Continue to category mapping

### Error Cases
- [ ] Upload empty file → See empty file error
- [ ] Upload file > 5MB → See file too large error
- [ ] Upload non-CSV file → See invalid format error
- [ ] AI service down → See service unavailable with retry
- [ ] Rate limit hit → See countdown timer

### Edge Cases
- [ ] Upload same file twice → See duplicate warning with link
- [ ] Network disconnection during processing
- [ ] Very large file (5000+ transactions) with progress
- [ ] File with special characters (Polish: ąćęłńóśźż)

---

## Related Documentation

- **Full API Design:** `docs/features-backlog/2026-03-19-ai-bank-csv-adapter-design.md`
- **Manual Testing Guide:** `docs/manual-testing/CASHFLOW_IMPORT_TEST_GUIDE.md`
- **Existing Bank Data Ingestion:** `docs/BANK_DATA_INGESTION_GUIDE.md`

---

## Summary

1. Study the mockups: `docs/design/ai-csv-import-ui-mockups.html`
2. Implement 4 screens: Entry, Upload, Processing, Preview
3. Handle 7 error states with appropriate UI
4. Support both Polish and English languages
5. Connect to 4 API endpoints
6. After preview, redirect to existing category mapping flow
