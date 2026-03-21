# AI Capabilities Backlog - Vidulum

> Kompletne podsumowanie wszystkich możliwości AI, które mogą zostać zaimplementowane w Vidulum.
> Data utworzenia: 2026-03-21

---

## Spis treści

1. [Status implementacji](#status-implementacji)
2. [Szczegółowy opis funkcjonalności](#szczegółowy-opis-funkcjonalności)
3. [Koszty i modele AI](#koszty-i-modele-ai)
4. [Bezpieczeństwo i prywatność](#bezpieczeństwo-i-prywatność)
5. [Roadmapa implementacji](#roadmapa-implementacji)
6. [Źródła dokumentacji](#źródła-dokumentacji)

---

## Status implementacji

| # | Funkcjonalność | Status | Priorytet | Koszt/100 transakcji |
|---|----------------|--------|-----------|---------------------|
| 1 | **AI Bank CSV Adapter** | ✅ ZAIMPLEMENTOWANE | WYSOKI | ~1-5 gr (cache) |
| 2 | **Automatyczna kategoryzacja transakcji** | 📋 DESIGN READY | WYSOKI | ~1-8 gr |
| 3 | **AI Sugestie reguł cyklicznych** | 📋 DESIGN READY | WYSOKI | ~2-5 gr |
| 4 | **Analiza wzorców wydatków** | 💡 KONCEPT | ŚREDNI | ~5-15 gr |
| 5 | **Inteligentne budżetowanie** | 💡 KONCEPT | ŚREDNI | ~10-20 gr |
| 6 | **Predykcja cash flow** | 💡 KONCEPT | ŚREDNI | ~5-10 gr |
| 7 | **Wykrywanie anomalii** | 💡 KONCEPT | NISKI | ~2-5 gr |
| 8 | **Chatbot finansowy** | 💡 KONCEPT | NISKI | ~5-20 gr/pytanie |
| 9 | **OCR paragonów** | 💡 KONCEPT | NISKI | ~10-30 gr/paragon |
| 10 | **Inteligentne cele oszczędnościowe** | 💡 KONCEPT | NISKI | ~5-10 gr |
| 11 | **Smart tagging** | 💡 KONCEPT | NISKI | ~1-3 gr |
| 12 | **Benchmarki wydatków** | 💡 KONCEPT | NISKI | ~2-5 gr |
| 13 | **Inteligentna rekoncyliacja** | 📋 DESIGN READY | ŚREDNI | Zewnętrzne API |

**Legenda:**
- ✅ ZAIMPLEMENTOWANE - Funkcjonalność jest gotowa i działa w produkcji
- 📋 DESIGN READY - Szczegółowy projekt jest gotowy, można implementować
- 💡 KONCEPT - Pomysł opisany, wymaga szczegółowego projektu

---

## Szczegółowy opis funkcjonalności

### 1. AI Bank CSV Adapter ✅ ZAIMPLEMENTOWANE

**Status:** Działający w produkcji

**Opis:**
System automatycznego rozpoznawania i transformacji plików CSV z różnych banków do formatu kanonicznego Vidulum.

**Komponenty:**
- `AiBankCsvTransformService` - główny serwis transformacji
- `CsvAnonymizer` - anonimizacja danych przed wysłaniem do AI
- `LocalCsvTransformer` - aplikowanie reguł lokalnie (bez AI)
- `MappingRulesCacheService` - cache reguł per bank
- `AiPromptBuilder` - budowanie promptów dla AI

**Architektura Cache-First:**
```
1. Upload CSV → Detect bank format
2. Check cache for MappingRules
3. Cache HIT → LocalCsvTransformer (9-15ms)
   Cache MISS → CsvAnonymizer → AI Call (~10s) → Save to cache
4. Return transformed CSV
```

**Wydajność:**
- Cache HIT: ~9-15ms
- Cache MISS: ~10-15 sekund (AI call)
- Oszczędność: ~99.9% po pierwszym imporcie dla danego banku

**Wspierane banki (automatyczne wykrywanie):**
- Nest Bank
- mBank
- PKO BP
- ING
- Santander
- Inne (generyczne wykrywanie kolumn)

---

### 2. Automatyczna kategoryzacja transakcji 📋 DESIGN READY

**Status:** Szczegółowy design gotowy w `AI_CATEGORIZATION_PLAN.md`

**Problem:**
Obecny system mapuje kategorie na poziomie `bankCategory` (np. "Płatności kartą"), ale nie analizuje opisu transakcji. Transakcja "BIEDRONKA 1234" w kategorii "Płatności kartą" powinna trafić do "Zakupy spożywcze".

**Rozwiązanie - Flow:**
```
1. Użytkownik dodaje transakcję/import CSV
2. System sprawdza czy istnieje CategoryMapping dla opisu
3. Brak mappingu → AI kategoryzuje z confidence score
4. confidence >= 90% → auto-przypisanie
5. confidence 50-89% → sugestia dla użytkownika
6. confidence < 50% → fallback do bankCategory
7. Użytkownik akceptuje → tworzony CategoryMapping (auto-learning)
```

**Kluczowe komponenty:**
```java
public interface LlmProvider {
    CategorizationResult categorize(List<Transaction> transactions,
                                    List<Category> availableCategories);
}

public record CategorizationResult(
    String categoryId,
    String subcategoryId,
    double confidence,
    String reasoning
) {}
```

**Batch processing:**
- 20 transakcji per request (optymalizacja kosztów)
- ~7x taniej niż pojedyncze requesty

**Progi confidence:**
| Próg | Akcja |
|------|-------|
| >= 90% | Auto-przypisanie bez pytania |
| 50-89% | Sugestia z możliwością akceptacji/odrzucenia |
| < 50% | Fallback do bankCategory lub "Inne" |

**Auto-learning:**
Każda akceptowana sugestia tworzy `CategoryMapping`:
```java
CategoryMapping {
    pattern: "BIEDRONKA.*",
    categoryId: "groceries",
    source: "AI_LEARNED",
    confidence: 0.95,
    usageCount: 47
}
```

**Koszty:**
| Model | Koszt/100 transakcji |
|-------|---------------------|
| GPT-4o-mini | ~1-2 gr |
| Claude Haiku | ~2-3 gr |
| GPT-4o | ~5-8 gr |

---

### 3. AI Sugestie reguł cyklicznych 📋 DESIGN READY

**Status:** Szczegółowy design w `2026-02-25-recurring-rules-ai-suggestions-monitoring.md`

**Opis:**
System wykrywa wzorce w transakcjach i sugeruje utworzenie reguł cyklicznych (np. "Netflix co miesiąc 49 PLN").

**Algorytm wykrywania wzorców:**
```java
RecurringPatternDetector {
    MIN_OCCURRENCES = 3          // Minimum powtórzeń
    DATE_TOLERANCE_DAYS = 5       // Tolerancja dni (±5)
    AMOUNT_TOLERANCE_PERCENT = 10 // Tolerancja kwoty (±10%)
}
```

**Typy wzorców:**
| Typ | Opis | Przykład |
|-----|------|----------|
| `DAILY` | Codziennie | Kawa w Starbucks |
| `WEEKLY` | Co tydzień | Zakupy weekendowe |
| `MONTHLY` | Co miesiąc | Netflix, Spotify |
| `YEARLY` | Co rok | Ubezpieczenie samochodu |

**Źródła wzorców:**
1. **Temporal patterns** - regularne daty (np. 1-5 każdego miesiąca)
2. **Counterparty patterns** - ten sam odbiorca
3. **Category patterns** - ta sama kategoria
4. **Amount clustering** - podobne kwoty

**Endpoint API:**
```
GET /api/v1/recurring-rules/cf={cfId}/suggestions

Response:
{
  "suggestions": [
    {
      "pattern": "Netflix subscription",
      "confidence": 0.94,
      "frequency": "MONTHLY",
      "expectedAmount": {"amount": 49.00, "currency": "PLN"},
      "expectedDay": 15,
      "matchedTransactions": 6,
      "suggestedRule": {
        "name": "Netflix",
        "category": "Entertainment",
        "type": "OUTFLOW"
      }
    }
  ]
}
```

**Monitoring:**
Dashboard z statystykami:
- Liczba wykrytych wzorców
- Accuracy rate (ile sugestii zaakceptowanych)
- False positive rate
- Pokrycie transakcji regułami

---

### 4. Analiza wzorców wydatków 💡 KONCEPT

**Opis:**
AI analizuje historię transakcji i wykrywa nietypowe wzorce, trendy, oraz dostarcza insights.

**Możliwości:**
- "Wydajesz 40% więcej na jedzenie w weekendy"
- "Twoje wydatki na transport rosną o 15% rocznie"
- "Najwięcej wydajesz w piątki"
- "Subskrypcje stanowią 12% miesięcznych wydatków"

**Przykładowy prompt:**
```
Przeanalizuj poniższe transakcje z ostatnich 6 miesięcy.
Znajdź:
1. Nietypowe wzorce wydatków
2. Trendy (rosnące/malejące kategorie)
3. Sezonowość
4. Potencjalne oszczędności

Format odpowiedzi: JSON z insights i rekomendacjami
```

**Koszt:** ~5-15 gr per analiza (zależnie od ilości danych)

---

### 5. Inteligentne budżetowanie 💡 KONCEPT

**Opis:**
AI sugeruje realistyczne budżety na podstawie historycznych wydatków i celów użytkownika.

**Flow:**
```
1. Użytkownik podaje cel: "Chcę zaoszczędzić 500 PLN miesięcznie"
2. AI analizuje historię wydatków
3. AI sugeruje budżety per kategoria:
   - "Ogranicz jedzenie na mieście z 800 do 500 PLN"
   - "Subskrypcje: możesz zrezygnować z X i Y"
4. System monitoruje realizację i dostosowuje sugestie
```

**Koszt:** ~10-20 gr per generowanie budżetu

---

### 6. Predykcja cash flow 💡 KONCEPT

**Opis:**
AI przewiduje przyszłe przepływy pieniężne na podstawie:
- Historii transakcji
- Zdefiniowanych reguł cyklicznych
- Wykrytych wzorców

**Output:**
- Prognoza salda na koniec miesiąca
- Alert jeśli przewidywany debet
- Optymalne terminy większych wydatków

**Koszt:** ~5-10 gr per prognoza

---

### 7. Wykrywanie anomalii 💡 KONCEPT

**Opis:**
System alertuje o nietypowych transakcjach:
- Znacznie większa kwota niż zwykle
- Nietypowa lokalizacja/merchant
- Podejrzane wzorce (np. wiele małych transakcji)

**Przykłady alertów:**
- "Transakcja 2500 PLN w Media Markt - to 5x więcej niż średnia"
- "Pierwsza transakcja z tego merchanta - potwierdź?"
- "3 transakcje w ciągu 5 minut - czy to zamierzone?"

**Koszt:** ~2-5 gr per batch anomaly check

---

### 8. Chatbot finansowy 💡 KONCEPT

**Opis:**
Konwersacyjny interfejs do danych finansowych:
- "Ile wydałem na jedzenie w lutym?"
- "Porównaj moje wydatki z zeszłym rokiem"
- "Czy stać mnie na wakacje za 5000 PLN?"

**Technologia:**
- RAG (Retrieval Augmented Generation)
- Embedding transakcji w vector DB
- Kontekstowe odpowiedzi

**Koszt:** ~5-20 gr per pytanie (zależnie od złożoności)

---

### 9. OCR paragonów 💡 KONCEPT

**Opis:**
Skanowanie paragonów i automatyczne dodawanie transakcji:
- Wykrywanie merchanta
- Rozpoznawanie pozycji
- Przypisanie do kategorii
- Opcjonalnie: rozbicie na subcategorie

**Technologia:**
- Vision API (GPT-4V / Claude Vision)
- Preprocessing obrazu
- Walidacja OCR

**Koszt:** ~10-30 gr per paragon

---

### 10. Inteligentne cele oszczędnościowe 💡 KONCEPT

**Opis:**
AI pomaga definiować i osiągać cele oszczędnościowe:
- "Chcę zebrać 10000 PLN na wakacje do grudnia"
- AI oblicza ile miesięcznie odkładać
- Sugeruje gdzie ciąć wydatki
- Monitoruje postęp i motywuje

**Koszt:** ~5-10 gr per goal setup + monitoring

---

### 11. Smart tagging 💡 KONCEPT

**Opis:**
Automatyczne tagowanie transakcji:
- #praca, #rodzina, #hobby
- #tax-deductible (dla freelancerów)
- #shared (do rozliczenia z kimś)

**Koszt:** ~1-3 gr per batch

---

### 12. Benchmarki wydatków 💡 KONCEPT

**Opis:**
Porównanie wydatków z anonimowymi danymi innych użytkowników:
- "Wydajesz 20% więcej na transport niż średnia w Twoim mieście"
- "Twoje wydatki na jedzenie są w normie"

**Wymaga:** Agregowane, zanonimizowane dane wielu użytkowników

**Koszt:** ~2-5 gr per benchmark report

---

### 13. Inteligentna rekoncyliacja bankowa 📋 DESIGN READY

**Status:** Design w `2026-02-07-intelligent-cashflow-reconciliation.md`

**Opis:**
Automatyczne łączenie z kontami bankowymi poprzez Open Banking:
- Automatyczny import transakcji
- Synchronizacja salda
- Wykrywanie duplikatów

**Porównanie providerów:**

| Provider | Koszt | Banki PL | Uwagi |
|----------|-------|----------|-------|
| GoCardless | Od €49/mies | 5 | Stabilny, duży |
| Tink | Od €99/mies | 3 | Więcej funkcji |
| Kontomatik | Indywidualnie | 15+ | Lokalny, drogi |

**Rekomendacja:** GoCardless jako MVP, potem Tink dla dodatkowych funkcji

---

## Koszty i modele AI

### Porównanie modeli

| Model | Provider | Koszt input/1M | Koszt output/1M | Use case |
|-------|----------|----------------|-----------------|----------|
| GPT-4o-mini | OpenAI | $0.15 | $0.60 | Kategoryzacja, CSV |
| GPT-4o | OpenAI | $2.50 | $10.00 | Złożona analiza |
| Claude Haiku | Anthropic | $0.25 | $1.25 | Szybkie zadania |
| Claude Sonnet | Anthropic | $3.00 | $15.00 | Średnia złożoność |
| Claude Opus | Anthropic | $15.00 | $75.00 | Najtrudniejsze zadania |

### Szacunkowe koszty miesięczne

Założenia:
- 500 transakcji/miesiąc
- 1 analiza wzorców/miesiąc
- 5 pytań do chatbota/miesiąc

| Funkcjonalność | Koszt/miesiąc |
|----------------|---------------|
| Kategoryzacja (GPT-4o-mini) | ~5-10 gr |
| Sugestie recurring (GPT-4o-mini) | ~2-5 gr |
| Analiza wzorców (GPT-4o) | ~10-15 gr |
| Chatbot (5 pytań, GPT-4o) | ~50-100 gr |
| **RAZEM** | ~0.70-1.30 PLN |

### Optymalizacja kosztów

1. **Cache-first architecture** - już zaimplementowane w CSV Adapter
2. **Batch processing** - grupowanie transakcji (20 per request)
3. **Hierarchia modeli** - tańszy model do prostych zadań
4. **Auto-learning** - mniej AI calls z czasem

---

## Bezpieczeństwo i prywatność

### Implementowane mechanizmy

1. **CsvAnonymizer** ✅
   - Zamiana IBAN na pseudonimy
   - Usuwanie danych osobowych z opisów
   - Maskowanie numerów kart

2. **Minimalizacja danych** ✅
   - Wysyłamy tylko niezbędne kolumny
   - Agregacja zamiast raw data gdzie możliwe

### Wymagane dla kolejnych funkcji

3. **Data retention policy**
   - Automatyczne usuwanie logów AI po X dni
   - Prawo do usunięcia danych użytkownika

4. **Consent management**
   - Explicit opt-in dla AI features
   - Możliwość wyłączenia per funkcjonalność

5. **Audit trail**
   - Log wszystkich AI decisions
   - Możliwość review i korekty

---

## Roadmapa implementacji

### Faza 1: Core AI (Q1 2026) ✅ UKOŃCZONE
- [x] AI Bank CSV Adapter
- [x] MappingRules Cache
- [x] CsvAnonymizer

### Faza 2: Smart Categorization (Q2 2026)
- [ ] LlmProvider abstraction
- [ ] Auto-categorization z confidence
- [ ] CategoryMapping auto-learning
- [ ] UI dla sugestii

### Faza 3: Recurring Intelligence (Q3 2026)
- [ ] RecurringPatternDetector
- [ ] AI Rule Suggestions
- [ ] Monitoring dashboard

### Faza 4: Advanced Analytics (Q4 2026)
- [ ] Spending patterns analysis
- [ ] Budget suggestions
- [ ] Cash flow predictions

### Faza 5: Future (2027+)
- [ ] Financial chatbot
- [ ] OCR paragonów
- [ ] Open Banking integration
- [ ] Benchmarki

---

## Źródła dokumentacji

| Plik | Opis |
|------|------|
| `docs/features-backlog/AI_USE_CASES.md` | 10 przypadków użycia AI |
| `docs/features-backlog/AI_CATEGORIZATION_PLAN.md` | Szczegółowy design auto-kategoryzacji |
| `docs/features-backlog/2026-02-25-recurring-rules-ai-suggestions-monitoring.md` | Design sugestii recurring |
| `docs/features-backlog/2026-03-19-ai-bank-csv-adapter-design.md` | Design CSV Adapter |
| `docs/features-backlog/2026-03-20-csv-anonymizer-detailed-design.md` | Design anonimizatora |
| `docs/features-backlog/2026-02-07-intelligent-cashflow-reconciliation.md` | Open Banking comparison |

---

## Dane testowe

Dla testowania zaimplementowanych funkcji:

**Użytkownik testowy:**
- Login: `fulltestuser`
- Hasło: `SecurePassword123!`
- CashFlow 1: `CF10000108` (402 transakcje, 4 kategorie)
- CashFlow 2: `CF10000109` (402 transakcje, alternatywne mapowania)

**Dystrybucja transakcji (CF10000109):**
| Kategoria | Typ | Ilość |
|-----------|-----|-------|
| Wynagrodzenie | INFLOW | 37 |
| Prywatne | OUTFLOW | 334 |
| Opłaty bankowe | OUTFLOW | 30 |
| Karty | OUTFLOW | 1 |

---

*Dokument wygenerowany automatycznie na podstawie analizy dokumentacji projektowej.*
