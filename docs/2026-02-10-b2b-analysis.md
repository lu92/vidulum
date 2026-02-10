# Analiza możliwości B2B dla Vidulum

**Data:** 2026-02-10
**Status:** Analiza strategiczna

---

## Obecny stan aplikacji

Vidulum to **multi-portfolio finansowa aplikacja** z dwoma głównymi modułami:

1. **Portfolio & Trading** - zarządzanie portfelami inwestycyjnymi, zlecenia, PnL
2. **CashFlow Forecasting** - prognozowanie przepływów pieniężnych, import z banków, kategoryzacja

---

## Funkcjonalności z potencjałem B2B

| Funkcjonalność | Wartość dla B2B | Status | Premium Pricing |
|----------------|-----------------|--------|-----------------|
| **Bank API Integration (Open Banking)** | Bardzo wysoka | Planowana | TAK |
| **AI Kategoryzacja transakcji** | Wysoka | Planowana | TAK |
| **Recurring Rules + Reconciliation** | Wysoka | Planowana | TAK |
| **Multi-portfolio (wiele kont)** | Średnia | Zaimplementowana | TAK |
| **Benchmarki (porównanie z rynkiem)** | Średnia | Planowana | TAK |
| **CSV Import** | Niska | Zaimplementowana | NIE |

---

## Kluczowe funkcje za które biznes ZAPŁACI WIĘCEJ

### 1. Open Banking Integration (Tink/GoCardless)

```
Klient indywidualny: €0 (sam wpisuje transakcje)
Business (10 kont bankowych): €5-50/miesiąc

Wartość: Automatyczna synchronizacja z wieloma bankami
B2B case: Firma ma 5+ kont w różnych bankach
```

### 2. AI-Powered Categorization (Claude/OpenAI)

```
Consumer: 50 transakcji/miesiąc = 2-4 gr/miesiąc (prawie darmowe)
Business: 5000 transakcji/miesiąc = 2-5 zł/miesiąc (koszt API)
               + 50-100 zł/miesiąc (marża za wygodę)

Wartość: 95% automatyczna kategoryzacja bez pracy księgowej
B2B case: Eliminuje 20+ godzin pracy księgowego miesięcznie
```

### 3. Intelligent Reconciliation Engine

```
Consumer: Prosty matching = free tier
Business: Zaawansowany matching z counterpartyAccount,
          wielowalutowość, partial payments = premium

Wartość: Automatyczne dopasowanie faktur do płatności
B2B case: Firmy z 100+ kontrahentami potrzebują reconciliation
```

### 4. Multi-CashFlow Management

```
Consumer: 1-2 CashFlows (konto osobiste + oszczędności)
Business: 10+ CashFlows (firmowe, projektowe, departamentowe)

Wartość: Zarządzanie wieloma budżetami z jednego miejsca
B2B case: Każdy projekt/dział ma swój budżet
```

### 5. Forecast & Anomaly Detection

```
Consumer: "Czy wystarczy mi do końca miesiąca?"
Business: "Cash flow projection na 12 miesięcy z sezonowością"

Wartość: Prognozowanie płynności finansowej
B2B case: CFO potrzebuje wiedzieć kiedy będą braki gotówki
```

---

## Model cenowy B2B vs Consumer

| Tier | Consumer | SMB (mały biznes) | Enterprise |
|------|----------|-------------------|------------|
| Cena | 0-29 zł/msc | 99-299 zł/msc | 500-2000 zł/msc |
| Konta bankowe | 1-2 | 5-10 | Unlimited |
| Transakcje/msc | 200 | 2000 | 10000+ |
| AI kategoryzacja | Basic | Full | Full + custom rules |
| Bank API | Manual only | 4 sync/dzień | Real-time webhook |
| Reconciliation | Manual | Auto-suggest | Full auto + audit |
| Support | Community | Email | Dedicated + SLA |
| Multi-user | 1 | 3-5 | Unlimited |
| API access | Brak | Read-only | Full CRUD |
| Audit trail | Brak | Basic | Enterprise |
| White-label | Brak | Brak | TAK |

---

## Konkretne przypadki użycia B2B

### 1. Mała firma (freelancer/jednoosobowa)

- **Problem**: Ręczne wpisywanie transakcji, brak prognozy cash flow
- **Rozwiązanie Vidulum**: Import CSV + AI kategoryzacja + 12-miesięczna prognoza
- **Willingness to pay**: 49-99 zł/msc (oszczędza 5-10h/msc)

### 2. Średnia firma (10-50 pracowników)

- **Problem**: Wiele kont bankowych, ręczny reconciliation, brak visibility
- **Rozwiązanie Vidulum**: Open Banking + Auto-reconciliation + Multi-CashFlow
- **Willingness to pay**: 199-499 zł/msc (oszczędza 20-40h/msc pracy księgowej)

### 3. Startup / Scale-up

- **Problem**: Burn rate visibility, runway projection
- **Rozwiązanie Vidulum**: Cash burn tracking + Runway calculator + investor-ready reports
- **Willingness to pay**: 299-999 zł/msc (krityczna funkcja dla przetrwania)

### 4. Biuro rachunkowe

- **Problem**: Obsługa wielu klientów, każdy z innymi bankami
- **Rozwiązanie Vidulum**: White-label + Multi-tenant + Bulk operations
- **Willingness to pay**: 500-2000 zł/msc za możliwość obsługi klientów

---

## Przychód potencjalny

```
Scenariusz rok 1:
  - 1000 users free
  - 100 users consumer premium @ 29 zł = 2,900 zł/msc
  - 20 SMB @ 199 zł = 3,980 zł/msc
  - 5 Enterprise @ 999 zł = 4,995 zł/msc

  Total MRR: ~11,875 zł/msc = ~142,500 zł/rok

  B2B (25 klientów) = 8,975 zł/msc = 75% przychodów!
  B2C (100 klientów) = 2,900 zł/msc = 25% przychodów
```

---

## Rekomendacje

### Najpierw zaimplementuj:

1. **Open Banking** (GoCardless - darmowy dla dev)
2. **AI Categorization** (Claude Haiku - tani)
3. **Multi-user per CashFlow**

### B2B differentiators:

- **Audit trail** - każda zmiana logowana (compliance)
- **API access** - integracja z ich systemami
- **Bulk operations** - import/export wielu danych
- **Custom categories** - dopasowane do ich planu kont

### Go-to-market B2B:

1. Zacznij od freelancerów/jednoosobowych (łatwiejsze pozyskanie)
2. Potem małe firmy (word of mouth)
3. Biura rachunkowe jako kanał dystrybucji

---

## Podsumowanie

**TAK**, Vidulum ma solidny potencjał B2B.

Kluczowe funkcje to:
- Open Banking
- AI kategoryzacja
- Reconciliation
- Multi-CashFlow

**Business zapłaci 5-20x więcej niż consumer** za te same funkcje, bo oszczędzają czas pracy i redukują ryzyko błędów księgowych.
