# VID-156: Enrichment Cache Design

**Date:** 2026-04-22
**Status:** Design Proposal
**Related:** VID-154, VID-155

## Problem Statement

Enrichment phase takes 58-93 seconds due to Claude API calls. While in-file grouping already reduces API calls by 75-92%, users importing monthly statements will have many recurring merchants that don't need AI processing each time.

## Data Analysis

### Current In-File Deduplication (already implemented)

| Bank | Transactions | Unique Merchants | In-file dedup | AI Representatives |
|------|--------------|------------------|---------------|-------------------|
| **Nest Bank** | 402 | 33 | **92%** | 37 |
| **Pekao** | 784 | 191 | **76%** | 197 |

### Recurring Merchants Analysis

**Nest Bank - Top Recurring:**
```
 74x  Lucjan Bik Pekao          → Self-transfer
 51x  Urzad skarbowy w Mielcu   → Tax Office
 37x  ZUS                       → Social Insurance
 36x  Ikano                     → Loan payment
 35x  IFIRMA SA                 → Accounting software
 32x  Lucjan Bik mbank          → Self-transfer
 23x  MINDBOX SPÓŁKA AKCYJNA    → Salary
```

**Pekao - Top Recurring:**
```
 85x  BANK PEKAO S.A.           → Bank fees
 51x  BADOO HELP@BADOO.COM      → Subscription
 41x  ZABKA ZC525               → Grocery store
 25x  DEV LUCJAN BIK            → Self-transfer
 23x  ALLEGRO SP. Z O.O.        → E-commerce
 21x  STOWARZYSZENIE PSYCHOLOGOW→ Charity
 20x  XTREME FITNESS GYMS       → Gym membership
```

### Cross-Import Cache Potential

For monthly imports by the same user:
- **Nest Bank**: ~80% transactions are recurring merchants
- **Pekao**: ~50-60% transactions are recurring merchants

## Proposed Solution: Hybrid 3-Tier Lookup

```
┌─────────────────────────────────────────────────────────────┐
│                    ENRICHMENT REQUEST                       │
│                   (normalizedName, type)                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  TIER 1: DETERMINISTIC RULES (Pattern Matching)            │
│  ─────────────────────────────────────────────────────────  │
│  • Static patterns for well-known merchants                 │
│  • Zero API calls, instant response                         │
│  • High confidence (0.95-1.0)                               │
│  • Examples: ZABKA*, ALLEGRO*, NETFLIX*, ZUS*, UBER*        │
│                                                             │
│  Hit rate: ~30-40% of unique merchants                      │
└─────────────────────────────────────────────────────────────┘
                              │
                        (not found)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  TIER 2: USER ENRICHMENT CACHE (MongoDB)                    │
│  ─────────────────────────────────────────────────────────  │
│  • Per-user cache of previous AI enrichments                │
│  • Exact match on normalizedName                            │
│  • Confidence from original AI response                     │
│                                                             │
│  Hit rate: ~40-60% after first import                       │
└─────────────────────────────────────────────────────────────┘
                              │
                        (not found)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  TIER 3: AI ENRICHMENT (Claude API)                         │
│  ─────────────────────────────────────────────────────────  │
│  • Only for NEW, unknown merchants                          │
│  • Results saved to Tier 2 cache                            │
│  • Batched processing (50 per call)                         │
│                                                             │
│  Called for: ~10-30% of unique merchants (after warm-up)    │
└─────────────────────────────────────────────────────────────┘
```

## Tier 1: Deterministic Rules

### Data Structure

```java
public record MerchantRule(
    Pattern pattern,           // Regex pattern
    String merchant,           // Normalized merchant name
    TransactionClassification classification,
    double confidence,
    String inferredBankCategory  // Optional
) {}
```

### Initial Rule Set (Polish Market)

```java
// Grocery & Retail
Pattern.compile("(?i)ZABKA.*")           → ("Żabka", MERCHANT, 1.0)
Pattern.compile("(?i)BIEDRONKA.*")       → ("Biedronka", MERCHANT, 1.0)
Pattern.compile("(?i)LIDL.*")            → ("Lidl", MERCHANT, 1.0)
Pattern.compile("(?i)KAUFLAND.*")        → ("Kaufland", MERCHANT, 1.0)
Pattern.compile("(?i)AUCHAN.*")          → ("Auchan", MERCHANT, 1.0)
Pattern.compile("(?i)CARREFOUR.*")       → ("Carrefour", MERCHANT, 1.0)
Pattern.compile("(?i)ROSSMANN.*")        → ("Rossmann", MERCHANT, 1.0)

// E-commerce
Pattern.compile("(?i)ALLEGRO.*")         → ("Allegro", MERCHANT, 0.95)
Pattern.compile("(?i)AMAZON.*")          → ("Amazon", MERCHANT, 0.95)
Pattern.compile("(?i)ALIEXPRESS.*")      → ("AliExpress", MERCHANT, 0.95)

// Subscriptions & Digital
Pattern.compile("(?i)NETFLIX.*")         → ("Netflix", MERCHANT, 1.0)
Pattern.compile("(?i)SPOTIFY.*")         → ("Spotify", MERCHANT, 1.0)
Pattern.compile("(?i)YOUTUBE.*")         → ("YouTube", MERCHANT, 1.0)
Pattern.compile("(?i)GOOGLE.*")          → ("Google", MERCHANT, 0.9)
Pattern.compile("(?i)APPLE.*")           → ("Apple", MERCHANT, 0.9)
Pattern.compile("(?i)BADOO.*")           → ("Badoo", MERCHANT, 1.0)
Pattern.compile("(?i)TINDER.*")          → ("Tinder", MERCHANT, 1.0)

// Transport
Pattern.compile("(?i)UBER.*")            → ("Uber", MERCHANT, 0.95)
Pattern.compile("(?i)BOLT.*")            → ("Bolt", MERCHANT, 0.95)
Pattern.compile("(?i)FREENOW.*")         → ("FreeNow", MERCHANT, 0.95)
Pattern.compile("(?i)ORLEN.*")           → ("Orlen", MERCHANT, 1.0)
Pattern.compile("(?i)BP\\s.*")           → ("BP", MERCHANT, 1.0)
Pattern.compile("(?i)SHELL.*")           → ("Shell", MERCHANT, 1.0)
Pattern.compile("(?i)CIRCLE.*K.*")       → ("Circle K", MERCHANT, 1.0)

// Government & Fees
Pattern.compile("(?i)^ZUS.*")            → ("ZUS", BANK_FEE, 0.95)
Pattern.compile("(?i)URZA.*SKARBOWY.*")  → ("Urząd Skarbowy", BANK_FEE, 0.95)
Pattern.compile("(?i)BANK.*PEKAO.*")     → ("Bank Pekao", BANK_FEE, 0.9)
Pattern.compile("(?i)MBANK.*")           → ("mBank", BANK_FEE, 0.9)
Pattern.compile("(?i)ING.*BANK.*")       → ("ING Bank", BANK_FEE, 0.9)

// Telecom
Pattern.compile("(?i)ORANGE.*")          → ("Orange", MERCHANT, 0.95)
Pattern.compile("(?i)PLAY.*|P4\\s.*")    → ("Play", MERCHANT, 0.95)
Pattern.compile("(?i)PLUS.*|POLKOMTEL.*")→ ("Plus", MERCHANT, 0.95)
Pattern.compile("(?i)T-MOBILE.*")        → ("T-Mobile", MERCHANT, 0.95)

// Fitness & Health
Pattern.compile("(?i)ZDROFIT.*")         → ("Zdrofit", MERCHANT, 1.0)
Pattern.compile("(?i)CITYFIT.*")         → ("CityFit", MERCHANT, 1.0)
Pattern.compile("(?i)XTREME.*FITNESS.*") → ("Xtreme Fitness", MERCHANT, 1.0)

// Self-transfers (detection by account match or name)
Pattern.compile("(?i)PRZELEW\\s+WŁASNY.*")→ (null, SELF_TRANSFER, 0.9)
Pattern.compile("(?i)PRZELEW\\s+WEWNĘTRZNY.*")→ (null, SELF_TRANSFER, 0.9)

// Cash operations
Pattern.compile("(?i)WYPŁATA.*BANKOMAT.*")→ (null, CASH_WITHDRAWAL, 0.95)
Pattern.compile("(?i)WPŁATA.*BANKOMAT.*") → (null, CASH_DEPOSIT, 0.95)
Pattern.compile("(?i)ATM.*WITHDRAWAL.*")  → (null, CASH_WITHDRAWAL, 0.95)
```

### Implementation

```java
@Component
public class DeterministicMerchantResolver {

    private static final List<MerchantRule> RULES = List.of(
        // ... rules above
    );

    /**
     * Try to resolve merchant using deterministic rules.
     * @return Optional.empty() if no rule matches
     */
    public Optional<EnrichmentResult> resolve(String normalizedName, String transactionType) {
        for (MerchantRule rule : RULES) {
            if (rule.pattern().matcher(normalizedName).matches()) {
                return Optional.of(new EnrichmentResult(
                    rule.merchant(),
                    rule.classification(),
                    rule.confidence(),
                    rule.inferredBankCategory(),
                    "DETERMINISTIC_RULE"
                ));
            }
        }
        return Optional.empty();
    }
}
```

## Tier 2: User Enrichment Cache

### MongoDB Document

```java
@Document("enrichment_cache")
public class EnrichmentCacheEntry {

    @Id
    private String id;                              // hash(userId + normalizedName)

    @Indexed
    private String userId;                          // Owner of this cache entry

    @Indexed
    private String normalizedName;                  // "ZABKA ZC525 K.1 WARSZAWA"

    private String originalName;                    // Original transaction name

    // Enrichment result
    private String merchant;                        // "Żabka"
    private String bankCategory;                    // "Artykuły spożywcze"
    private TransactionClassification classification; // MERCHANT
    private double confidence;                      // 0.95

    // Metadata
    private String source;                          // "AI", "DETERMINISTIC", "USER_OVERRIDE"
    private int hitCount;                           // How many times used
    private Date createdAt;
    private Date lastUsedAt;

    @Indexed(expireAfterSeconds = 31536000)         // TTL: 1 year
    private Date expiresAt;
}
```

### Cache Key Strategy

```java
// Normalize name for cache lookup
String normalizeForCache(String name) {
    return name
        .toUpperCase()
        .replaceAll("\\s+", " ")           // Normalize whitespace
        .replaceAll("[^A-Z0-9ĄĆĘŁŃÓŚŹŻąćęłńóśźż ]", "")  // Keep Polish chars
        .trim();
}

// Cache key = hash(userId + normalizedName)
String cacheKey(String userId, String normalizedName) {
    return DigestUtils.sha256Hex(userId + "::" + normalizedName);
}
```

### Repository

```java
@Repository
public interface EnrichmentCacheRepository extends MongoRepository<EnrichmentCacheEntry, String> {

    Optional<EnrichmentCacheEntry> findByUserIdAndNormalizedName(String userId, String normalizedName);

    List<EnrichmentCacheEntry> findByUserIdAndNormalizedNameIn(String userId, List<String> normalizedNames);

    @Query("{ 'userId': ?0 }")
    long countByUserId(String userId);

    void deleteByUserIdAndNormalizedName(String userId, String normalizedName);
}
```

## Tier 3: AI Enrichment (existing)

Only called for merchants not found in Tier 1 or Tier 2.
Results are automatically saved to Tier 2 cache after processing.

## Integration Flow

```java
@Service
public class CachedEnrichmentService {

    private final DeterministicMerchantResolver deterministicResolver;
    private final EnrichmentCacheRepository cacheRepository;
    private final TransactionEnrichmentService aiEnrichmentService;

    public EnrichmentResult enrich(List<TransactionForEnrichment> transactions,
                                   String userId,
                                   String bankName,
                                   String language) {

        List<EnrichedTransaction> results = new ArrayList<>();
        List<TransactionForEnrichment> needsAi = new ArrayList<>();

        // Stats
        int tier1Hits = 0, tier2Hits = 0, tier3Calls = 0;

        for (TransactionForEnrichment txn : transactions) {
            String normalizedName = normalizeForCache(txn.getName());

            // TIER 1: Deterministic rules
            Optional<EnrichmentResult> tier1 = deterministicResolver.resolve(normalizedName, txn.getType());
            if (tier1.isPresent()) {
                results.add(applyEnrichment(txn, tier1.get()));
                tier1Hits++;
                continue;
            }

            // TIER 2: User cache
            Optional<EnrichmentCacheEntry> tier2 = cacheRepository
                .findByUserIdAndNormalizedName(userId, normalizedName);
            if (tier2.isPresent()) {
                results.add(applyEnrichment(txn, tier2.get()));
                updateHitCount(tier2.get());
                tier2Hits++;
                continue;
            }

            // TIER 3: Needs AI
            needsAi.add(txn);
        }

        // Process remaining with AI
        if (!needsAi.isEmpty()) {
            EnrichmentResult aiResult = aiEnrichmentService.enrich(needsAi, bankName, language);
            tier3Calls = aiResult.getAiCallCount();

            // Save to cache
            for (EnrichedTransaction enriched : aiResult.getEnrichedTransactions()) {
                saveToCacheAsync(userId, enriched);
            }

            results.addAll(aiResult.getEnrichedTransactions());
        }

        log.info("Enrichment: {} total, {} tier1 (deterministic), {} tier2 (cache), {} tier3 (AI calls)",
                transactions.size(), tier1Hits, tier2Hits, tier3Calls);

        return buildResult(results, tier1Hits, tier2Hits, tier3Calls);
    }
}
```

## Expected Performance Impact

### First Import (Cold Cache)

| Phase | Nest Bank | Pekao |
|-------|-----------|-------|
| Tier 1 (deterministic) | ~5 hits (14%) | ~60 hits (30%) |
| Tier 2 (cache) | 0 hits | 0 hits |
| Tier 3 (AI) | 32 calls | 137 calls |
| **Total Time** | ~50s | ~70s |
| **Improvement** | -14% | -25% |

### Second Import (Warm Cache)

| Phase | Nest Bank | Pekao |
|-------|-----------|-------|
| Tier 1 (deterministic) | ~5 hits | ~60 hits |
| Tier 2 (cache) | ~28 hits (85%) | ~100 hits (50%) |
| Tier 3 (AI) | ~4 calls (new merchants) | ~30 calls |
| **Total Time** | **~5s** | **~20s** |
| **Improvement** | **-91%** | **-78%** |

### Steady State (Regular Monthly Imports)

After 3+ months, cache hit rate stabilizes at:
- **Nest Bank**: 95%+ cache hit → **~3s enrichment**
- **Pekao**: 85%+ cache hit → **~10s enrichment**

## API Response Extensions

Add cache metrics to transform response:

```json
{
  "enrichmentApplied": true,
  "enrichmentTimeMs": 5200,

  "enrichmentTier1Hits": 5,        // Deterministic rules
  "enrichmentTier2Hits": 28,       // Cache hits
  "enrichmentTier3Calls": 4,       // AI calls

  "enrichmentCacheHitRate": 0.89,  // (tier1 + tier2) / total
  "enrichmentNewMerchants": 4      // Added to cache
}
```

## Implementation Plan

### Phase 1: Tier 1 - Deterministic Rules
- Create `DeterministicMerchantResolver` with initial rule set
- Integrate into enrichment flow
- Add metrics

### Phase 2: Tier 2 - User Cache
- Create `EnrichmentCacheEntry` MongoDB document
- Create repository and service
- Integrate into enrichment flow
- Add cache warm-up on AI results

### Phase 3: Optimization
- Batch cache lookups (`findByUserIdAndNormalizedNameIn`)
- Async cache writes
- Cache preloading for known users

### Phase 4: Admin Features
- Cache statistics endpoint
- Rule management (add/remove patterns)
- User cache clear endpoint

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| False positive in deterministic rules | Conservative patterns, confidence < 1.0 for ambiguous cases |
| Cache pollution with bad AI results | TTL expiration, user can override/clear |
| Cache size growth | Per-user limits, LRU eviction, 1-year TTL |
| Pattern maintenance burden | Start small, add patterns based on analytics |

## Conclusion

The 3-tier hybrid approach provides:
1. **Immediate wins** from deterministic rules (30-40% of merchants)
2. **Progressive improvement** as cache warms up
3. **Graceful fallback** to AI for unknown merchants
4. **User-specific learning** without cross-user data leakage

Expected reduction in enrichment time:
- First import: -15-25%
- Second import: -80-90%
- Steady state: -90-95%
