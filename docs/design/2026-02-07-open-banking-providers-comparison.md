# Open Banking Providers - Porównanie i Analiza

**Data utworzenia**: 2026-02-07
**Cel**: Wybór providera Open Banking dla aplikacji Vidulum (Cash Flow Forecasting SaaS)
**Kontekst**: Polska działalność gospodarcza, mały SaaS, budżet ~€0.50/user/miesiąc

---

## Spis treści

1. [Podsumowanie wykonawcze](#podsumowanie-wykonawcze)
2. [Porównanie providerów Open Banking](#porównanie-providerów-open-banking)
3. [Szczegółowe opisy providerów](#szczegółowe-opisy-providerów)
4. [Payment Processors (Stripe i alternatywy)](#payment-processors)
5. [Rekomendacje](#rekomendacje)
6. [Szablony maili do providerów](#szablony-maili-do-providerów)

---

## Podsumowanie wykonawcze

### Sytuacja
- Polska działalność gospodarcza (JDG/spółka)
- Brak EU VAT (nie jest blokerem - reverse charge)
- Potrzeba dostępu do polskich banków przez Open Banking API
- Niska subskrypcja SaaS (~€4-5/miesiąc)
- Budżet na Open Banking: ~€0.50/user/miesiąc

### Kluczowe wnioski

| Aspekt | Wniosek |
|--------|---------|
| **Najlepszy provider** | Salt Edge Partner Program (90 dni darmowego testu z live data) |
| **Polski alternatywny** | Kontomatik (Warszawa, specjalizacja CEE) |
| **Backup** | Aiia/Mastercard (stabilność korporacji) |
| **Payment processor** | Stripe (1.4% + €0.25 dla EU) |
| **Nordigen/GoCardless** | ZAMKNIĘTE dla nowych rejestracji (lipiec 2025) |

### Główne ryzyka
1. Brak publicznych cenników - trzeba negocjować
2. Providerzy enterprise-focused mogą ignorować małe firmy
3. Koszty Live mode mogą być za wysokie dla małego SaaS

---

## Porównanie providerów Open Banking

### Tabela główna

| Provider | Region | Polska | Free tier | Min. cena | Self-service | Dla małych firm |
|----------|--------|--------|-----------|-----------|--------------|-----------------|
| **Salt Edge** | EU (50+ krajów) | ✅ | 100 conn / 90 dni | Custom | ✅ | ⭐⭐⭐ |
| **Kontomatik** | CEE (PL, CZ, ES) | ✅ Polski | Demo | Custom | ? | ⭐⭐⭐⭐ |
| **Aiia/Mastercard** | EU | ✅ | Sandbox | Custom | ⚠️ | ⭐⭐⭐ |
| **Tink (Visa)** | EU | ✅ ~11 banków | Sandbox | €0.50/user | ❌ | ⭐⭐ Enterprise |
| **Yapily** | EU (19 krajów) | ✅ | Sandbox | Custom | ⚠️ | ⭐⭐⭐ |
| **TrueLayer** | EU (22 kraje) | ✅ | Sandbox | Custom | ❌ | ⭐⭐ |
| **Plaid** | US/EU | ✅ | Sandbox | Custom (EU) | ❌ w EU | ⭐⭐ |
| **finAPI** | DE/AT | ❌ | 30 dni trial | €250/mies | ✅ | ⭐⭐⭐ |
| **Finexer** | UK only | ❌ | Usage-based | Custom | ✅ | ⭐⭐⭐⭐ |
| **Noda** | EU/UK | ? | ? | Custom | ? | ⭐⭐⭐ |
| **Swan** | EU | ? | ❌ | €900/mies | ❌ | ⭐ Enterprise |
| **Nordigen/GC** | EU | ✅ | ~~Darmowe~~ | ❌ ZAMKNIĘTE | ❌ | ❌ |

### Legenda
- ✅ = Tak / Dostępne
- ❌ = Nie / Niedostępne
- ⚠️ = Częściowo / Z ograniczeniami
- ? = Brak informacji

---

## Szczegółowe opisy providerów

### 1. Salt Edge Partner Program ⭐ REKOMENDOWANY

**Strona**: https://www.saltedge.com/products/account_information/partner_program

#### Opis
Salt Edge Partner Program umożliwia dostęp do Open Banking bez własnej licencji PSD2. Salt Edge działa jako licencjonowany AISP, a klient operuje pod ich parasolem regulacyjnym.

#### Statusy konta i limity

| Status | Dostęp | Connections | Czas | Koszt |
|--------|--------|-------------|------|-------|
| **Pending** | Tylko fake/sandbox | Max 100 fake | Bez limitu | **DARMOWY** |
| **Test** | Fake + LIVE providers | Max 100 łącznie | 90 dni | **DARMOWY** |
| **Live** | Pełny produkcyjny | Bez limitu | Bez limitu | **PŁATNY** |

#### Limity API

| Limit | Wartość |
|-------|---------|
| Fetch nowych danych | Max 2 requesty/sekundę (7,200/h) |
| Auto-refresh per user | 1x dziennie (bez obecności usera) |
| Background fetch (PSD2) | 4x na 24h per connection |
| Retry po błędzie | Max 2 dodatkowe próby/dzień |
| Batch size | Max 100 obiektów per request |
| Kategoryzacja (Test/Pending) | Max 1000 transakcji/dzień |

#### Pricing (Live mode)
- **NIE PUBLIKUJĄ oficjalnego cennika**
- Model: usage-based (API calls / connections)
- Nieoficjalnie (~2019): ~$500/miesiąc po 100 darmowych connections
- Składniki: API Call Volume, Data Retrieval, Connections, Features (enrichment)

#### Wymagania techniczne (Test → Live)

| Wymaganie | Opis |
|-----------|------|
| HTTPS | Wszystkie callbacks na port 443 |
| 2FA | Dla konta i wszystkich teammates |
| Request signing | Implementacja podpisu requestów |
| Incident email | Skonfigurowany w dashboardzie |
| Status page | Subskrypcja ich status page |
| Review | ~2 dni robocze |

#### Coverage - Polska
Pełna lista: https://www.saltedge.com/products/account_information/coverage/pl
- PKO BP, mBank, Santander, ING, Pekao SA, i inne

#### Plusy
- 90 dni darmowego testu z LIVE data
- Bez własnej licencji PSD2
- Dobra dokumentacja REST API
- Self-service do Test mode
- Szeroka coverage (5000+ banków, 50+ krajów)

#### Minusy
- Brak publicznego cennika
- Live mode może być drogi
- Trzeba negocjować

---

### 2. Kontomatik 🇵🇱

**Strona**: https://www.kontomatik.com/
**Developer Portal**: https://developer.kontomatik.com/
**Kontakt**: contact@kontomatik.com
**Adres**: Bonifraterska 17, 00-203 Warszawa

#### Opis
Polski pionier Open Banking (od 2009). Pierwszy fintech z licencją AISP od KNF i Banku Litwy. Specjalizacja w regionie CEE.

#### Cechy

| Aspekt | Szczegóły |
|--------|-----------|
| **Region** | Polska, Czechy, Hiszpania |
| **Licencja** | AISP (KNF, Bank Litwy) |
| **Pricing** | Custom, per session |
| **Billing** | Płacisz tylko za udane sesje |
| **Extras** | PDF parsing (wyciągi), ML analysis |

#### Billing model
- Sesja = login usera do banku przez widget
- Płacisz tylko gdy wszystkie komendy import wykonają się pomyślnie
- Nieudane sesje (np. problemy z połączeniem) nie są naliczane

#### Plusy
- **Polski provider** - rozumie lokalny rynek
- Pierwszy z licencją KNF
- Elastyczny billing (per session)
- PDF parsing dla wyciągów
- Może mieć lepsze warunki dla polskich startupów

#### Minusy
- Brak publicznego cennika
- Mniejszy zasięg (tylko CEE)
- Trzeba kontaktować się po wycenę

---

### 3. Aiia / Mastercard Open Banking

**Strona**: https://openbankingeu.mastercard.com/
**Developer Portal**: https://developer.mastercard.com/open-banking-europe/documentation/

#### Opis
Aiia (przejęte przez Mastercard) oferuje Open Banking w EU. Dostępne ścieżki: Licensed (masz licencję) lub Unlicensed (pod ich licencją).

#### Cechy

| Aspekt | Szczegóły |
|--------|-----------|
| **Region** | EU (DK, FI, NO, NL, UK, IE, PL, LU, DE, EE, LT, AT) |
| **Banki** | ~3000 w Europie |
| **Licencja** | Możesz działać pod ich licencją |
| **Pricing** | Custom (kontakt z Mastercard) |

#### Ścieżka Unlicensed
- Aiia Data - dostęp do danych bez licencji
- Aiia Pay - płatności bez licencji
- Działasz pod licencją Mastercard

#### Proces
1. Rejestracja na developer.mastercard.com
2. Sandbox access automatyczny
3. Request production connectivity
4. Review przez Mastercard

#### Plusy
- Mastercard = stabilność i zaufanie
- Szeroka coverage EU
- Bez własnej licencji

#### Minusy
- Korporacyjny proces (może być wolny)
- Brak publicznego cennika
- Może być overkill dla małego SaaS

---

### 4. Tink (Visa)

**Strona**: https://tink.com/
**Console**: https://console.tink.com/

#### Opis
Szwedzka platforma Open Banking przejęta przez Visa w 2022. Enterprise-focused.

#### Pricing

| Usługa | Cena |
|--------|------|
| Transaction services | €0.50/user/miesiąc |
| Account verification | €0.25/weryfikacja |
| Enterprise | Custom |

#### Coverage - Polska (~11 banków)
PKO BP, mBank, Santander, ING, BNP Paribas, Alior, BOŚ, Credit Agricole, Getin, Millennium, Citi Handlowy

**Brak**: Nest Bank, Pekao SA (niejasne)

#### Cechy

| Aspekt | Szczegóły |
|--------|-----------|
| **Model** | Enterprise-focused |
| **Sandbox** | Darmowy |
| **Production** | Wymaga kontaktu z sales |
| **Licencja** | Działasz pod ich PSD2 |
| **Support** | Standard = brak SLA, Enterprise = SLA |

#### Plusy
- Przejrzysty pricing (€0.50/user)
- Visa backing = stabilność
- Działasz pod ich licencją

#### Minusy
- Enterprise-focused
- Mały klient = niski priorytet
- Wymaga kontaktu z sales
- Brak self-service do production

---

### 5. Yapily

**Strona**: https://www.yapily.com/
**Pricing**: https://www.yapily.com/pricing

#### Opis
UK-based platforma Open Banking z silną pozycją w EU.

#### Cechy

| Aspekt | Szczegóły |
|--------|-----------|
| **Region** | 19 krajów EU |
| **Banki** | 2000+ |
| **Polska** | ✅ PKO, mBank, Pekao, inne (~25M kont) |
| **Pricing** | Tiered: Free → "Get Set for Success" → Enterprise |

#### Pricing model
- **Free**: Sandbox only
- **Get Set for Success**: Base fee + usage-based
- **Enterprise**: Custom

#### Plusy
- Dobra dokumentacja
- Dedykowane wsparcie dla Polski
- Bez własnej licencji możliwe

#### Minusy
- Nieprzewidywalne koszty (usage-based)
- "Koszty często wyższe niż szacunki"
- Wymaga onboardingu

---

### 6. TrueLayer

**Strona**: https://truelayer.com/

#### Opis
UK-based lider Open Banking w Europie. Fokus na Pay by Bank.

#### Cechy

| Aspekt | Szczegóły |
|--------|-----------|
| **Region** | 22 kraje EU |
| **Polska** | ✅ Live (22. rynek), wspiera PLN |
| **Pricing** | Custom (kontakt z sales) |
| **Fokus** | Pay by Bank, instant payouts |
| **Coverage** | 95-99% kont w głównych rynkach |

#### Plusy
- Silna pozycja w EU
- Instant payouts w PLN
- 100% API-based (no scraping)

#### Minusy
- Brak publicznego pricingu
- Wymaga negocjacji
- Enterprise-focused

---

### 7. Plaid

**Strona**: https://plaid.com/
**EU Coverage**: https://plaid.com/docs/institutions/europe/

#### Pricing (US/Canada)

| Tier | Cena | Uwagi |
|------|------|-------|
| Pay as you go | Per-use | Brak minimum |
| Growth | Od $100/mies | Volume discounts |
| Scale | Od $500/mies | Custom |

#### EU/UK - UWAGA
> "For customers based in the EU or UK, **only Custom plans are available**"

Self-service Pay as you go NIE działa w EU. Trzeba kontaktować się z sales.

#### Plusy
- Świetna dokumentacja
- Szeroka coverage

#### Minusy
- EU = tylko custom pricing
- Nie self-service w EU

---

### 8. finAPI (Niemcy)

**Strona**: https://www.finapi.io/
**Pricing**: https://www.finapi.io/en/prices/

#### Pricing

| Element | Cena |
|---------|------|
| Minimum | €250/miesiąc |
| Per user | €0.27-0.30 (volume-based) |
| Trial | 30 dni darmowe |

**Przykład**: 5001 userów = €300 + (4000 × €0.30) + (1 × €0.27) = €1,500.27

#### Cechy

| Aspekt | Szczegóły |
|--------|-----------|
| **Region** | Niemcy, Austria (DACH) |
| **Polska** | ❌ NIE |
| **Licencja** | BaFin licensed |
| **API calls** | 13M+ dziennie |

#### Plusy
- Transparentny pricing
- 30 dni trial

#### Minusy
- **Brak Polski**
- Tylko DACH region

---

### 9. Pozostali providerzy

#### Finexer (UK only)
- **Region**: Tylko UK
- **Model**: Usage-based, no monthly minimums
- **Claim**: "90% taniej niż enterprise providers"
- **Polska**: ❌ NIE

#### Noda
- **Region**: EU, UK, Brazylia, Kanada
- **Banki**: 2000+
- **Fokus**: Płatności A2A + dane
- **Acceptance rate**: 90%

#### Swan
- **Minimum**: €900/miesiąc
- **Dla**: Enterprise only

#### KIR PSD2 HUB (Polska)
- Polski hub PSD2 dla banków
- **Wymaga**: Własnej licencji KNF
- Nie dla firm bez licencji

#### Open Bank Project
- **Model**: Open source, self-hosted
- **Koszt**: Darmowy (ale wymaga infrastruktury)
- **Dla**: Techniczne zespoły z budżetem na dev

---

### Zamknięte / Nieaktywne

| Provider | Status | Data |
|----------|--------|------|
| **Nordigen** | Zamknięty dla nowych | Lipiec 2025 |
| **GoCardless Bank Account Data** | = Nordigen | Lipiec 2025 |
| **Figo** | Bankructwo | 2020 |

---

## Payment Processors

### Stripe ⭐ REKOMENDOWANY

**Strona**: https://stripe.com/
**Pricing**: https://stripe.com/pricing

#### Opłaty

| Element | Cena |
|---------|------|
| Setup fee | €0 |
| Monthly fee | €0 |
| Karty EU (consumer) | 1.4% + €0.25 |
| Karty spoza EU | 2.9% + €0.25 + 1.5% cross-border |
| Stripe Billing (subskrypcje) | +0.7% |
| Currency conversion (PLN→EUR) | +2% |

#### Przykład dla €10/mies subskrypcji (EU)

| Składnik | Opłata |
|----------|--------|
| Transakcja (1.4% + €0.25) | €0.39 |
| Stripe Billing (0.7%) | €0.07 |
| **Razem** | **€0.46** |
| **Ty dostajesz** | **€9.54** |

#### Przykład dla €4/mies subskrypcji (EU)

| Składnik | Opłata |
|----------|--------|
| Transakcja (1.4% + €0.25) | €0.31 |
| Stripe Billing (0.7%) | - |
| **Razem** | **€0.31** |
| **% stracony** | **7.8%** |

#### Problem niskiej ceny subskrypcji
Przy €4/mies opłata stała €0.25 stanowi już 6.25% - boli.

**Rozwiązania**:
1. Roczna subskrypcja (€40) → tylko 2% opłat
2. Cena €5 zamiast €4 → 6.4% zamiast 7.8%
3. SEPA Direct Debit (Mollie) → €0.25 flat

#### Co dostajesz za darmo
- Konto Stripe
- Dashboard
- Integracja API
- Checkout pages
- Customer portal
- Webhooks
- Sandbox (testowe środowisko)
- Faktury automatyczne

---

### Alternatywy dla Stripe

| Provider | Karty EU | Monthly fee | Subskrypcje | Uwagi |
|----------|----------|-------------|-------------|-------|
| **Stripe** | 1.4% + €0.25 | €0 | +0.7% | Standard dla SaaS |
| **Mollie** | 1.8% + €0.25 | €0 | Wbudowane | Brak extra za subs |
| **PayU PL** | ~1.9-2% + 1 PLN | €0 | ⚠️ Ograniczone | Polski |
| **Przelewy24** | Custom + 1 PLN | €0 | ✅ BLIK | Polski, recurring |
| **Paddle** | 5% + $0.50 | €0 | ✅ | MoR - oni rozliczają VAT |
| **LemonSqueezy** | 5% + $0.50 | €0 | ✅ | MoR - prostszy |

#### Porównanie przy €4/mies

| Provider | Opłata | Ty dostajesz | % stracony |
|----------|--------|--------------|------------|
| Stripe | €0.31 | €3.69 | 7.8% |
| Mollie | €0.32 | €3.68 | 8.0% |
| Paddle | €0.66 | €3.34 | 16.5% |

#### Merchant of Record (MoR)
Paddle i LemonSqueezy są droższe (5%), ale:
- Oni rozliczają VAT we wszystkich krajach EU
- Ty dostajesz czystą kwotę
- Zero papierologii VAT

---

## Rekomendacje

### Open Banking - Plan działania

| Priorytet | Provider | Działanie |
|-----------|----------|-----------|
| 1 | **Salt Edge** | Zarejestruj się, 90 dni test z live data |
| 2 | **Kontomatik** | Napisz po wycenę (polski provider) |
| 3 | **CSV Import** | Rozwijaj równolegle jako fallback |
| 4 | **Aiia/Mastercard** | Backup jeśli 1-2 nie wypalą |

### Payment Processor - Rekomendacja

**Stripe** - dla startu SaaS:
- Brak opłat stałych = zero ryzyka
- Świetne API i dokumentacja
- Stripe Billing do subskrypcji

**Strategie optymalizacji przy niskiej cenie (€4)**:
1. Oferuj plan roczny z rabatem → 2% zamiast 8%
2. Rozważ €5 zamiast €4 → lepszy margin
3. BLIK/P24 dla polskich klientów

---

## Szablony maili do providerów

### Salt Edge - Polski

```
Temat: Zapytanie o Salt Edge Partner Program - mały SaaS z Polski

Dzień dobry,

Reprezentuję [NAZWA FIRMY], polską firmę rozwijającą aplikację SaaS do zarządzania
przepływami pieniężnymi (Cash Flow Forecasting).

Jestem zainteresowany Salt Edge Partner Program i mam kilka pytań:

1. PRICING
   - Jaki jest cennik dla Live mode?
   - Czy jest minimalny wolumen/opłata miesięczna?
   - Jak wygląda pricing przy małej skali (~10-100 użytkowników na start)?

2. POLSKA COVERAGE
   - Które polskie banki są wspierane?
   - Czy są wspierane: PKO BP, mBank, Pekao SA, ING, Santander, Nest Bank?
   - Jaka jest jakość/stabilność połączeń z polskimi bankami?

3. INTEGRACJA
   - Ile trwa typowy proces od Test do Live?
   - Czy są jakieś specjalne wymagania dla firm z Polski?

4. BILLING MODEL
   - Czy płacę per connection, per API call, czy per user?
   - Jak rozliczane są nieaktywne connections?

O NAS:
- Polska działalność gospodarcza
- Aplikacja: Personal Finance / Cash Flow Management
- Oczekiwana skala na start: 10-100 użytkowników
- Docelowo: kilkaset użytkowników

Czy moglibyście przesłać ofertę cenową lub umówić się na krótką rozmowę?

Z poważaniem,
[IMIĘ NAZWISKO]
[NAZWA FIRMY]
[EMAIL]
[TELEFON]
```

### Salt Edge - English

```
Subject: Salt Edge Partner Program Inquiry - Small SaaS from Poland

Hello,

I represent [COMPANY NAME], a Polish company developing a SaaS application
for Cash Flow Forecasting and Personal Finance Management.

I'm interested in the Salt Edge Partner Program and have several questions:

1. PRICING
   - What is the pricing structure for Live mode?
   - Is there a minimum volume or monthly fee?
   - What does pricing look like at small scale (~10-100 users initially)?

2. POLAND COVERAGE
   - Which Polish banks are supported?
   - Are these banks supported: PKO BP, mBank, Pekao SA, ING, Santander, Nest Bank?
   - What is the connection quality/stability with Polish banks?

3. INTEGRATION
   - What is the typical timeline from Test to Live status?
   - Are there any special requirements for companies from Poland?

4. BILLING MODEL
   - Is billing per connection, per API call, or per user?
   - How are inactive connections billed?

ABOUT US:
- Polish registered business
- Application: Personal Finance / Cash Flow Management
- Expected initial scale: 10-100 users
- Target: several hundred users

Could you please provide a pricing quote or schedule a brief call?

Best regards,
[NAME]
[COMPANY NAME]
[EMAIL]
[PHONE]
```

---

### Kontomatik - Polski

```
Temat: Zapytanie o współpracę - SaaS do zarządzania finansami

Dzień dobry,

Nazywam się [IMIĘ NAZWISKO] i rozwijam aplikację SaaS do zarządzania
przepływami pieniężnymi dla użytkowników indywidualnych w Polsce.

Jestem zainteresowany integracją z Kontomatik i chciałbym poznać warunki współpracy.

PYTANIA:

1. CENNIK
   - Jaki jest model cenowy (per session, per user, miesięczny)?
   - Czy jest minimalna opłata miesięczna?
   - Jak wygląda pricing dla małego startupu (~10-100 użytkowników)?

2. POLSKIE BANKI
   - Które banki są wspierane?
   - Czy macie: PKO BP, mBank, Pekao SA, ING, Santander, Millennium, Nest Bank?
   - Jak często aktualizujecie integracje z bankami?

3. FUNKCJONALNOŚCI
   - Czy dostępna jest kategoryzacja transakcji?
   - Czy mogę pobierać historię transakcji (jak daleko wstecz)?
   - Czy wspieracie konta firmowe czy tylko osobiste?

4. INTEGRACJA
   - Jak wygląda proces onboardingu?
   - Czy jest sandbox/demo do testów?
   - Ile trwa typowa integracja?

5. WYMAGANIA PRAWNE
   - Czy potrzebuję własnej licencji AISP?
   - Jak wygląda kwestia RODO i zgód użytkowników?

O PROJEKCIE:
- Aplikacja: Cash Flow Forecasting / Personal Finance
- Target: użytkownicy indywidualni w Polsce
- Skala na start: 10-100 użytkowników
- Model: SaaS z miesięczną subskrypcją (~20 PLN/mies)

Jako polska firma rozwijająca produkt dla polskiego rynku, bardzo zależy mi
na współpracy z lokalnym dostawcą, który rozumie specyfikę naszego rynku.

Czy moglibyśmy umówić się na krótką rozmowę lub demo?

Z poważaniem,
[IMIĘ NAZWISKO]
[NAZWA FIRMY]
[NIP]
[EMAIL]
[TELEFON]
```

---

### Tink - English

```
Subject: Tink API Access Inquiry - Small SaaS Startup from Poland

Hello Tink Team,

I'm [NAME], founder of [COMPANY NAME], a Polish startup building a Cash Flow
Forecasting SaaS application.

I'm interested in using Tink for Open Banking connectivity and would like to
understand if Tink is a good fit for a small business like mine.

QUESTIONS:

1. PRODUCTION ACCESS
   - Can a small business (Polish sole proprietorship) get production access?
   - What is the process from sandbox to production?
   - Is there a minimum user/volume requirement?

2. PRICING
   - I understand Standard tier is €0.50/user/month - is this correct?
   - Are there any setup fees or monthly minimums?
   - Is Standard tier available for small businesses or only Enterprise?

3. POLAND COVERAGE
   - Which Polish banks do you support?
   - I need: PKO BP, mBank, Pekao SA, ING, Santander, Nest Bank
   - What is the data refresh frequency for Polish banks?

4. LICENSING
   - Can I operate under Tink's PSD2 license?
   - What are the compliance requirements for my business?

5. INTEGRATION
   - What is the typical timeline to go live?
   - What support is available for Standard tier customers?

ABOUT US:
- Polish registered business (sole proprietorship)
- Product: Cash Flow Forecasting SaaS
- Target market: Polish consumers
- Expected scale: 10-100 users initially, growing to 500+
- We're prepared to pay €0.50/user/month

I've already tested in sandbox and the integration looks straightforward.
I'd appreciate guidance on whether Tink serves small businesses like mine.

Best regards,
[NAME]
[COMPANY NAME]
[EMAIL]
[PHONE]
```

---

### Aiia / Mastercard - English

```
Subject: Mastercard Open Banking (Aiia) - Small Business Inquiry

Hello,

I'm exploring Mastercard Open Banking (Aiia) for my SaaS application and
would like to understand the options for small businesses.

ABOUT MY PROJECT:
- Company: Polish registered business
- Product: Cash Flow Forecasting / Personal Finance Management SaaS
- Target market: Poland (expanding to EU)
- Scale: Starting with 10-100 users

QUESTIONS:

1. UNLICENSED PATH
   - Can I use Aiia Data/Aiia Pay without my own PSD2 license?
   - What are the requirements to operate under Mastercard's license?

2. PRICING
   - What is the pricing model for small businesses?
   - Is there a minimum volume or monthly fee?
   - Are there setup costs?

3. PRODUCTION ACCESS
   - What is the process from sandbox to production?
   - What documentation/verification is required?
   - Typical timeline?

4. POLAND COVERAGE
   - Which Polish banks are supported?
   - What data is available (transactions, balances, account details)?

5. SUPPORT
   - What level of support is available for small businesses?
   - Is there documentation for the unlicensed path?

I'm particularly interested in the "unlicensed" path as I don't have
my own AISP/PISP license.

Thank you for your assistance.

Best regards,
[NAME]
[COMPANY NAME]
[EMAIL]
```

---

### Yapily - English

```
Subject: Yapily Pricing Inquiry - Polish SaaS Startup

Hello Yapily Team,

I'm building a Cash Flow Forecasting SaaS and I'm evaluating Yapily
for Open Banking connectivity in Poland.

QUESTIONS:

1. PRICING FOR SMALL BUSINESS
   - What is the pricing structure for small startups?
   - Is there a minimum monthly fee?
   - What does pricing look like for ~50-100 active users?

2. POLAND COVERAGE
   - I saw you support Poland with ~25M accounts
   - Which specific banks are supported?
   - PKO BP, mBank, Pekao SA, ING, Santander, Nest Bank?

3. "GET SET FOR SUCCESS" TIER
   - What are the base costs?
   - How does usage-based pricing work?
   - Can you provide an example calculation?

4. LICENSING
   - Can I operate without my own PSD2 license?
   - What compliance requirements apply?

5. INTEGRATION
   - Typical timeline to production?
   - What onboarding support is provided?

ABOUT US:
- Polish registered business
- Cash Flow Forecasting SaaS
- Target: Polish consumers initially
- Budget: ~€0.50/user/month

Looking forward to hearing from you.

Best regards,
[NAME]
[COMPANY NAME]
[EMAIL]
```

---

## Changelog

| Data | Zmiana |
|------|--------|
| 2026-02-07 | Utworzenie dokumentu |
| 2026-02-07 | Dodanie porównania providerów |
| 2026-02-07 | Dodanie sekcji Payment Processors |
| 2026-02-07 | Dodanie szablonów maili |

---

## Źródła

### Open Banking Providers
- Salt Edge: https://www.saltedge.com/
- Kontomatik: https://www.kontomatik.com/
- Tink: https://tink.com/
- Yapily: https://www.yapily.com/
- TrueLayer: https://truelayer.com/
- Plaid: https://plaid.com/
- Aiia/Mastercard: https://openbankingeu.mastercard.com/
- finAPI: https://www.finapi.io/

### Payment Processors
- Stripe: https://stripe.com/pricing
- Mollie: https://www.mollie.com/pricing
- PayU Poland: https://poland.payu.com/pricing/
- Przelewy24: https://www.przelewy24.pl/en/offer/commissions-and-fees

### Porównania i analizy
- Open Banking Tracker: https://www.openbankingtracker.com/
- Finexer Blog: https://blog.finexer.com/
