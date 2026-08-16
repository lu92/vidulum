# VID-151: AI Categorization - Known Limitations and Future Solutions

## Current Limitations

### 1. Popular Merchant Chains Not Recognized

**Problem:** Popular Polish chains like ZABKA, ROSSMANN, BIEDRONKA, NETFLIX are not automatically recognized because AI creates pattern mappings only for transactions it sees, not from a predefined database.

**Impact:**
- ZABKA: 53 transactions → "Inne wydatki" (should be: Groceries)
- NETFLIX: 12 transactions → "Inne wydatki" (should be: Streaming)
- MCDONALDS: 4 transactions → "Inne wydatki" (should be: Restaurants)

**Future Solutions:**
1. **Shared Pattern Mappings Cache** - Store successful pattern mappings across users and reuse them for new users
2. **Polish Merchant Dictionary** - Pre-built database of known Polish merchants with default categories
3. **Community-driven mappings** - Allow users to share their categorization patterns

### 2. Bank's Own Categories May Be Incorrect

**Problem:** Banks like Pekao assign their own categories (e.g., "Restauracje i kawiarnie") but these may be inaccurate or too generic.

**Impact:**
- Bank may categorize ZDROFIT as "Hobby" instead of "Sport"
- Bank may categorize PARAFIA as "Kino i teatr" (clearly wrong)

**Future Solutions:**
1. **User Feedback Loop** - Allow users to correct AI categorization, learn from corrections
2. **Bank Category Confidence Score** - Weight bank categories lower when they seem inconsistent
3. **Cross-reference with merchant database** - If we know ZDROFIT is a gym, override bank's "Hobby" category

### 3. Ambiguous Transaction Names

**Problem:** Some transactions have cryptic or abbreviated names that neither AI nor humans can easily categorize.

**Examples:**
- "UL. WOLNOSCI 23B MIELEC" - Address only, no merchant name
- "LOPUSZANSKA 22 WARSZAWA" - Address only
- "XYZ123456" - Reference number only

**Future Solutions:**
1. **Geolocation enrichment** - Look up addresses to find business names
2. **User manual categorization** - Let users categorize and remember for future
3. **Ask user for context** - Interactive categorization for ambiguous patterns

### 4. Single Import Context

**Problem:** AI categorizes based on single import, doesn't learn from user's historical preferences.

**Future Solutions:**
1. **User Preference Learning** - Store user's category preferences and apply to new imports
2. **Category Usage Statistics** - If user frequently uses "Psychoterapia", suggest it for therapy-related payments
3. **Cross-import pattern learning** - Remember how user categorized similar patterns before

## Implementation Priority

| Limitation | Priority | Effort | Impact |
|------------|----------|--------|--------|
| Shared Pattern Cache | High | Medium | High |
| Polish Merchant Dictionary | High | Low | High |
| User Feedback Loop | Medium | High | Medium |
| Geolocation Enrichment | Low | High | Low |

## Related Tickets

- VID-151: AI Transaction Categorization (current)
- VID-152: Pattern Mappings Cache (planned)
- Future: User Feedback System
- Future: Merchant Database
