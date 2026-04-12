# AI Categorization - UI Integration Guide

This document provides guidance for frontend developers integrating with the AI Categorization API.

## API Endpoints Overview

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/bank-data-adapter/transform` | POST | Transform bank CSV with AI |
| `/api/v1/bank-data-adapter/{id}/import` | POST | Import to staging |
| `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/ai-categorize` | POST | Get AI suggestions |
| `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/accept-ai` | POST | Accept suggestions |

## UI Flow Diagram

```
[1. Upload CSV]
     |
     v
[2. Transformation Progress] --> Show: bank detected, rows count
     |
     v
[3. Import to CashFlow] --> Select target CashFlow
     |
     v
[4. AI Analysis] --> Loading state with animation
     |
     v
[5. Review Suggestions] --> Interactive category tree + pattern list
     |
     v
[6. Confirm & Import] --> Progress bar for import
     |
     v
[7. Success] --> Link to CashFlow forecast
```

## Step 1: CSV Upload Component

```typescript
interface TransformRequest {
  file: File;
  bankHint?: string;
}

interface TransformResponse {
  transformationId: string;
  success: boolean;
  detectedBank: string;
  detectedLanguage: string;
  rowCount: number;
  minTransactionDate: string;
  maxTransactionDate: string;
  monthsOfData: number;
  warnings: string[];
}

// Upload handler
async function uploadCsv(file: File, bankHint?: string): Promise<TransformResponse> {
  const formData = new FormData();
  formData.append('file', file);
  if (bankHint) formData.append('bankHint', bankHint);

  const response = await fetch('/api/v1/bank-data-adapter/transform', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`
    },
    body: formData
  });

  return response.json();
}
```

### UI Component: Upload Area

```jsx
<div className="upload-area">
  <DropZone
    accept=".csv"
    onDrop={handleUpload}
    description="Upload bank CSV export"
  />
  <BankSelector
    options={['Nest Bank', 'mBank', 'ING', 'PKO BP']}
    onChange={setBankHint}
    label="Select your bank (optional)"
  />
</div>
```

## Step 2: Transformation Result Display

```jsx
<TransformationResult
  detectedBank={result.detectedBank}
  rowCount={result.rowCount}
  dateRange={{
    from: result.minTransactionDate,
    to: result.maxTransactionDate
  }}
  monthsOfData={result.monthsOfData}
  warnings={result.warnings}
/>
```

## Step 3: Import to Staging

```typescript
interface ImportRequest {
  cashFlowId: string;
}

interface ImportResponse {
  transformationId: string;
  stagingSessionId: string;
  importedRows: number;
  message: string;
}

async function importToStaging(
  transformationId: string,
  cashFlowId: string
): Promise<ImportResponse> {
  const response = await fetch(
    `/api/v1/bank-data-adapter/${transformationId}/import`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ cashFlowId })
    }
  );
  return response.json();
}
```

## Step 4: AI Categorization Request

```typescript
interface AiCategorizeResponse {
  sessionId: string;
  status: 'AI_SUGGESTIONS_READY' | 'PROCESSING' | 'ERROR';
  suggestedStructure: {
    outflow: CategorySuggestion[];
    inflow: CategorySuggestion[];
  };
  patternSuggestions: PatternSuggestion[];
  statistics: {
    totalTransactions: number;
    uniquePatterns: number;
    cachedPatternMatches: number;
    aiSuggestions: number;
    autoAcceptCount: number;
    needsReviewCount: number;
  };
}

interface CategorySuggestion {
  name: string;
  subCategories: string[];
  transactionCount: number;
  totalAmount: number;
}

interface PatternSuggestion {
  pattern: string;
  sampleTransaction: string;
  suggestedCategory: string;
  parentCategory: string;
  type: 'INFLOW' | 'OUTFLOW';
  confidence: number;
  source: 'GLOBAL' | 'USER' | 'AI';
  transactionCount: number;
  totalAmount: number;
  needsUserInput: boolean;
}

async function requestAiCategorization(
  cashFlowId: string,
  sessionId: string
): Promise<AiCategorizeResponse> {
  const response = await fetch(
    `/api/v1/bank-data-ingestion/cf=${cashFlowId}/staging/${sessionId}/ai-categorize`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      }
    }
  );
  return response.json();
}
```

## Step 5: Review Suggestions UI

### Category Tree Component

```jsx
<CategoryTree>
  <CategoryGroup type="OUTFLOW" title="Expenses">
    {suggestedStructure.outflow.map(category => (
      <CategoryItem
        key={category.name}
        name={category.name}
        subCategories={category.subCategories}
        selected={selectedCategories.includes(category.name)}
        onToggle={(name) => toggleCategory(name)}
      />
    ))}
  </CategoryGroup>

  <CategoryGroup type="INFLOW" title="Income">
    {suggestedStructure.inflow.map(category => (
      <CategoryItem
        key={category.name}
        name={category.name}
        subCategories={category.subCategories}
        selected={selectedCategories.includes(category.name)}
        onToggle={(name) => toggleCategory(name)}
      />
    ))}
  </CategoryGroup>
</CategoryTree>
```

### Pattern Suggestions Table

```jsx
<PatternTable>
  <thead>
    <tr>
      <th>Pattern</th>
      <th>Suggested Category</th>
      <th>Confidence</th>
      <th>Source</th>
      <th>Transactions</th>
      <th>Total Amount</th>
      <th>Actions</th>
    </tr>
  </thead>
  <tbody>
    {patternSuggestions.map(pattern => (
      <PatternRow
        key={pattern.pattern}
        pattern={pattern}
        onAccept={() => acceptPattern(pattern)}
        onReject={() => rejectPattern(pattern)}
        onEdit={() => editPattern(pattern)}
      />
    ))}
  </tbody>
</PatternTable>
```

### Confidence Indicator

```jsx
function ConfidenceIndicator({ confidence, source }) {
  const getColor = () => {
    if (confidence >= 90) return 'green';
    if (confidence >= 70) return 'yellow';
    return 'orange';
  };

  const getIcon = () => {
    switch (source) {
      case 'GLOBAL': return <GlobalIcon />;
      case 'USER': return <UserIcon />;
      case 'AI': return <AiIcon />;
    }
  };

  return (
    <div className="confidence-indicator">
      <ProgressBar
        value={confidence}
        max={100}
        color={getColor()}
      />
      <span>{confidence}%</span>
      {getIcon()}
    </div>
  );
}
```

## Step 6: Accept Suggestions

```typescript
interface AcceptAiRequest {
  acceptedCategories: CategoryToCreate[];
  acceptedMappings: MappingToApply[];
  saveToCache: boolean;
}

interface CategoryToCreate {
  name: string;
  parentName: string | null;
  type: 'INFLOW' | 'OUTFLOW';
}

interface MappingToApply {
  pattern: string;
  bankCategory: string;
  targetCategory: string;
  parentCategory: string | null;
  type: 'INFLOW' | 'OUTFLOW';
  confidence: number;
}

interface AcceptAiResponse {
  cashFlowId: string;
  sessionId: string;
  status: 'SUCCESS' | 'PARTIAL' | 'ERROR';
  categoriesCreated: number;
  mappingsApplied: number;
  patternsCached: number;
  warnings: string[];
  validationSummary: {
    totalTransactions: number;
    validTransactions: number;
    invalidTransactions: number;
    duplicateTransactions: number;
    readyForImport: boolean;
  };
}

async function acceptAiSuggestions(
  cashFlowId: string,
  sessionId: string,
  request: AcceptAiRequest
): Promise<AcceptAiResponse> {
  const response = await fetch(
    `/api/v1/bank-data-ingestion/cf=${cashFlowId}/staging/${sessionId}/accept-ai`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(request)
    }
  );
  return response.json();
}
```

### Helper: Convert Suggestions to Request

```typescript
function buildAcceptRequest(
  suggestedStructure: SuggestedStructure,
  patternSuggestions: PatternSuggestion[],
  selectedPatterns: Set<string>
): AcceptAiRequest {
  const acceptedCategories: CategoryToCreate[] = [];

  // Process outflow categories
  for (const cat of suggestedStructure.outflow) {
    acceptedCategories.push({
      name: cat.name,
      parentName: null,
      type: 'OUTFLOW'
    });
    for (const sub of cat.subCategories) {
      acceptedCategories.push({
        name: sub,
        parentName: cat.name,
        type: 'OUTFLOW'
      });
    }
  }

  // Process inflow categories
  for (const cat of suggestedStructure.inflow) {
    acceptedCategories.push({
      name: cat.name,
      parentName: null,
      type: 'INFLOW'
    });
    for (const sub of cat.subCategories) {
      acceptedCategories.push({
        name: sub,
        parentName: cat.name,
        type: 'INFLOW'
      });
    }
  }

  // Process patterns
  const acceptedMappings: MappingToApply[] = patternSuggestions
    .filter(p => selectedPatterns.has(p.pattern))
    .map(p => ({
      pattern: p.pattern,
      bankCategory: p.sampleTransaction,
      targetCategory: p.suggestedCategory,
      parentCategory: p.parentCategory,
      type: p.type,
      confidence: p.confidence
    }));

  return {
    acceptedCategories,
    acceptedMappings,
    saveToCache: true
  };
}
```

## Loading States

```jsx
// AI Analysis Loading
<LoadingState
  title="Analyzing Transactions"
  description="AI is categorizing your transactions..."
  icon={<AiSpinnerIcon />}
  progress={{
    current: processedPatterns,
    total: totalPatterns
  }}
/>

// Import Progress
<LoadingState
  title="Importing Transactions"
  description="Creating categories and mappings..."
  progress={{
    current: categoriesCreated,
    total: totalCategories
  }}
/>
```

## Error Handling

```typescript
function handleApiError(error: ApiError) {
  switch (error.code) {
    case 'STAGING_SESSION_NOT_FOUND':
      showError('Session expired. Please start over.');
      redirectToUpload();
      break;

    case 'AI_SERVICE_UNAVAILABLE':
      showError('AI service temporarily unavailable. Please try again.');
      break;

    case 'CATEGORY_ALREADY_EXISTS':
      showWarning('Some categories already exist and will be skipped.');
      break;

    default:
      showError('An unexpected error occurred.');
  }
}
```

## Success State

```jsx
<SuccessScreen>
  <SuccessIcon />
  <h2>Import Complete!</h2>
  <Stats>
    <StatItem label="Categories Created" value={categoriesCreated} />
    <StatItem label="Mappings Applied" value={mappingsApplied} />
    <StatItem label="Patterns Cached" value={patternsCached} />
  </Stats>
  <Actions>
    <Button
      primary
      onClick={() => navigateToCashFlow(cashFlowId)}
    >
      View CashFlow
    </Button>
    <Button
      secondary
      onClick={() => navigateToForecast(cashFlowId)}
    >
      View Forecast
    </Button>
  </Actions>
</SuccessScreen>
```

## Responsive Design

- **Desktop**: Full category tree + pattern table side by side
- **Tablet**: Stacked layout with collapsible sections
- **Mobile**: Step-by-step wizard with simplified pattern cards

## Accessibility

- Use ARIA labels for confidence indicators
- Keyboard navigation for pattern selection
- Screen reader support for category tree
- Color-blind friendly confidence colors (use shapes/icons)
