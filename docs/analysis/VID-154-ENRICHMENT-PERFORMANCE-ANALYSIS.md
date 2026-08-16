# VID-154: Enrichment Performance Analysis

**Date:** 2026-04-22
**Status:** Analysis Complete
**Related:** VID-152, VID-154

## Executive Summary

Enrichment phase adds 58-93 seconds to CSV transformation, which may impact user experience. Analysis shows the bottleneck is Claude API latency, not data volume. Grouping optimization already reduces AI calls by 75-92%.

## Test Results

### Nest Bank CSV (403 transactions)

| Metric | Value |
|--------|-------|
| Total Transactions | 403 |
| **Unique Groups** | **37** (92% reduction!) |
| AI Calls | 1 batch |
| Enrichment Time | 57.7s |
| **Time per Representative** | **1.56s** |

### Pekao CSV (791 transactions)

| Metric | Value |
|--------|-------|
| Total Transactions | 791 |
| **Unique Groups** | **197** (75% reduction) |
| AI Calls | 4 batches (parallel) |
| Enrichment Time | 93.3s |
| **Time per Batch** | ~23s |
| **Time per Representative** | ~473ms |

## Architecture Analysis

### Current Flow

```
CSV Upload
    ↓
AI Transformation (mapping rules)     ~20s
    ↓
Grouping (deduplicate by name)        <1s
    ↓
Batching (50 representatives/batch)   <1s
    ↓
AI Enrichment (parallel batches)      57-93s  ← BOTTLENECK
    ↓
Result Propagation                    <1s
    ↓
Response
```

### Grouping Effectiveness

The system groups transactions by normalized `name` field, sending only one representative per group to AI:

| Bank | Transactions | Unique Groups | Reduction |
|------|-------------|---------------|-----------|
| Nest Bank | 403 | 37 | **91%** |
| Pekao | 791 | 197 | **75%** |

This optimization is already saving significant API costs and time.

### Parallelization

- **Nest Bank**: 37 groups < 50 batch size → 1 batch (no parallelism)
- **Pekao**: 197 groups / 50 = 4 batches → processed in parallel (4 threads)

Parallel processing provides ~3-4x speedup for larger datasets.

## Root Cause Analysis

### Why is Nest Bank slower per representative?

| Metric | Nest Bank | Pekao |
|--------|-----------|-------|
| Representatives | 37 | 197 |
| Total Time | 57.7s | 93.3s |
| Time/Representative | **1.56s** | **0.47s** |

**Key insight**: Small batches don't benefit from parallelization and have similar API overhead (cold start, prompt processing) as larger batches.

### Claude API Latency Breakdown (estimated)

| Phase | Time |
|-------|------|
| API Cold Start | ~5-10s |
| Prompt Processing (input tokens) | ~5-10s |
| Response Generation (output tokens) | ~30-40s |
| Network Overhead | ~2-5s |
| **Total per API call** | **~50-60s** |

## Optimization Options

### 1. Increase Batch Size (Low Effort, Medium Impact)

**Current:** `batch-size: 50`
**Proposed:** `batch-size: 100-150`

| Scenario | Batches | Est. Time | Savings |
|----------|---------|-----------|---------|
| Pekao (197 groups) @ 50 | 4 | 93s | baseline |
| Pekao (197 groups) @ 100 | 2 | ~60s | **-35%** |
| Pekao (197 groups) @ 150 | 2 | ~55s | **-40%** |

**Risk:** Larger prompts may hit token limits or reduce quality.

### 2. Use Faster Model (Medium Effort, High Impact)

**Current:** `claude-3-5-haiku-20241022`
**Alternative:** `claude-3-5-haiku-latest` or future faster variants

Expected improvement: **30-50% faster** response generation.

### 3. Asynchronous Processing (High Effort, Best UX)

Instead of blocking HTTP request, return immediately with job ID:

```
POST /transform → 202 Accepted { jobId: "xxx" }

Background processing...

GET /transform/{jobId} → { status: "ENRICHING", progress: 75% }
GET /transform/{jobId} → { status: "COMPLETED", result: {...} }
```

**Benefits:**
- Immediate response to user
- Progress indicators possible
- Webhook/polling for completion

**Complexity:** Requires job queue, status tracking, frontend changes.

### 4. Enrichment Cache (Medium Effort, High Impact for Repeat Users)

Cache enrichment results by transaction name hash:

```java
@Cacheable(value = "enrichment", key = "#normalizedName")
EnrichedTransaction getCachedEnrichment(String normalizedName);
```

| Scenario | First Import | Second Import |
|----------|--------------|---------------|
| Same CSV | 60s | ~5s |
| Similar CSV (80% overlap) | 60s | ~15s |

**Best for:** Users importing monthly statements with recurring transactions.

### 5. Streaming Response (High Effort, Perceived Speed)

Use Server-Sent Events to stream partial results:

```
POST /transform → SSE stream
  event: progress { phase: "TRANSFORMATION", progress: 100 }
  event: progress { phase: "ENRICHMENT", progress: 25 }
  event: progress { phase: "ENRICHMENT", progress: 50 }
  event: complete { result: {...} }
```

**Benefits:** User sees progress, feels faster.

## Recommendations

### Short-term (Quick Wins)

1. **Increase batch-size to 100** - Simple config change, ~35% improvement
2. **Add progress logging** - Better visibility for debugging

### Medium-term (Next Sprint)

3. **Implement enrichment cache** - Major speedup for repeat users
4. **Evaluate newer Claude models** - Check for faster alternatives

### Long-term (Future)

5. **Async processing with job queue** - Best UX, requires significant work
6. **Consider local model for simple enrichments** - Reduce API dependency

## Comparison: Before vs After Optimization

| Scenario | Current | After Batch=100 | After +Cache |
|----------|---------|-----------------|--------------|
| First Nest Import | 58s | ~45s | ~45s |
| Second Nest Import | 58s | ~45s | **~5s** |
| First Pekao Import | 93s | ~60s | ~60s |
| Second Pekao Import | 93s | ~60s | **~10s** |

## Metrics Added (VID-154)

New metrics in API response help diagnose performance:

```json
{
  "enrichmentApplied": true,
  "merchantsExtracted": 375,
  "bankCategoriesInferred": 285,
  "enrichmentTimeMs": 57689,
  "enrichmentAiCalls": 1,

  "classificationMerchantCount": 259,
  "classificationBankFeeCount": 26,
  "classificationCashWithdrawalCount": 0,
  "classificationSelfTransferCount": 2,
  "classificationUnknownCount": 116,

  "highConfidenceCount": 258,
  "mediumConfidenceCount": 1,
  "lowConfidenceCount": 116
}
```

These metrics enable:
- Monitoring enrichment quality without downloading CSV
- Identifying problematic imports (high UNKNOWN count)
- Tracking AI efficiency (calls vs transactions)

## Conclusion

The enrichment phase is inherently slow due to AI API latency, but:
1. Grouping already provides 75-92% reduction in AI calls
2. Parallelization helps for larger datasets
3. Simple optimizations (batch size, caching) can provide 35-80% improvement
4. Async processing would provide the best UX but requires significant work

**Recommended next step:** Increase `batch-size` to 100 and implement enrichment cache.
