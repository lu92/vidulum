# Porównanie providerów Open Banking (tylko AIS)

**Data:** 2026-02-10
**Cel:** Tylko Account Information Services (AIS) - odczyt transakcji, bez przelewów

---

## Podsumowanie cenowe

| Provider | Polska? | Model cenowy | Koszt ~100 userów/msc | Koszt ~1000 userów/msc |
|----------|---------|--------------|------------------------|-------------------------|
| **Kontomatik** | TAK | Per konto + per import | ~600 zł (on-demand) | ~6,000 zł |
| **Tink** | TAK | Per user/msc | ~220 zł (€50) | ~2,200 zł (€500) |
| **finAPI** | NIE (13 krajów EU) | Per user/msc | ~260 zł (€60) | ~1,300 zł (€300) |
| **Enable Banking** | TAK (29 krajów) | Volume-based | ?? (kontakt) | ?? (kontakt) |
| **Yapily** | TAK | Custom | ?? (kontakt) | ?? (kontakt) |
| **Salt Edge** | TAK | Custom | ?? (kontakt) | ?? (kontakt) |
| **GoCardless** | ZAMKNIĘTY | - | - | - |

---

## Enable Banking (Finlandia)

### Co to jest?
Fiński provider Open Banking założony w Helsinkach. Oferuje uniwersalne API do 2,500+ banków w 29 krajach europejskich.

### Pokrycie
- **29 krajów europejskich**
- **2,500+ banków** (ASPSPs)
- Polska wspierana
- Zarówno konta osobiste jak i firmowe

### Cennik AIS
**Enable Banking NIE publikuje cen.** Model:

| Element | Opis |
|---------|------|
| Volume-based | Koszt zależy od liczby kont i płatności/miesiąc |
| Minimum commitment | Wymagana minimalna kwota miesięczna |
| Custom | Negocjowane indywidualnie |

### Model cenowy:
- Volume-based (per accounts accessed + payments made)
- Minimum invoicing per month
- **Musisz zapytać sales: info@enablebanking.com**

### Zalety:
- Duże pokrycie (29 krajów, 2500+ banków)
- Darmowy sandbox i produkcja przed podpisaniem kontraktu
- Nie przechowują danych użytkowników (privacy-focused)
- TPP Infrastructure as a Service
- Licencja PSD2

### Wady:
- Brak transparentnych cen
- Wymaga kontraktu przed publikacją aplikacji
- Minimum commitment

### Kontakt:
- info@enablebanking.com
- [enablebanking.com](https://enablebanking.com)

---

## finAPI (Niemcy)

### Co to jest?
Niemiecki lider Open Banking z licencją BaFin. Jeden z najdłużej działających providerów w Europie (głównie DACH).

### Pokrycie
- **13 krajów europejskich**: DE, AT, CZ, HU, RO, SK, SI, FR, NL, BE, IT, ES
- **~3,000 banków**
- **NIE wspiera Polski!**

### Pokrycie per kraj:
| Kraj | % banków | AIS | PIS |
|------|----------|-----|-----|
| Niemcy | 99% | ✅ | ✅ |
| Austria | 95% | ✅ | ✅ |
| Francja | 90% | ✅ | ✅ |
| Słowacja | 91% | ✅ | ✅ |
| Rumunia | 87% | ✅ | ✅ |
| Czechy | 82% | ✅ | ✅ |
| Holandia | 81% | ✅ | ✅ |
| Belgia | 81% | ✅ | ✅ |
| Węgry | 67% | ✅ | ✅ |

### Cennik AIS (Access B2C) - PUBLICZNY!

| Wolumen userów | Koszt/miesiąc |
|----------------|---------------|
| Do 200 | **€60 flat** |
| Do 1,000 | **€300 flat** |
| 1,001 - 5,000 | €0.30/user |
| 5,001 - 10,000 | €0.27/user |
| 10,001 - 25,000 | €0.24/user |
| 25,001 - 50,000 | €0.21/user |
| 50,001 - 100,000 | €0.18/user |
| 100,001+ | €0.15/user |

### Przykład kalkulacji:
```
5,001 userów:
€300 (do 1000) + 4,000 × €0.30 + 1 × €0.27 = €1,500.27/msc
```

### Access B2X (Business accounts) - droższy:

| Wolumen | B2C | B2X |
|---------|-----|-----|
| Do 200 | €60 | €100 |
| Do 1,000 | €300 | €500 |
| 1,001+ | €0.30 | €0.50 |

### Add-ons:
- International access: €20-0.05/user (zależnie od wolumenu)
- Batch updates: dodatkowa opłata
- Data Intelligence reports: osobny cennik (€80-400 base + per-call)

### Technologia:
- **XS2A** (PSD2 standard) - główna metoda
- **FinTS/HBCI** (niemiecki standard) - dostęp do oszczędnościowych, kart
- **Web scraping** - fallback gdy brak API

### Zalety:
- **Transparentny, publiczny cennik!**
- Najlepsze pokrycie DACH (DE, AT, CH)
- Dostęp do kont oszczędnościowych i kart (FinTS)
- Licencja BaFin (AIS + PIS)
- 13M+ API calls/dzień

### Wady:
- **NIE wspiera Polski!**
- Droższy przy małej skali (€60 minimum)
- Skomplikowany cennik z add-onami

### Kontakt:
- [finapi.io/en/prices](https://www.finapi.io/en/prices/)
- [finapi.io/en/products/country-coverage](https://www.finapi.io/en/products/country-coverage/)

---

## Yapily

### Co to jest?
Brytyjski provider Open Banking z silną pozycją w Europie. Oferuje zarówno AIS jak i PIS.

### Pokrycie Polski
- **25+ milionów kont** dostępnych w Polsce
- Banki: PKO Bank Polski, mBank, Bank Pekao, Santander, ING i więcej
- Wspiera Polish API framework (standard PFSA)

### Cennik AIS
**Yapily NIE publikuje cen.** Oferują 3 tiery:

| Tier | Opis | Dla kogo |
|------|------|----------|
| **Sandbox** | Darmowy, tylko testy | Development |
| **Get Set for Success** | Base fee + usage-based | Startupy, SMB |
| **Enterprise** | Custom pricing | Duże firmy |

### Model cenowy:
- Base fee (stała miesięczna)
- + Usage-based (per connection, per refresh)
- Brak publicznych cen - **musisz zapytać sales**

### Zalety:
- Dobre pokrycie polskich banków
- Jeden z liderów w Europie
- AIS + PIS w jednym
- Sandbox darmowy

### Wady:
- Brak transparentnych cen
- Może być drogi dla małych firm
- Wymaga kontaktu z sales

### Kontakt:
- [yapily.com/pricing](https://www.yapily.com/pricing)
- Formularz kontaktowy na stronie

---

## Salt Edge

### Co to jest?
Globalny provider Open Banking z siedzibą w Kanadzie. Jeden z największych na świecie.

### Pokrycie Polski
- **5,000+ banków** w 50+ krajach
- Polska wspierana
- Zarówno retail jak i business accounts

### Cennik AIS
**Salt Edge NIE publikuje cen.** Model:

| Element | Opis |
|---------|------|
| API Call Volume | Podstawa cennika - liczba wywołań |
| Data Retrieval | Częstotliwość odświeżania danych |
| Feature-based | Dodatkowe za enrichment, bulk payments |
| Custom | Negocjowane indywidualnie |

### Model cenowy:
- Usage-based (per API call)
- Różne stawki per kraj/bank
- Custom pricing dla większych wolumenów
- **Musisz zapytać sales**

### Zalety:
- Ogromne pokrycie (5000+ banków)
- Działa globalnie (nie tylko EU)
- ISO 27001, PSD2 licensed
- Bulk payments, data enrichment

### Wady:
- Brak transparentnych cen
- Może być drogi przy dużej skali
- Skomplikowany model cenowy

### Kontakt:
- sales@saltedge.com
- [saltedge.com](https://www.saltedge.com)

---

## Porównanie z Kontomatik i Tink

### Kontomatik (znany cennik)

| Element | Koszt |
|---------|-------|
| Stałe per konto | 2 zł/msc |
| Per import | 0.50 zł |
| **On-demand (8 imp./msc)** | **6 zł/konto** |
| Scheduled Pn-Pt (22 imp.) | 13 zł/konto |

### Tink (znany cennik)

| Element | Koszt |
|---------|-------|
| Per user/miesiąc | **€0.50 (~2.20 zł)** |
| Per import | 0 zł (unlimited) |
| Polskie banki | PKO, mBank, Pekao, ING, Santander+ |

### finAPI (znany cennik, 13 krajów EU, BEZ Polski!)

| Wolumen | Access B2C | Access B2X (biznes) |
|---------|------------|---------------------|
| Do 200 userów | €60 flat | €100 flat |
| Do 1,000 userów | €300 flat | €500 flat |
| 1,001-5,000 | €0.30/user | €0.50/user |
| 5,001-10,000 | €0.27/user | €0.37/user |
| 10,001-25,000 | €0.24/user | €0.34/user |
| 25,001+ | €0.15-0.21/user | €0.25-0.31/user |

**Kraje:** DE (99%), AT (95%), FR (90%), CZ (82%), HU (67%), SK, SI, RO, NL, BE, IT, ES

**UWAGA:** finAPI nie wspiera Polski! Ale świetny dla ekspansji na DE/AT/CZ.

### Enable Banking (volume-based, kontakt wymagany)

| Element | Opis |
|---------|------|
| Model | Volume-based (per accounts + payments) |
| Minimum | Wymagany minimum commitment |
| Kraje | 29 EU (w tym Polska) |
| Banki | 2,500+ |
| Kontakt | info@enablebanking.com |

---

## Rekomendacja dla Vidulum

### Opcja 1: Tink (REKOMENDOWANE jeśli dostępne)
```
Koszt: €0.50/user/msc (~2.20 zł)
Unlimited importów
Dobre pokrycie PL
6x tańszy niż Kontomatik scheduled
```

### Opcja 2: Kontomatik on-demand
```
Koszt: 6 zł/user/msc (8 importów)
User klika przycisk sync
Świetne pokrycie PL
54% tańszy niż scheduled
```

### Opcja 3: Zapytaj Yapily/Salt Edge
```
Możliwe że będą tańsi lub drożsi
Custom pricing = negocjacje
Warto zapytać mając ofertę Kontomatik jako benchmark
```

---

## Co zapytać Yapily/Salt Edge

### Template email:

```
Subject: AIS Pricing Inquiry - Poland Market

Hello,

We are building a personal finance / cash flow management SaaS
for the Polish market and need Account Information Services (AIS) only
(no payment initiation).

Our requirements:
- Market: Poland
- Banks: Major Polish banks (PKO, mBank, Pekao, ING, Santander, Millennium)
- Volume: Starting 100 users, scaling to 1000-5000
- Features: Transaction history, account balance, categorization (if available)
- Model: On-demand refresh (user-initiated) preferred over scheduled

Questions:
1. What is your pricing for AIS-only in Poland?
2. Is it per-user, per-connection, per-API-call, or hybrid?
3. Are there minimum commitments or setup fees?
4. What is the typical implementation timeline?
5. Do you offer a sandbox for testing?

For comparison, we have received the following offers:
- Kontomatik: 2 PLN/month/account + 0.50 PLN/import
- Tink: €0.50/user/month

Looking forward to your proposal.

Best regards,
[Your name]
```

---

## Kluczowe wnioski

### Problem: Brak transparentnych cen

Yapily i Salt Edge wymagają kontaktu z sales. Jedyne znane ceny na rynku:

| Provider | Koszt/user/msc | Polska | Status |
|----------|----------------|--------|--------|
| **Tink** | €0.50 (~2.20 zł) | TAK | Dostępny |
| **Kontomatik on-demand** | ~6 zł | TAK | Dostępny |
| **Kontomatik scheduled** | ~13 zł | TAK | Dostępny |
| **finAPI** | €0.15-0.30 | NIE | Świetny dla DE/AT/CZ |
| **Enable Banking** | ?? (volume) | TAK | Zapytaj |
| Yapily | ?? | TAK | Zapytaj |
| Salt Edge | ?? | TAK | Zapytaj |
| GoCardless | - | - | ZAMKNIĘTY |

### Rekomendacja końcowa dla startupów:

1. **Kontomatik on-demand** - 6 zł/user, dostępny dla startupów, świetne pokrycie PL
2. **finAPI** - jeśli ekspansja na DE/AT/CZ (€60/msc minimum, publiczny cennik)
3. **Zapytaj Enable Banking/Yapily** - użyj benchmarku Kontomatik (6-13 zł)
4. ~~Tink~~ - **NIE dla startupów** (enterprise-focused, custom pricing)

### Model on-demand vs scheduled:

| Model | Kontomatik | Tink | UX |
|-------|------------|------|-----|
| Scheduled Pn-Pt | 13 zł | 2.20 zł | Dane zawsze gotowe |
| **On-demand** | **6 zł** | **2.20 zł** | User czeka 30-90 sek |

**On-demand oszczędza 54% przy Kontomatik** i daje user'owi real-time dane.

---

---

## Ważne: Co oznacza "user" w cenniku finAPI?

### Definicja z dokumentacji finAPI:

> **"A user is defined by a unique user ID in the finAPI database. The number of accounts and bank details managed per user is not billing relevant."**

> **"Repeated retrieval of the information is not cost-relevant, unless otherwise specified."**

### Co to oznacza w praktyce:

| Element | Rozliczenie |
|---------|-------------|
| 1 user z 1 kontem | €0.30/msc |
| 1 user z 5 kontami w 3 bankach | **€0.30/msc** (ta sama cena!) |
| Importy (refreshe) | **UNLIMITED** - wliczone w cenę |

### Przykład:
```
User "Jan Kowalski":
- Konto osobiste w Deutsche Bank
- Konto oszczędnościowe w Sparkasse
- Konto firmowe w Commerzbank
- Karta kredytowa w N26

Koszt finAPI: €0.30/msc (jeden user = jedna cena, bez względu na liczbę kont)
Importy: bez limitu ze wszystkich 4 kont
```

### Porównanie z Kontomatik (per konto + per import):

| Scenariusz | finAPI | Kontomatik (8 imp.) |
|------------|--------|---------------------|
| 1 user, 1 konto | €0.30 (~1.30 zł) | 6 zł |
| 1 user, 3 konta | **€0.30** (~1.30 zł) | **18 zł** (3×6 zł) |
| 1 user, 5 kont | **€0.30** (~1.30 zł) | **30 zł** (5×6 zł) |

**Wniosek:** finAPI jest znacznie tańszy dla userów z wieloma kontami (typowy B2B case).

---

## Tabela pokrycia krajów per provider

### Legenda:
- ✅ = Pełne wsparcie AIS
- 🔶 = Częściowe wsparcie
- ❌ = Brak wsparcia
- **%** = procent pokrycia banków w kraju

| Kraj | Kontomatik | Tink | finAPI | Enable Banking | Yapily | Salt Edge |
|------|------------|------|--------|----------------|--------|-----------|
| **Polska** | ✅ 95%+ | ✅ (11 banków) | ❌ | ✅ | 🔶 | ✅ |
| **Niemcy** | 🔶 | ✅ | ✅ 99% | ✅ | ✅ 98% | ✅ |
| **Austria** | ❌ | ✅ | ✅ 95% | ✅ | ✅ | ✅ |
| **Czechy** | ✅ | ❌ | ✅ 82% | ✅ | ❌ | ✅ |
| **Węgry** | ❌ | ❌ | ✅ 67% | ✅ | ❌ | ✅ |
| **Słowacja** | ❌ | ❌ | ✅ 91% | ✅ | ❌ | ✅ |
| **Rumunia** | ❌ | ❌ | ✅ 87% | ✅ | ❌ | ✅ |
| **Francja** | 🔶 | ✅ | ✅ 90% | ✅ | ✅ | ✅ |
| **Holandia** | ❌ | ✅ | ✅ 81% | ✅ | ✅ | ✅ |
| **Belgia** | ❌ | ✅ | ✅ 81% | ✅ | ✅ | ✅ |
| **Włochy** | ✅ | ✅ | ✅ | ✅ | ✅ 80% | ✅ |
| **Hiszpania** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Portugalia** | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **UK** | 🔶 | ✅ | ❌ | ✅ | ✅ 98% | ✅ |
| **Irlandia** | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Szwecja** | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Norwegia** | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Dania** | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Finlandia** | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Estonia** | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Łotwa** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| **Litwa** | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Słowenia** | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ |
| **Islandia** | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |

### Podsumowanie pokrycia:

| Provider | Kraje EU | Banki | Specjalizacja |
|----------|----------|-------|---------------|
| **Kontomatik** | 11 | ~500 | CEE (PL, CZ, Bałtyk) |
| **Tink** | 18 | 3,400+ | Skandynawia, Zachód EU |
| **finAPI** | 13 | 3,000 | DACH (DE 99%, AT 95%) |
| **Enable Banking** | 29 | 2,500+ | Cała EU |
| **Yapily** | 19 | 2,000+ | UK 98%, DE 98% |
| **Salt Edge** | 50+ | 5,000+ | Globalnie |

---

## Scoring: Który provider najtańszy?

### Dla Polski (tylko) - realnie dostępne dla startupów:

| Ranking | Provider | Koszt 100 userów | Koszt 1000 userów | Multi-konta | Startup? |
|---------|----------|------------------|-------------------|-------------|----------|
| 🥇 1 | **Kontomatik on-demand** | ~600 zł | ~6,000 zł | Per konto | ✅ Tak |
| 🥈 2 | **Kontomatik scheduled** | ~1,300 zł | ~13,000 zł | Per konto | ✅ Tak |
| ? | Enable Banking | ?? | ?? | ?? | 🔶 Zapytaj |
| ? | Yapily | ?? | ?? | ?? | 🔶 Zapytaj |
| ? | Salt Edge | ?? | ?? | ?? | ?? |
| ⚠️ | **Tink** | ~220 zł* | ~2,200 zł* | Unlimited | ❌ Enterprise |

*Ceny Tink są tylko dla istniejących klientów enterprise - nowi muszą kontaktować sales.

**Tink - polskie banki:** PKO BP, mBank, ING, Pekao, Santander, Millennium, BNP Paribas, Credit Agricole, Alior, Revolut, Wise (11 banków)

### Dla DACH (DE/AT/CH) - realnie dostępne dla startupów:

| Ranking | Provider | Koszt 100 userów | Koszt 1000 userów | Multi-konta | Startup? |
|---------|----------|------------------|-------------------|-------------|----------|
| 🥇 1 | **finAPI** | ~260 zł (€60) | ~1,300 zł (€300) | ✅ Unlimited | ✅ Tak |
| ? | Enable Banking | ?? | ?? | ?? | 🔶 Zapytaj |
| ? | Yapily | ?? | ?? | ?? | 🔶 Zapytaj |
| ⚠️ | **Tink** | ~220 zł* | ~2,200 zł* | Unlimited | ❌ Enterprise |

### Dla ekspansji EU (wiele krajów):

| Ranking | Provider | Kraje | Cennik | Startup-friendly |
|---------|----------|-------|--------|------------------|
| 🥇 1 | **finAPI** | 13 | Publiczny €0.30/user | ✅ Tak (€60 min) |
| 🥈 2 | Enable Banking | 29 | Kontakt | 🔶 Minimum commitment |
| 🥉 3 | Yapily | 19 | Kontakt | 🔶 Base fee |
| 4 | Salt Edge | 50+ | Kontakt | ?? |
| ⚠️ | **Tink** | 18 | Enterprise only | ❌ Nie dla startupów |

---

## Czy przyjmują startupy?

| Provider | Startup-friendly | Minimum | Sandbox | Uwagi |
|----------|------------------|---------|---------|-------|
| **Tink** | ❌ **Enterprise-focused** | Custom (kontakt sales) | ✅ Darmowy | Ceny na stronie tylko dla istniejących klientów! |
| **finAPI** | ✅ Tak | €60/msc | ✅ Darmowy | Publiczny cennik, niski próg |
| **Kontomatik** | ✅ Tak | ~6 zł/konto | ✅ Darmowy | Polski provider, łatwy kontakt |
| **Enable Banking** | 🔶 Warunkowo | Minimum commitment | ✅ Darmowy | Trzeba podpisać kontrakt |
| **Yapily** | 🔶 Warunkowo | Base fee (nieznana) | ✅ Darmowy | Tier "Get Set for Success" |
| **Salt Edge** | ?? Nieznane | Custom | ✅ Darmowy | Enterprise-focused |

### ⚠️ UWAGA: Tink NIE jest dla startupów!

Zgodnie z [Merchant Machine](https://merchantmachine.co.uk/open-banking-payments/tink/) i [oficjalną stroną Tink](https://tink.com/pricing/):

> **"The prices listed on this page are applicable exclusively to our existing customers who have an active business relation with us."**

> **"Tink mostly targets relatively large businesses, enterprises, financial institutions, and banks."**

> **"A noted drawback of Tink is that it's not geared toward individuals, merchants, and small businesses."**

**Co to oznacza:**
- Cena €0.50/user to cena dla **istniejących klientów enterprise**
- Nowi klienci muszą kontaktować się z sales
- Brak gwarantowanego SLA dla non-enterprise
- Produkty jak recurring payments tylko dla Enterprise

### Rekomendacja dla startupów:

1. **finAPI** - jeśli nie potrzebujesz Polski (€60 flat start, publiczny cennik)
2. **Kontomatik** - jeśli tylko Polska (6 zł/konto on-demand)
3. **Enable Banking** - zapytaj o minimum commitment dla startupów
4. **Tink** - tylko jeśli masz budżet na enterprise deal

---

## Strategia multi-country (ekspansja poza Polskę)

Jeśli planujesz ekspansję na inne rynki europejskie:

### Opcja 1: Dual-provider strategy
```
Polska: Tink lub Kontomatik
DACH (DE/AT/CH): finAPI (najtańszy, najlepsze pokrycie)
Reszta EU: Enable Banking lub Yapily
```

### Opcja 2: Single-provider strategy
```
Enable Banking lub Yapily - 29 krajów z jednej integracji
Wyższy koszt, ale prostsze utrzymanie
```

### finAPI dla DACH - dlaczego warto?
- **99% pokrycia banków w Niemczech** (najważniejszy rynek EU)
- Dostęp do kont oszczędnościowych przez FinTS
- Transparentny cennik bez niespodzianek
- Tylko €60/msc za 200 userów (tańszy start)

---

## Źródła

### Oficjalne strony cenowe:
- [finAPI Pricing](https://www.finapi.io/en/prices/) - **publiczny cennik z dokładnymi kwotami**
- [finAPI Country Coverage](https://www.finapi.io/en/products/country-coverage/) - 13 krajów EU z % pokrycia
- [Tink Pricing](https://tink.com/pricing/) - €0.50/user/msc
- [Tink Coverage](https://tink.com/) - 18 krajów, 3400+ banków

### Dokumentacja providerów:
- [Enable Banking](https://enablebanking.com) - 29 krajów, volume-based
- [Enable Banking FAQ](https://enablebanking.com/docs/faq/) - model cenowy, minimum commitment
- [Yapily Coverage](https://www.yapily.com/coverage) - 19 krajów, 2000+ banków
- [Yapily Pricing](https://www.yapily.com/pricing) - brak publicznych cen
- [Salt Edge Coverage](https://www.saltedge.com/products/account_information/coverage) - 50+ krajów
- [Kontomatik](https://www.kontomatik.com/) - 11 krajów CEE

### Analizy rynkowe:
- [Open Banking Tracker - Tink](https://www.openbankingtracker.com/api-aggregators/tink) - 509+ banków
- [Open Banking Tracker - Salt Edge](https://www.openbankingtracker.com/api-aggregators/salt-edge) - 1585+ banków
- [Open Banking Poland Overview](https://www.openbankingtracker.com/country/poland)
- [GoCardless zamknięty](https://forum.invoiceninja.com/t/gocardless-nordigen-service-no-longer-available-alternative-needed/22576)
