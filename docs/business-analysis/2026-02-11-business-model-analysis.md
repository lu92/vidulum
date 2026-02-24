# Analiza modelu biznesowego - CashFlow vs Portfolio

**Data:** 2026-02-11
**Status:** Analiza strategiczna

---

## Porównanie modeli biznesowych

### Podstawowe parametry:

| Model | Klient | Cena/msc | Konwersja | LTV* |
|-------|--------|----------|-----------|------|
| **CashFlow B2B** | Firma | 200-2000 zł | Trudna (sales) | Wysoki |
| **CashFlow B2C** | Osoba | 20-50 zł | Łatwiejsza | Niski |
| **Portfolio B2C** | Inwestor | 0-30 zł | Łatwa | Bardzo niski |
| **All-in-one B2C** | Osoba + inwestor | 30-100 zł | Średnia | Średni |

*LTV = Lifetime Value (ile zarobisz na kliencie)

---

## Matematyka przychodów

### Scenariusz A: CashFlow B2B (firmy)
```
100 klientów × 500 zł/msc = 50,000 zł/msc
Trudność pozyskania: WYSOKA (sales, demo, negocjacje)
Churn: NISKI (firmy nie zmieniają łatwo)
```

### Scenariusz B: CashFlow B2C (osoby)
```
1000 klientów × 30 zł/msc = 30,000 zł/msc
Trudność pozyskania: ŚREDNIA (marketing, SEO)
Churn: ŚREDNI
```

### Scenariusz C: Portfolio B2C (inwestorzy)
```
5000 klientów × 15 zł/msc = 75,000 zł/msc
Trudność pozyskania: NISKA (virality, crypto community)
Churn: WYSOKI (rynek spada = odchodzą)
```

### Scenariusz D: All-in-one B2C
```
2000 klientów × 50 zł/msc = 100,000 zł/msc
Trudność pozyskania: ŚREDNIA
Churn: NISKI (więcej funkcji = sticky)
```

---

## Ryzyko vs Zysk

| Model | Potencjał zysku | Ryzyko | Trudność |
|-------|-----------------|--------|----------|
| **B2B CashFlow** | 💰💰💰 | Niskie | Wysoka |
| **B2C CashFlow** | 💰💰 | Niskie | Średnia |
| **B2C Portfolio** | 💰💰💰 | **WYSOKIE** (bear market) | Niska |
| **All-in-one B2C** | 💰💰💰💰 | Średnie | Średnia |

---

## Kluczowe wnioski

| Fakt | Implikacja |
|------|------------|
| Crypto = boom & bust | Portfolio users odchodzą w bear market |
| CashFlow = zawsze potrzebny | Stabilni klienci przez cały rok |
| B2B = wolniejszy growth | Ale wyższa marża i retencja |
| All-in-one = sticky | User ma wszystko, nie odejdzie łatwo |

---

## CoinGecko i Delta - czego NIE kopiować

### Co oni mają (i robią dobrze):

| Funkcja | CoinGecko | Delta |
|---------|-----------|-------|
| Live prices API | ✅ | ✅ |
| Sync z giełdami (API) | ❌ | ✅ |
| 15,000+ coinów | ✅ | ✅ |
| Mobile app | 🔶 | ✅ |
| Tax reports | ❌ | ✅ |
| News/kalendarz | ✅ | ✅ |
| NFT/DeFi | ✅ | 🔶 |

### Czego oni NIE mają (Twoja przewaga):

| Funkcja | CoinGecko | Delta | Vidulum |
|---------|-----------|-------|---------|
| **Bank integration (Open Banking)** | ❌ | ❌ | ✅ |
| **CashFlow forecasting** | ❌ | ❌ | ✅ |
| **Budżetowanie** | ❌ | ❌ | ✅ |
| **Recurring transactions** | ❌ | ❌ | ✅ |
| **Reconciliation** | ❌ | ❌ | ✅ |
| **B2B / firma** | ❌ | ❌ | ✅ |

### Rekomendacja - NIE buduj:
- Live prices (użyj API CoinGecko - darmowe)
- Sync z 50 giełdami (za dużo pracy)
- Tax reports (skomplikowane, różne kraje)
- NFT/DeFi tracking

### TAK buduj:
- Prosty portfolio tracker (CSV import)
- Połączenie z CashFlow ("Twój majątek = bank + crypto")
- Całościowy widok finansów osoby/firmy

**Unikalna wartość: "Zobacz WSZYSTKIE swoje pieniądze w jednym miejscu - bank, gotówka, crypto, inwestycje"**

---

## Rekomendowana strategia: Hybrid B2C + B2B

### Faza 1 (teraz):
```
├── CashFlow B2C (osoby) - łatwiejsze pozyskanie
├── + Portfolio jako dodatek (sticky users)
└── Cena: Free → 30-50 zł/msc Pro
```

### Faza 2 (później):
```
├── CashFlow B2B (firmy) - wyższa marża
├── Osobny pricing tier: 200-500 zł/msc
└── Funkcje: multi-user, API, audit trail
```

### Faza 3 (skala):
```
├── Enterprise B2B: 1000+ zł/msc
└── White-label dla biur rachunkowych
```

---

## Ranking modeli - gdzie NAJWIĘCEJ pieniędzy

| Ranking | Model | Dlaczego |
|---------|-------|----------|
| 🥇 | **B2B CashFlow** | Najwyższa cena, najniższy churn |
| 🥈 | **All-in-one B2C** | Duży rynek, sticky users |
| 🥉 | **B2C CashFlow** | Stabilny, ale niska cena |
| 4 | **B2C Portfolio** | Wysokie ryzyko (bear market = 0 userów) |

---

## Podsumowanie

| Jeśli chcesz... | Wybierz |
|-----------------|---------|
| Szybko zacząć zarabiać | B2C CashFlow + Portfolio |
| Maksymalny zysk długoterminowo | B2B CashFlow |
| Niskie ryzyko | CashFlow (bez Portfolio jako głównego) |
| Wszystko naraz | Hybrid: B2C start → B2B scale |

**Rekomendacja: Zacznij od B2C (łatwiej pozyskać), ale buduj z myślą o B2B (tam są pieniądze).**

---

## Połączenie CashFlow + Portfolio - sens biznesowy

### Kiedy MA sens łączyć:

| Scenariusz | Sens? | Przykład |
|------------|-------|----------|
| **Inwestor indywidualny** | ✅ TAK | "Mam budżet domowy + inwestuję w crypto" |
| **Freelancer / JDG** | ✅ TAK | "Mam firmę + inwestuję prywatnie" |
| **Mała firma + właściciel** | 🔶 Może | "Finanse firmy + moje prywatne inwestycje" |
| **Średnia/duża firma** | ❌ NIE | CFO nie śledzi crypto w tej samej apce |

### Strategia produktowa:

```
Główny produkt: CashFlow (B2B + B2C)
│
├── Free tier: Budżet osobisty
├── Pro tier: Zaawansowane prognozy, multi-konta
├── Business tier: Firma, reconciliation, API
│
└── Bonus moduł: Portfolio tracking
    ├── Dla Pro/Business userów
    ├── Crypto + akcje (CSV import)
    └── Prosty PnL, bez zaawansowanej analityki
```

### Korzyści połączenia:

| Korzyść | Opis |
|---------|------|
| **Upsell** | Free → Pro "chcesz też śledzić inwestycje?" |
| **Sticky users** | Więcej danych = trudniej odejść |
| **Unikalność** | Konkurencja ma albo cashflow ALBO portfolio, nie oba |
| **Cross-sell** | Inwestor przychodzi na Portfolio, zostaje na CashFlow |

### Czego NIE robić:

| ❌ | Dlaczego |
|----|----------|
| Mieszać UI firmy z crypto | CFO nie chce widzieć "Bitcoin +5%" |
| Reklamować jako "wszystko w jednym" | Rozmywa przekaz |
| Budować zaawansowaną analitykę portfolio | Za dużo pracy, są lepsi (CoinGecko, Delta) |

---

## Wymagania B2B - Co potrzeba żeby sprzedawać firmom

### Obecny stan vs wymagania B2B:

| Funkcja | Masz? | B2B wymaga? | Priorytet |
|---------|-------|-------------|-----------|
| CashFlow tracking | ✅ TAK | ✅ | - |
| Kategorie (nested) | ✅ TAK | ✅ | - |
| CSV import | ✅ TAK | ✅ | - |
| Forecasting | ✅ TAK | ✅ | - |
| Bank integration (Open Banking) | 🔶 Planujesz | ✅ **KRYTYCZNE** | 🔴 |
| Recurring transactions | 🔶 Częściowo? | ✅ **KRYTYCZNE** | 🔴 |
| Multi-user / zespół | ❌ NIE | ✅ **WAŻNE** | 🟡 |
| Role i uprawnienia | ❌ NIE | ✅ **WAŻNE** | 🟡 |
| Audit trail / historia zmian | ❌ NIE | ✅ **WAŻNE** | 🟡 |
| Debt management | ❌ NIE | 🔶 Nice-to-have | 🟢 |
| Raporty / eksport | 🔶 Częściowo? | ✅ **WAŻNE** | 🟡 |
| API dla integracji | ❌ NIE | 🔶 Enterprise | 🟢 |
| Multi-company | ❌ NIE | 🔶 Enterprise | 🟢 |
| White-label | ❌ NIE | 🔶 Enterprise | 🟢 |

---

## Minimum dla B2B (MVP)

### 🔴 MUST HAVE (bez tego firma nie kupi):

```
├── Open Banking integration (finAPI/Enable Banking/Kontomatik)
├── Recurring inflow/outflow (automatyczne prognozy)
├── Multi-user (właściciel + księgowa)
└── Podstawowe raporty (PDF/Excel export)
```

### 🟡 SHOULD HAVE (zwiększa konwersję):

```
├── Role (Admin, Viewer, Editor)
├── Audit trail (kto co zmienił)
├── Reconciliation (dopasowanie faktur do płatności)
└── Budżetowanie per kategoria
```

### 🟢 NICE TO HAVE (Enterprise tier):

```
├── Debt management
├── API access
├── Multi-company
├── White-label
└── SSO / SAML
```

---

## Recurring inflow/outflow - szczegóły

### Co to znaczy dla B2B:

| Typ | Przykład | Co system robi |
|-----|----------|----------------|
| **Recurring INFLOW** | Faktura miesięczna od klienta | Auto-tworzy prognozę przychodu |
| **Recurring OUTFLOW** | Czynsz, leasing, pensje | Auto-tworzy prognozę kosztu |
| **Reguły** | "Każdy 10-ty dzień miesiąca" | Powtarzalność |
| **Alerty** | "Brak wpłaty od klienta X" | Powiadomienia o opóźnieniach |

**Dlaczego KRYTYCZNE:** Firma chce widzieć "jak będzie wyglądał cash flow za 3-6 miesięcy" - bez recurring to niemożliwe.

### Przykładowe reguły recurring:

```
Inflow:
- Klient ABC: 10,000 zł, 15-ty dzień miesiąca
- Klient XYZ: 5,000 zł, ostatni dzień miesiąca

Outflow:
- Czynsz biura: 3,000 zł, 1-szy dzień miesiąca
- Pensje: 50,000 zł, 10-ty dzień miesiąca
- Leasing auto: 1,500 zł, 5-ty dzień miesiąca
- Abonament AWS: 2,000 zł, 1-szy dzień miesiąca
```

---

## Debt management - szczegóły

### Co to obejmuje:

| Funkcja | Opis | Priorytet B2B |
|---------|------|---------------|
| Lista zobowiązań | Kredyty, leasingi, pożyczki | 🟡 Średni |
| Harmonogram spłat | Kiedy, ile, do kogo | 🟡 Średni |
| Integracja z CashFlow | Auto-outflow w prognozach | 🟡 Średni |
| Alerty o ratach | "Za 5 dni rata leasingu" | 🟢 Niski |
| Symulacje | "Co jeśli wezmę nowy kredyt?" | 🟢 Niski |

**Wniosek:** Debt management to nice-to-have, nie blocker dla B2B MVP.

---

## Open Banking - strategia pokrycia

### Dla Polski:

| Provider | Pokrycie | Startup-friendly | Status |
|----------|----------|------------------|--------|
| **Kontomatik** | 95%+ banków PL | ✅ TAK | Gotowy |
| **Enable Banking** | TAK (29 krajów) | 🔶 Zapytanie wysłane | Czekamy |

### Dla ekspansji międzynarodowej (DACH):

| Provider | Pokrycie | Startup-friendly | Status |
|----------|----------|------------------|--------|
| **finAPI** | DE 99%, AT 95%, CZ 82% | ✅ TAK (€60/msc) | Gotowy |

### Strategia:

```
Faza 1 (Polska): Kontomatik lub Enable Banking
Faza 2 (DACH):   finAPI dla DE/AT/CZ
Faza 3 (EU):     Enable Banking dla reszty EU
```

### finAPI - dlaczego dobry dla B2B:

| Cecha | Wartość dla B2B |
|-------|-----------------|
| 1 user = unlimited kont | Firma z 5 kontami = 1 opłata |
| Unlimited importów | Codzienne sync bez dodatkowych kosztów |
| 99% pokrycie DE | Prawie każdy bank niemieckiej firmy |
| Publiczny cennik | Łatwo zabudżetować |

---

## Multi-user - co potrzeba

### Role dla B2B:

| Rola | Uprawnienia | Kto |
|------|-------------|-----|
| **Owner/Admin** | Wszystko + billing + zarządzanie userami | Właściciel firmy |
| **Editor** | CRUD transakcji, kategorii, recurring | Księgowa, CFO |
| **Viewer** | Tylko odczyt, raporty | Manager, inwestor |

### Minimalny scope:

```
- Invite user by email
- Assign role (Admin/Editor/Viewer)
- Remove user
- Audit: kto co zmienił i kiedy
```

---

## Raporty - co potrzeba

### Minimum dla B2B:

| Raport | Format | Opis |
|--------|--------|------|
| **Cash Flow Statement** | PDF/Excel | Przychody vs wydatki per miesiąc |
| **Forecast Report** | PDF/Excel | Prognoza na 3-6-12 miesięcy |
| **Category Breakdown** | PDF/Excel | Wydatki per kategoria |
| **Bank Reconciliation** | PDF/Excel | Status dopasowania transakcji |

### Nice-to-have:

```
- Customizable date range
- Porównanie YoY (rok do roku)
- Budżet vs actual
- Scheduled email reports
```

---

## Roadmap B2B - priorytety implementacji

| Faza | Co budować | Czas | Efekt |
|------|------------|------|-------|
| **1** | Recurring transactions (rules engine) | 2-3 tyg | Automatyczne prognozy |
| **2** | Open Banking (Kontomatik lub Enable) | 3-4 tyg | Automatyczny import |
| **3** | Multi-user + role | 2 tyg | Zespół może używać |
| **4** | Raporty PDF/Excel | 1-2 tyg | CFO ma co pokazać |
| **5** | Audit trail | 1 tyg | Compliance |
| **6** | finAPI (DE/AT/CZ) | 2-3 tyg | Ekspansja międzynarodowa |
| **7** | Debt management | 2-3 tyg | Dodatkowa wartość |
| **8** | API access | 2-3 tyg | Enterprise tier |

**Szacowany czas do B2B MVP (fazy 1-5): ~10-12 tygodni**

---

## Pricing B2B

| Tier | Cena/msc | Co zawiera |
|------|----------|------------|
| **Starter** | 99-149 zł | 1 user, 2 konta bankowe, recurring, raporty |
| **Business** | 299-499 zł | 5 userów, unlimited konta, Open Banking, audit |
| **Enterprise** | 999+ zł | Unlimited, API, multi-company, debt mgmt, SLA |

### Porównanie z kosztami Open Banking:

| Tier | Cena | Koszt Open Banking | Marża |
|------|------|-------------------|-------|
| Starter (2 konta) | 149 zł | ~12 zł (Kontomatik) | 137 zł |
| Business (10 kont) | 499 zł | ~60 zł (Kontomatik) | 439 zł |
| Enterprise (50 kont) | 1499 zł | ~300 zł (Kontomatik) | 1199 zł |

---

## Checklist B2B - przed startem sprzedaży

### MVP (musisz mieć):

- [ ] Open Banking integration (Kontomatik lub Enable Banking)
- [ ] Recurring transactions z rules engine
- [ ] Multi-user z rolami (Admin/Editor/Viewer)
- [ ] Podstawowe raporty (PDF/Excel export)
- [ ] Audit trail (kto co zmienił)

### Dla ekspansji międzynarodowej:

- [ ] finAPI integration (DE/AT/CZ)
- [ ] Multi-currency support
- [ ] Lokalizacja (DE, EN)

### Dla Enterprise:

- [ ] Debt management
- [ ] API access
- [ ] Multi-company
- [ ] White-label
- [ ] SSO/SAML

---

## Podatki w B2B - strategia

### Czy musisz budować moduł podatkowy?

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy firmy tego oczekują? | 🔶 Częściowo |
| Czy to blocker dla B2B? | ❌ NIE |
| Czy to skomplikowane? | ✅ BARDZO |
| Czy konkurencja to ma? | 🔶 Niektórzy |

### Co firmy robią z podatkami:

| Rozmiar firmy | Kto robi podatki | Czego potrzebują od Ciebie |
|---------------|------------------|---------------------------|
| **Mikro (JDG)** | Sam właściciel lub księgowa | Eksport danych do JPK/Excel |
| **Mała (do 10 os.)** | Biuro rachunkowe | Eksport danych |
| **Średnia (10-50)** | Księgowość wewnętrzna + system ERP | API / integracja |
| **Duża (50+)** | Dział finansowy + SAP/Oracle | Nie użyją Twojej apki do podatków |

**Wniosek:** Firmy NIE oczekują, że Twoja apka zrobi im deklaracje VAT. Oczekują **eksportu danych** do ich systemu księgowego.

### Co możesz zrobić (proste):

| Funkcja | Trudność | Wartość B2B |
|---------|----------|-------------|
| **Eksport do Excel/CSV** | ✅ Łatwe | Wysoka |
| **Kategorie zgodne z planem kont** | ✅ Łatwe | Wysoka |
| **Oznaczenie VAT (23%, 8%, 0%, ZW)** | 🔶 Średnie | Średnia |
| **Eksport JPK-ready** | 🔶 Średnie | Średnia (tylko PL) |
| **Integracja z systemami księgowymi** | 🔴 Trudne | Wysoka |

### Czego NIE robić:

| ❌ Funkcja | Dlaczego |
|-----------|----------|
| Generowanie deklaracji VAT | Za skomplikowane, różne kraje |
| Obliczanie podatku dochodowego | Każda firma ma inną sytuację |
| CIT/PIT kalkulacje | Wymaga wiedzy księgowej |
| Pełna księgowość | To nie jest Twój produkt |

### Rekomendacja: "Tax-friendly" nie "Tax software"

```
Twoja apka:
├── Śledzi cash flow (przychody/wydatki)
├── Kategoryzuje transakcje
├── Eksportuje dane w formacie dla księgowej
└── NIE robi deklaracji podatkowych

Księgowa/Biuro rachunkowe:
├── Importuje dane z Twojej apki
├── Robi faktyczną księgowość
└── Składa deklaracje
```

### Minimum dla B2B (podatki):

| Funkcja | Priorytet | Opis |
|---------|-----------|------|
| **Eksport Excel z kategoriami** | 🔴 Must | Księgowa może zaimportować |
| **Custom kategorie** | 🔴 Must | Firma dopasuje do planu kont |
| **Pole "VAT rate"** | 🟡 Should | Opcjonalne oznaczenie stawki |
| **Pole "Kontrahent"** | 🟡 Should | Kto zapłacił/komu płacono |
| **Pole "Nr faktury"** | 🟡 Should | Powiązanie z dokumentem |

### Integracje z systemami księgowymi (później - Faza 3):

| System | Rynek | Trudność | Priorytet |
|--------|-------|----------|-----------|
| **wFirma** | PL | 🔶 Średnia | 🟡 |
| **inFakt** | PL | 🔶 Średnia | 🟡 |
| **Fakturownia** | PL | 🔶 Średnia | 🟡 |
| **DATEV** | DE | 🔴 Trudna | 🟢 (dla ekspansji DE) |
| **Xero** | Global | 🔶 Średnia | 🟢 |
| **QuickBooks** | Global | 🔶 Średnia | 🟢 |

### Podsumowanie podatki:

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy budować moduł podatkowy? | ❌ NIE teraz |
| Co zrobić zamiast tego? | ✅ Dobry eksport danych |
| Kiedy integracje księgowe? | 🟢 Faza 3 (Enterprise) |
| Czy to blocker dla B2B MVP? | ❌ NIE |

**Firmy kupią Twój produkt na cash flow forecasting + Open Banking. Podatki robi im księgowa - Ty tylko dajesz dane.**

---

## Cash Flow Statement - czy warto produkować dokument?

### Co to jest Cash Flow Statement?

Oficjalny raport finansowy pokazujący przepływy pieniężne w 3 kategoriach:

```
1. Działalność operacyjna (Operating)
   - Przychody ze sprzedaży
   - Koszty operacyjne
   - Pensje, czynsz, materiały

2. Działalność inwestycyjna (Investing)
   - Zakup/sprzedaż sprzętu
   - Inwestycje w inne firmy

3. Działalność finansowa (Financing)
   - Kredyty (zaciągnięcie/spłata)
   - Dywidendy
   - Emisja akcji
```

### Kto tego potrzebuje?

| Klient | Potrzebuje? | Dlaczego |
|--------|-------------|----------|
| **Osoba prywatna (B2C)** | ❌ NIE | Nie wie co to, nie potrzebuje |
| **Mikro firma (JDG)** | 🔶 Może | Prostsza wersja wystarczy |
| **Mała firma** | ✅ TAK | Bank/inwestor może wymagać |
| **Średnia firma** | ✅ TAK | Zarząd chce widzieć, compliance |
| **Startup szukający funding** | ✅ **BARDZO** | Inwestor ZAWSZE pyta o cash flow |
| **Biuro rachunkowe** | ✅ TAK | Robi to dla klientów |

### Wartość biznesowa:

| Korzyść | Opis |
|---------|------|
| **Differentiator** | Konkurencja (budżetówki) tego NIE ma |
| **B2B upsell** | "Chcesz raport dla banku? → Business tier" |
| **Profesjonalizm** | Pokazuje że to poważne narzędzie |
| **Sticky users** | Firma przyzwyczaja się do Twoich raportów |

### Poziomy raportu:

| Poziom | Co zawiera | Trudność | Dla kogo |
|--------|------------|----------|----------|
| **Basic** | Przychody vs Wydatki per miesiąc | ✅ Łatwe | B2C, mikro |
| **Standard** | + Podział na kategorie, trend | 🔶 Średnie | Mała firma |
| **Professional** | + Operating/Investing/Financing | 🔶 Średnie | Średnia firma, startup |
| **Auditable** | + Porównanie YoY, notes, zgodność z MSSF | 🔴 Trudne | Enterprise |

### Roadmap Cash Flow Statement:

| Faza | Co budować | Priorytet |
|------|------------|-----------|
| **MVP** | Basic: Przychody vs Wydatki (PDF/Excel) | 🔴 Must |
| **B2B** | Standard: + kategorie, trend 3-6 msc | 🟡 Should |
| **Enterprise** | Professional: Operating/Investing/Financing | 🟢 Nice |

### Przykład raportu (Standard):

```
═══════════════════════════════════════════════════
         CASH FLOW STATEMENT
         Firma XYZ Sp. z o.o.
         Styczeń - Marzec 2026
═══════════════════════════════════════════════════

SALDO POCZĄTKOWE (1 sty 2026):     50,000 PLN

WPŁYWY (INFLOWS):
  Przychody ze sprzedaży           +120,000 PLN
  Zwroty podatku                    +5,000 PLN
  Inne wpływy                       +2,000 PLN
  ─────────────────────────────────────────────
  SUMA WPŁYWÓW:                   +127,000 PLN

WYPŁYWY (OUTFLOWS):
  Wynagrodzenia                    -45,000 PLN
  Czynsz i media                   -12,000 PLN
  Marketing                         -8,000 PLN
  Materiały                        -15,000 PLN
  Podatki                          -10,000 PLN
  Inne koszty                       -5,000 PLN
  ─────────────────────────────────────────────
  SUMA WYPŁYWÓW:                   -95,000 PLN

═══════════════════════════════════════════════════
PRZEPŁYW NETTO:                    +32,000 PLN
SALDO KOŃCOWE (31 mar 2026):       82,000 PLN
═══════════════════════════════════════════════════

PROGNOZA (następne 3 msc):
  Kwiecień:    +10,000 PLN  →  92,000 PLN
  Maj:         +12,000 PLN  → 104,000 PLN
  Czerwiec:     +8,000 PLN  → 112,000 PLN
```

### Podsumowanie Cash Flow Statement:

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy warto budować Cash Flow Statement? | ✅ **TAK** |
| Czy to blocker dla MVP? | ❌ NIE (Basic wystarczy) |
| Czy zwiększa wartość B2B? | ✅ **TAK, znacząco** |
| Kiedy budować Professional? | 🟢 Faza 2-3 |

**Cash Flow Statement to Twój CORE PRODUCT - zdecydowanie warto. Ale zacznij od Basic, rozbudowuj stopniowo.**

---

## Cash Flow Statement - analiza architektury

### Czy łatwo wyliczyć raport na podstawie obecnej architektury?

**TAK - architektura jest idealnie przygotowana.**

Obecna struktura danych:

```java
// Już istnieje w CashFlowForecastStatement:
CashFlowForecastStatement {
    forecasts: Map<YearMonth, CashFlowMonthlyForecast>
}

CashFlowMonthlyForecast {
    period: YearMonth                           // Miesiąc
    cashFlowStats: CashFlowStats                // start, end, netChange
    categorizedInFlows: List<CashCategory>      // Wpływy per kategoria
    categorizedOutFlows: List<CashCategory>     // Wypływy per kategoria
}

CashCategory {
    categoryName: String
    transactions: List<CashFlowTransaction>
    totalAmount: Money
}
```

### Co już masz (bez żadnych zmian):

| Dane | Masz? | Gdzie |
|------|-------|-------|
| Saldo początkowe miesiąca | ✅ | `cashFlowStats.startBalance` |
| Saldo końcowe miesiąca | ✅ | `cashFlowStats.endBalance` |
| Przepływ netto | ✅ | `cashFlowStats.netChange` |
| Wpływy per kategoria | ✅ | `categorizedInFlows` |
| Wypływy per kategoria | ✅ | `categorizedOutFlows` |
| Historia miesięcy | ✅ | `forecasts` (Map by YearMonth) |

### Generowanie raportu Basic/Standard:

```java
// Pseudokod - to już możesz zrobić dziś!
CashFlowStatement generateBasicReport(CashFlowForecastStatement statement, YearMonth from, YearMonth to) {
    return statement.forecasts.entrySet().stream()
        .filter(e -> e.getKey().compareTo(from) >= 0 && e.getKey().compareTo(to) <= 0)
        .map(e -> new MonthlySection(
            e.getKey(),
            e.getValue().cashFlowStats.startBalance,
            e.getValue().cashFlowStats.endBalance,
            e.getValue().categorizedInFlows,
            e.getValue().categorizedOutFlows
        ))
        .toList();
}
```

**Wniosek: Basic i Standard Cash Flow Statement możesz wygenerować TERAZ bez żadnych zmian w architekturze.**

---

## Professional Cash Flow Statement - wymagania

### Co odróżnia Professional od Standard?

| Aspekt | Standard | Professional |
|--------|----------|--------------|
| Kategorie | Flat list (Przychody, Koszty) | 3 sekcje: Operating/Investing/Financing |
| Format | Prosty raport | Zgodny z MSSF / US GAAP |
| Odbiorcy | Zarząd wewnętrzny | Inwestorzy, banki, audytorzy |
| Automatyzacja | Ręczne przypisanie kategorii | Rule Engine + AI |

### Struktura Professional Cash Flow Statement:

```
═══════════════════════════════════════════════════════
         CASH FLOW STATEMENT (Professional)
         Firma XYZ Sp. z o.o.
         Styczeń - Marzec 2026
═══════════════════════════════════════════════════════

SALDO POCZĄTKOWE:                          50,000 PLN

I. DZIAŁALNOŚĆ OPERACYJNA (Operating)
   Wpływy:
     Przychody ze sprzedaży               +120,000 PLN
     Odsetki otrzymane                      +1,000 PLN
   Wypływy:
     Wynagrodzenia                         -45,000 PLN
     Czynsz i media                        -12,000 PLN
     Materiały i towary                    -15,000 PLN
     Podatki operacyjne                    -10,000 PLN
   ─────────────────────────────────────────────
   PRZEPŁYWY Z DZIAŁALNOŚCI OPERACYJNEJ:   +39,000 PLN

II. DZIAŁALNOŚĆ INWESTYCYJNA (Investing)
   Wpływy:
     Sprzedaż środków trwałych              +5,000 PLN
   Wypływy:
     Zakup sprzętu                         -20,000 PLN
     Zakup licencji                         -3,000 PLN
   ─────────────────────────────────────────────
   PRZEPŁYWY Z DZIAŁALNOŚCI INWESTYCYJNEJ: -18,000 PLN

III. DZIAŁALNOŚĆ FINANSOWA (Financing)
   Wpływy:
     Zaciągnięcie kredytu                  +50,000 PLN
   Wypływy:
     Spłata rat kredytu                    -15,000 PLN
     Odsetki od kredytu                     -2,000 PLN
     Dywidendy wypłacone                   -22,000 PLN
   ─────────────────────────────────────────────
   PRZEPŁYWY Z DZIAŁALNOŚCI FINANSOWEJ:    +11,000 PLN

═══════════════════════════════════════════════════════
PRZEPŁYW NETTO OGÓŁEM:                     +32,000 PLN
SALDO KOŃCOWE:                             82,000 PLN
═══════════════════════════════════════════════════════
```

### Co trzeba dodać do architektury:

**1. Enum CashFlowSection:**

```java
public enum CashFlowSection {
    OPERATING,    // Działalność operacyjna
    INVESTING,    // Działalność inwestycyjna
    FINANCING     // Działalność finansowa
}
```

**2. Section w Category:**

```java
// Rozszerzenie istniejącej Category
public record Category(
    CategoryName name,
    Type type,                    // INFLOW / OUTFLOW
    CashFlowSection section,      // NEW: OPERATING / INVESTING / FINANCING
    boolean isModifiable,
    CategoryOrigin origin,
    // ... reszta pól
) {}
```

**3. Mapping kategorii → sekcji:**

| Kategoria | Type | Section |
|-----------|------|---------|
| Przychody ze sprzedaży | INFLOW | OPERATING |
| Wynagrodzenia | OUTFLOW | OPERATING |
| Czynsz | OUTFLOW | OPERATING |
| Zakup sprzętu | OUTFLOW | INVESTING |
| Sprzedaż środków trwałych | INFLOW | INVESTING |
| Kredyt (zaciągnięcie) | INFLOW | FINANCING |
| Spłata kredytu | OUTFLOW | FINANCING |
| Dywidendy | OUTFLOW | FINANCING |

---

## Rule Engine - automatyczna kategoryzacja i sekcja

### Dlaczego Rule Engine?

| Problem | Rozwiązanie |
|---------|-------------|
| Transakcja z banku: "PRZELEW OD KLIENT ABC" | Rule: zawiera "KLIENT" → Kategoria: Przychody, Sekcja: OPERATING |
| Transakcja: "RATA KREDYTU 12/36 BANK XYZ" | Rule: zawiera "RATA KREDYTU" → Kategoria: Spłata kredytu, Sekcja: FINANCING |
| Transakcja: "ZAKUP LAPTOPY DELL" | Rule: zawiera "ZAKUP" + kwota > 1000 → Kategoria: Sprzęt, Sekcja: INVESTING |

### Architektura Rule Engine:

```java
public class CategorizationRule {
    RuleId id;
    CashFlowId cashFlowId;           // null = global, set = per firma

    // Warunki dopasowania (OR logic między polami, AND wewnątrz):
    String descriptionPattern;        // regex lub contains
    String counterpartyPattern;       // regex lub contains
    BigDecimal amountMin;             // kwota >=
    BigDecimal amountMax;             // kwota <=

    // Wynik dopasowania:
    CategoryName targetCategory;
    CashFlowSection targetSection;    // OPERATING / INVESTING / FINANCING
    RecurringRuleId matchRecurringRule; // opcjonalnie: dopasuj do recurring

    // Metadane:
    int priority;                     // wyższy = ważniejszy
    RuleOrigin origin;                // SYSTEM / USER / AI_SUGGESTED
    boolean enabled;
    ZonedDateTime created;
}
```

### Rodzaje reguł:

| Typ | Opis | Przykład |
|-----|------|----------|
| **SYSTEM** | Predefiniowane, nie do edycji | "PENSJA" → Wynagrodzenia (OPERATING) |
| **USER** | Utworzone przez użytkownika | "KLIENT ABC" → Przychody (OPERATING) |
| **AI_SUGGESTED** | Sugerowane przez AI | "NETFLIX" → Subskrypcje (OPERATING) |

### Przykłady reguł systemowych:

```java
// Reguły SYSTEM (predefiniowane):
rules = [
    // OPERATING - wpływy
    Rule("przychod|sprzedaz|faktura", INFLOW, "Przychody ze sprzedaży", OPERATING),
    Rule("zwrot|refund", INFLOW, "Zwroty", OPERATING),

    // OPERATING - wypływy
    Rule("pensja|wynagrodzenie|salary", OUTFLOW, "Wynagrodzenia", OPERATING),
    Rule("czynsz|najem|rent", OUTFLOW, "Czynsz", OPERATING),
    Rule("prąd|gaz|woda|media", OUTFLOW, "Media", OPERATING),
    Rule("telefon|internet|mobile", OUTFLOW, "Telekomunikacja", OPERATING),

    // INVESTING
    Rule("zakup.*sprzęt|laptop|komputer|maszyna", OUTFLOW, "Zakup sprzętu", INVESTING),
    Rule("sprzedaż.*środk|zbycie", INFLOW, "Sprzedaż środków trwałych", INVESTING),
    Rule("licencja|software|oprogramowanie", OUTFLOW, "Licencje", INVESTING),

    // FINANCING
    Rule("kredyt.*zaciąg|pożyczka.*otrzym", INFLOW, "Zaciągnięcie kredytu", FINANCING),
    Rule("rata.*kredyt|spłata.*kredyt", OUTFLOW, "Spłata kredytu", FINANCING),
    Rule("odsetki.*kredyt|odsetki.*pożyczk", OUTFLOW, "Odsetki", FINANCING),
    Rule("dywidend", OUTFLOW, "Dywidendy", FINANCING),
]
```

### Flow przetwarzania:

```
Bank Transaction (z Open Banking)
        ↓
┌───────────────────────────────────────┐
│           RULE ENGINE                 │
├───────────────────────────────────────┤
│ 1. Szukaj USER rules (priority: high) │
│ 2. Szukaj AI_SUGGESTED (jeśli approved)│
│ 3. Szukaj SYSTEM rules (fallback)     │
│ 4. Brak dopasowania → "Uncategorized" │
└───────────────────────────────────────┘
        ↓
CashChange z:
  - category: "Wynagrodzenia"
  - section: OPERATING
  - matchedRule: rule_id
        ↓
Reconciliation (opcjonalnie):
  - Dopasuj do recurring "Pensja dla Jana"
```

### Integracja Rule Engine z Reconciliation:

```java
// Reconciliation używa Rule Engine do:
// 1. Kategoryzacji nowej transakcji
// 2. Przypisania CashFlowSection
// 3. Dopasowania do recurring transaction

ReconciliationResult reconcile(BankTransaction tx, CashFlow cashFlow) {
    // Krok 1: Znajdź pasującą regułę
    CategorizationRule rule = ruleEngine.findMatchingRule(tx, cashFlow.getId());

    // Krok 2: Jeśli reguła ma recurringRuleId, szukaj recurring
    if (rule.getMatchRecurringRule() != null) {
        RecurringTransaction recurring = findRecurring(rule.getMatchRecurringRule());
        return ReconciliationResult.matched(tx, recurring, rule);
    }

    // Krok 3: Zwróć kategorię i sekcję z reguły
    return ReconciliationResult.categorized(
        tx,
        rule.getTargetCategory(),
        rule.getTargetSection()
    );
}
```

---

## Roadmap: Basic → Standard → Professional

| Faza | Co budować | Czas | Wynik |
|------|------------|------|-------|
| **1. Basic Report** | PDF/Excel export obecnych danych | 2-3 dni | Prosty raport dla B2C |
| **2. Standard Report** | + kategorie, wykresy, porównanie miesięcy | 2-3 dni | Raport dla małych firm |
| **3. CashFlowSection enum** | Dodać pole section do Category | 1 dzień | Przygotowanie do Professional |
| **4. Rule Engine (basic)** | SYSTEM rules + proste dopasowanie | 3-4 dni | Automatyczna kategoryzacja |
| **5. Professional Report** | Operating/Investing/Financing sekcje | 2-3 dni | Raport dla średnich firm |
| **6. Rule Engine (advanced)** | USER rules + UI do zarządzania | 3-4 dni | Personalizacja |
| **7. AI Rules** | Claude/GPT sugestie kategoryzacji | 3-4 dni | Inteligentne dopasowanie |

**Szacowany czas na Professional Cash Flow Statement: ~2-3 tygodnie**

### Zależności:

```
Basic Report ─────────────────────────────┐
                                          │
Standard Report ──────────────────────────┤
                                          ↓
CashFlowSection enum ──────→ Professional Report
       ↓                           ↑
Rule Engine (basic) ───────────────┘
       ↓
Rule Engine (advanced) + AI Rules
```

---

## Podsumowanie - Cash Flow Statement

| Aspekt | Status |
|--------|--------|
| Architektura gotowa? | ✅ TAK (Basic/Standard) |
| Co trzeba dodać? | CashFlowSection enum + Rule Engine |
| Czy warto? | ✅ **TAK - to core product** |
| Kiedy Professional? | Po Rule Engine (~2-3 tyg) |
| ROI dla B2B | **WYSOKI** - differentiator |

**Cash Flow Statement (Professional) z podziałem na Operating/Investing/Financing to funkcja PREMIUM - uzasadnia wyższą cenę tier Business/Enterprise.**

---

## Wartość funkcji dla B2C vs B2B

### Porównanie wartości per funkcja:

| Funkcja | B2C (osoba) | B2B (firma) | Różnica |
|---------|-------------|-------------|---------|
| **Bank API (Open Banking)** | 🔶 Średnia | ✅ **Wysoka** | Firma ma 5+ kont, osoba 1-2 |
| **Reconciliation** | ❌ Niska | ✅ **Bardzo wysoka** | Osoba nie ma faktur do dopasowania |
| **Rule Engine** | 🔶 Średnia | ✅ **Wysoka** | Firma ma 500+ transakcji/msc |
| **Cash Flow Statement (Basic)** | 🔶 Średnia | ✅ Wysoka | "Ile wydaję?" vs "Raport dla banku" |
| **Cash Flow Statement (Professional)** | ❌ Niska | ✅ **Bardzo wysoka** | Osoba nie zna Operating/Investing |
| **Recurring transactions** | ✅ Wysoka | ✅ **Bardzo wysoka** | Oba segmenty potrzebują |
| **Forecasting** | ✅ Wysoka | ✅ **Bardzo wysoka** | "Czy wystarczy mi?" vs "Runway 12 msc" |

---

### 1. Bank API (Open Banking) - szczegóły

| Segment | Wartość | Dlaczego |
|---------|---------|----------|
| **B2C** | 🔶 **Średnia** | Ma 1-2 konta, może wpisać ręcznie |
| **B2B** | ✅ **Wysoka** | 5+ kont w różnych bankach, ręczne = 2h/tydzień |

**Wniosek:** B2B zapłaci 5-10x więcej za tę samą funkcję.

---

### 2. Reconciliation - szczegóły

| Segment | Wartość | Dlaczego |
|---------|---------|----------|
| **B2C** | ❌ **Niska** | Osoba nie wystawia faktur, nie potrzebuje dopasowania |
| **B2B** | ✅ **Bardzo wysoka** | 100+ faktur/msc, ręczne dopasowanie = 10h/msc pracy księgowej |

**Wniosek:** To jest **czysto B2B feature**. B2C tego nie rozumie i nie potrzebuje.

---

### 3. Rule Engine (auto-kategoryzacja) - szczegóły

| Segment | Wartość | Dlaczego |
|---------|---------|----------|
| **B2C** | 🔶 **Średnia** | 50-100 transakcji/msc, może ręcznie |
| **B2B** | ✅ **Wysoka** | 500-5000 transakcji/msc, ręczne = niemożliwe |

**Wniosek:** B2C doceni jako "nice-to-have", B2B to **must-have**.

---

### 4. Cash Flow Statement - szczegóły

| Raport | B2C | B2B |
|--------|-----|-----|
| **Basic** (przychody vs wydatki) | ✅ Chce | ✅ Chce |
| **Standard** (+ kategorie, trend) | 🔶 Może | ✅ Potrzebuje |
| **Professional** (Operating/Investing/Financing) | ❌ Nie rozumie | ✅ **Musi mieć** (bank/inwestor wymaga) |

**Wniosek:** Professional to **premium B2B feature**.

---

### 5. Recurring transactions - szczegóły

| Segment | Wartość | Dlaczego |
|---------|---------|----------|
| **B2C** | ✅ **Wysoka** | Netflix, Spotify, czynsz - chce widzieć |
| **B2B** | ✅ **Bardzo wysoka** | Pensje, faktury cykliczne, leasingi |

**Wniosek:** Oba segmenty potrzebują, ale B2B ma więcej i bardziej złożone.

---

## Co budować dla kogo

### Dla B2C (osoba prywatna):

| Funkcja | Priorytet | Wartość |
|---------|-----------|---------|
| Recurring transactions | 🔴 Must | "Ile co miesiąc tracę na subskrypcje?" |
| Forecasting | 🔴 Must | "Czy wystarczy mi do końca miesiąca?" |
| Basic Cash Flow Statement | 🟡 Should | "Ile zarobiłem vs wydałem?" |
| Bank API | 🟢 Nice | Wygoda, ale może wpisać ręcznie |
| Rule Engine | 🟢 Nice | Pomocne, ale nie krytyczne |
| Reconciliation | ❌ Skip | Nie potrzebuje |

### Dla B2B (firma):

| Funkcja | Priorytet | Wartość |
|---------|-----------|---------|
| Bank API | 🔴 **Must** | Oszczędza 10+ h/msc |
| Recurring transactions | 🔴 **Must** | Prognoza cash flow |
| Reconciliation | 🔴 **Must** | Dopasowanie faktur |
| Rule Engine | 🔴 **Must** | 500+ transakcji/msc |
| Professional Cash Flow Statement | 🟡 Should | Raport dla banku/inwestora |
| Forecasting | 🔴 **Must** | "Kiedy zabraknie gotówki?" |

---

## Strategia cenowa - monetyzacja per segment

| Funkcja | B2C tier | B2B tier |
|---------|----------|----------|
| Recurring + Forecasting | **Free/Pro** (29 zł) | Zawarte w każdym |
| Bank API (2 konta) | **Pro** (29 zł) | - |
| Bank API (unlimited) | - | **Business** (299 zł) |
| Rule Engine (basic) | **Pro** (29 zł) | Zawarte |
| Rule Engine (custom rules) | - | **Business** (299 zł) |
| Reconciliation | - | **Business** (299 zł) |
| Professional CFS | - | **Enterprise** (999 zł) |

---

## Konkluzja - co sprzedaje w każdym segmencie

```
B2C kupuje za:
├── Recurring transactions ("ile tracę na subskrypcje?")
├── Forecasting ("czy wystarczy mi do końca miesiąca?")
└── Basic Report ("ile zarobiłem vs wydałem?")

B2B kupuje za:
├── Bank API (oszczędność 10+ h/msc)
├── Reconciliation (dopasowanie faktur do płatności)
├── Rule Engine (automatyczna kategoryzacja 500+ transakcji)
└── Professional Report (raport dla banku/inwestora)

Overlap (buduj raz, sprzedawaj obu):
├── Recurring transactions
└── Forecasting

B2B-only (premium pricing):
├── Reconciliation
├── Professional Cash Flow Statement
└── Custom Rule Engine
```

**Kluczowy wniosek:** Funkcje "overlap" (Recurring + Forecasting) to fundament produktu. Funkcje B2B-only (Reconciliation, Professional CFS) uzasadniają 10x wyższą cenę.

---

## Multi-user access - współdzielenie CashFlow

### Scenariusze współdzielenia danych

#### B2C (osoby prywatne):

| Scenariusz | Przykład | Potrzeba |
|------------|----------|----------|
| **Para/Małżeństwo** | Wspólny budżet domowy | ✅ Wysoka |
| **Rodzina** | Rodzice + dorosłe dzieci | 🔶 Średnia |
| **Współlokatorzy** | Wspólne wydatki na mieszkanie | 🔶 Średnia |
| **Freelancer + księgowa** | Księgowa widzi moje finanse | ✅ Wysoka |

#### B2B (firmy):

| Scenariusz | Przykład | Potrzeba |
|------------|----------|----------|
| **Właściciel + Księgowa** | Księgowa zarządza, właściciel widzi | ✅ **Krytyczna** |
| **CFO + Zarząd** | CFO edytuje, zarząd tylko widzi raporty | ✅ **Krytyczna** |
| **Dział finansowy** | 3-5 osób pracuje na tych samych danych | ✅ **Krytyczna** |
| **Biuro rachunkowe** | 1 księgowa obsługuje 20 firm | ✅ **Krytyczna** |
| **Inwestor** | Read-only dostęp do cash flow startupu | ✅ Wysoka |

---

### Model uprawnień

#### Poziomy dostępu:

| Rola | Uprawnienia | Dla kogo |
|------|-------------|----------|
| **Owner** | Wszystko + billing + usuwanie + invite users | Właściciel konta |
| **Admin** | Wszystko oprócz billing i usuwania CashFlow | CFO, główna księgowa |
| **Editor** | CRUD transakcji, kategorii, recurring | Księgowa, pracownik finansowy |
| **Viewer** | Tylko odczyt + eksport raportów | Zarząd, inwestor, manager |
| **Auditor** | Read-only + pełny audit trail | Audytor zewnętrzny |

#### Struktura danych:

```java
// CashFlow ma listę userów z rolami
CashFlow {
    id: CashFlowId
    ownerId: UserId                    // Właściciel (zawsze 1)

    members: List<CashFlowMember> [    // Współdzielenie
        { userId: "user1", role: ADMIN },
        { userId: "user2", role: EDITOR },
        { userId: "user3", role: VIEWER }
    ]

    // ... reszta pól
}

// Invite system
CashFlowInvite {
    id: InviteId
    cashFlowId: CashFlowId
    email: String                      // Email zapraszanego
    role: Role                         // Jaka rola po akceptacji
    invitedBy: UserId
    status: PENDING | ACCEPTED | EXPIRED
    expiresAt: ZonedDateTime           // 7 dni ważności
}
```

---

### Scenariusze użycia

#### B2C: Para ze wspólnym budżetem

```
Anna (Owner) ─────────────────────────────────────
    │
    ├── CashFlow: "Budżet Domowy"
    │       │
    │       ├── Anna: OWNER (pełna kontrola)
    │       └── Marek: EDITOR (może dodawać transakcje)
    │
    └── CashFlow: "Moje prywatne" (tylko Anna)
```

#### B2B: Mała firma

```
Jan Kowalski (Owner) ─────────────────────────────
    │
    ├── CashFlow: "Firma XYZ Sp. z o.o."
    │       │
    │       ├── Jan: OWNER (właściciel)
    │       ├── Maria (księgowa): ADMIN (zarządza wszystkim)
    │       ├── Tomek (asystent): EDITOR (wprowadza dane)
    │       └── Piotr (wspólnik): VIEWER (tylko podgląd)
    │
    └── CashFlow: "Projekt Alpha" (osobny budżet)
            │
            ├── Jan: OWNER
            └── Anna (PM): EDITOR
```

#### B2B: Biuro rachunkowe

```
Biuro Rachunkowe "Księgowi24" ────────────────────
    │
    ├── Maria (właściciel biura): OWNER wszystkich
    │
    ├── Klient A: "Firma ABC"
    │       ├── Maria: ADMIN
    │       ├── Ewa (pracownik biura): EDITOR
    │       └── Pan Nowak (właściciel ABC): VIEWER
    │
    ├── Klient B: "Firma XYZ"
    │       ├── Maria: ADMIN
    │       ├── Ewa: EDITOR
    │       └── Pani Kowalska (właściciel XYZ): VIEWER
    │
    └── ... 20+ klientów
```

---

### Co trzeba zbudować

#### Minimum (MVP):

| Funkcja | Opis | Priorytet |
|---------|------|-----------|
| **Invite by email** | Owner wysyła zaproszenie | 🔴 Must |
| **Role assignment** | Przypisanie roli przy invite | 🔴 Must |
| **Accept/Decline** | User akceptuje zaproszenie | 🔴 Must |
| **Remove member** | Owner może usunąć usera | 🔴 Must |
| **My CashFlows** | Lista moich + współdzielonych | 🔴 Must |

#### Standard:

| Funkcja | Opis | Priorytet |
|---------|------|-----------|
| **Change role** | Owner zmienia rolę członka | 🟡 Should |
| **Transfer ownership** | Przekazanie własności | 🟡 Should |
| **Audit: kto co zmienił** | Historia zmian per user | 🟡 Should |
| **Notifications** | "Maria dodała transakcję" | 🟢 Nice |

#### Enterprise:

| Funkcja | Opis | Priorytet |
|---------|------|-----------|
| **Teams/Groups** | Grupa userów z jedną rolą | 🟢 Nice |
| **SSO/SAML** | Login przez firmowe AD | 🟢 Nice |
| **IP whitelist** | Dostęp tylko z biura | 🟢 Nice |
| **Temporary access** | "Dostęp na 30 dni dla audytora" | 🟢 Nice |

---

### API endpoints (propozycja)

```
# Zarządzanie członkami
POST   /cash-flow/{id}/members/invite     # Wyślij zaproszenie
GET    /cash-flow/{id}/members            # Lista członków
PUT    /cash-flow/{id}/members/{userId}   # Zmień rolę
DELETE /cash-flow/{id}/members/{userId}   # Usuń członka

# Zaproszenia
GET    /invites                           # Moje oczekujące zaproszenia
POST   /invites/{id}/accept               # Akceptuj
POST   /invites/{id}/decline              # Odrzuć

# Moje CashFlows
GET    /cash-flow/my                      # Własne + współdzielone
```

---

### Różnice B2C vs B2B w multi-user

| Aspekt | B2C | B2B |
|--------|-----|-----|
| Typowa liczba userów | 1-2 | 3-10 |
| Role | Owner + 1 Editor | Owner + Admin + Editors + Viewers |
| Audit trail | Nice-to-have | **Must-have** |
| Permissions granularity | Prosta | Szczegółowa |

---

### Podsumowanie multi-user

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy multi-user potrzebny? | ✅ **TAK** (B2B: krytyczne, B2C: ważne) |
| Jak modelować? | 1 CashFlow → wielu Members z rolami |
| Kiedy budować? | 🔴 **Przed B2B launch** |
| Trudność | 🔶 Średnia (~2 tygodnie) |

**Rekomendacja:** Jeden CashFlow współdzielony przez wielu userów z różnymi rolami. NIE łączyć danych z różnych CashFlows - zbyt skomplikowane.

---

## Multi-CashFlow i Consolidated View

### Przypadki użycia - ile CashFlows potrzebuje user?

#### B2C (osoba prywatna):

| Scenariusz | Ile CashFlows | Ile kont bankowych | Przykład |
|------------|---------------|-------------------|----------|
| **Typowa osoba** | 1 | 1-2 | Konto osobiste + oszczędnościowe |
| **Para** | 1-2 | 2-4 | Wspólny budżet + osobne konta |
| **Freelancer** | 2 | 2-3 | Prywatne + firmowe (JDG) |
| **Inwestor** | 2-3 | 2-4 | Budżet + inwestycje + oszczędności |

#### B2B (firma):

| Scenariusz | Ile CashFlows | Ile kont bankowych | Przykład |
|------------|---------------|-------------------|----------|
| **Mikro firma (JDG)** | 1 | 1-2 | Jedno konto firmowe |
| **Mała firma** | 1-2 | 2-5 | Konto główne + walutowe + VAT |
| **Średnia firma** | 3-5 | 5-15 | Per projekt/dział/spółka |
| **Holding/Grupa** | 5-20 | 20-50 | Każda spółka osobno + consolidated |
| **Biuro rachunkowe** | 20-100 | 50-200 | Każdy klient = osobny CashFlow |

---

### Dwa modele architektury

#### Model A: Wiele kont → 1 CashFlow

```
CashFlow "Firma ABC"
    │
    ├── Bank Account: PKO BP (PLN) ──────┐
    ├── Bank Account: mBank (PLN) ───────┼──→ Wszystkie transakcje
    ├── Bank Account: Revolut (EUR) ─────┤    w jednym CashFlow
    └── Bank Account: Wise (USD) ────────┘

    Raporty: Jeden skonsolidowany widok
```

**Zalety:**
- Prosty widok "wszystkie pieniądze firmy"
- Jeden raport Cash Flow Statement
- Łatwiejsze forecasting

**Wady:**
- Trudno rozdzielić np. projekt A vs projekt B
- Brak granularności per dział/spółka

#### Model B: 1 konto → 1 CashFlow + Consolidated View

```
CashFlow "Projekt Alpha" ←── PKO BP (PLN)
CashFlow "Projekt Beta"  ←── mBank (PLN)
CashFlow "Operacje EU"   ←── Revolut (EUR)
        │
        ↓
┌─────────────────────────────────────┐
│     CONSOLIDATED VIEW               │
│     "Firma ABC - wszystko"          │
│                                     │
│  Total: 500,000 PLN (po konwersji)  │
│  - Projekt Alpha: 200,000 PLN       │
│  - Projekt Beta: 150,000 PLN        │
│  - Operacje EU: 150,000 PLN (eq)    │
└─────────────────────────────────────┘
```

**Zalety:**
- Granularność per projekt/dział
- Możliwość różnych uprawnień per CashFlow
- Consolidated view dla zarządu

**Wady:**
- Bardziej złożone
- Wymaga konwersji walut

---

### Rekomendacja: Hybrid (oba modele)

| Funkcja | Opis |
|---------|------|
| **1 CashFlow = wiele kont bankowych** | ✅ TAK - domyślny model |
| **Wiele CashFlows per user** | ✅ TAK - dla firm z projektami |
| **Consolidated View** | ✅ TAK - agregacja wielu CashFlows |

#### Kiedy który model?

| Klient | Model | Dlaczego |
|--------|-------|----------|
| **B2C osoba** | 1 CashFlow + wiele kont | Nie potrzebuje więcej |
| **Freelancer** | 2 CashFlows (prywatne + firma) | Separacja |
| **Mała firma** | 1 CashFlow + wiele kont | Prostota |
| **Średnia firma** | Wiele CashFlows + Consolidated | Per projekt/dział |
| **Holding** | Wiele CashFlows + Consolidated | Per spółka |
| **Biuro rachunkowe** | Wiele CashFlows + Consolidated per klient | Każdy klient osobno |

---

### Consolidated View - szczegóły techniczne

#### Struktura danych:

```java
// Grupa CashFlows do konsolidacji
ConsolidatedView {
    id: ConsolidatedViewId
    name: String                       // "Holding ABC - wszystkie spółki"
    ownerId: UserId

    cashFlows: List<CashFlowReference> [
        { cashFlowId: "cf1", weight: 1.0 },   // 100% udziału
        { cashFlowId: "cf2", weight: 0.5 },   // 50% (joint venture)
        { cashFlowId: "cf3", weight: 1.0 }
    ]

    baseCurrency: Currency             // PLN - waluta raportowania

    settings: {
        includeForecasts: boolean      // Czy pokazywać prognozy
        includeIntercompany: boolean   // Czy pokazywać transakcje między spółkami
    }
}
```

#### Agregacja danych:

```java
ConsolidatedStatement generateConsolidated(ConsolidatedView view, YearMonth period) {
    // 1. Pobierz dane z każdego CashFlow
    List<CashFlowForecastStatement> statements = view.cashFlows.stream()
        .map(ref -> getStatement(ref.cashFlowId, period))
        .toList();

    // 2. Konwertuj do waluty bazowej
    statements = statements.stream()
        .map(s -> convertToCurrency(s, view.baseCurrency))
        .toList();

    // 3. Zastosuj wagi (dla joint ventures)
    statements = applyWeights(statements, view.cashFlows);

    // 4. Agreguj
    return ConsolidatedStatement.builder()
        .totalInflows(sumInflows(statements))
        .totalOutflows(sumOutflows(statements))
        .netCashFlow(calculateNet(statements))
        .byEntity(groupByEntity(statements))        // Breakdown per spółka
        .byCategory(groupByCategory(statements))    // Breakdown per kategoria
        .build();
}
```

#### Raport Consolidated:

```
═══════════════════════════════════════════════════════════════
         CONSOLIDATED CASH FLOW STATEMENT
         Holding ABC (3 spółki)
         Luty 2026
═══════════════════════════════════════════════════════════════

SALDO POCZĄTKOWE (łącznie):              1,500,000 PLN

PER SPÓŁKA:
┌────────────────────┬──────────────┬──────────────┬──────────────┐
│ Spółka             │ Wpływy       │ Wypływy      │ Netto        │
├────────────────────┼──────────────┼──────────────┼──────────────┤
│ ABC Sp. z o.o.     │ +500,000 PLN │ -350,000 PLN │ +150,000 PLN │
│ ABC Tech Sp. z o.o.│ +200,000 PLN │ -180,000 PLN │  +20,000 PLN │
│ ABC EU GmbH (50%)  │ +150,000 PLN │ -100,000 PLN │  +50,000 PLN │
├────────────────────┼──────────────┼──────────────┼──────────────┤
│ RAZEM              │ +850,000 PLN │ -630,000 PLN │ +220,000 PLN │
└────────────────────┴──────────────┴──────────────┴──────────────┘

PER KATEGORIA (skonsolidowane):
  Przychody ze sprzedaży:     +750,000 PLN
  Inne przychody:             +100,000 PLN
  Wynagrodzenia:              -300,000 PLN
  Koszty operacyjne:          -200,000 PLN
  Inwestycje:                 -130,000 PLN

SALDO KOŃCOWE (łącznie):              1,720,000 PLN

═══════════════════════════════════════════════════════════════
```

---

### Co ma konkurencja?

#### Agicap (lider B2B cash flow):

| Funkcja | Ma? | Szczegóły |
|---------|-----|-----------|
| Wiele kont → 1 CashFlow | ✅ TAK | Podstawowa funkcja |
| Wiele CashFlows (entities) | ✅ TAK | Per spółka/projekt |
| Consolidated View | ✅ TAK | "Group consolidation" |
| Multi-currency | ✅ TAK | Auto konwersja |
| Intercompany elimination | ✅ TAK | Usuwanie transakcji między spółkami |

**Cena:** €200-2000/msc (Enterprise feature)

#### Float (SMB cash flow):

| Funkcja | Ma? | Szczegóły |
|---------|-----|-----------|
| Wiele kont → 1 CashFlow | ✅ TAK | Integracja z bankami |
| Wiele CashFlows | ❌ NIE | Jeden widok per firma |
| Consolidated View | ❌ NIE | Brak |

#### Mint/YNAB (B2C):

| Funkcja | Ma? | Szczegóły |
|---------|-----|-----------|
| Wiele kont → 1 widok | ✅ TAK | Agregacja wszystkich kont |
| Osobne budżety | 🔶 YNAB | Kategorie, nie osobne CashFlows |
| Consolidated | ❌ N/A | Nie dotyczy B2C |

#### Podsumowanie konkurencji:

| Segment | Wiele kont/CashFlow | Consolidated View |
|---------|---------------------|-------------------|
| **B2C** | ✅ Standard | ❌ Nie potrzeba |
| **SMB** | ✅ Standard | 🔶 Rzadko |
| **Enterprise** | ✅ Standard | ✅ **Must-have** |

---

### Wartość biznesowa multi-CashFlow

#### Wiele kont → 1 CashFlow:

| Segment | Wartość | Willingness to pay |
|---------|---------|-------------------|
| **B2C** | ✅ Wysoka | Free/Pro (29 zł) |
| **B2B SMB** | ✅ Bardzo wysoka | Business (299 zł) |
| **B2B Enterprise** | ✅ Krytyczna | Enterprise (999+ zł) |

**Wniosek:** Must-have dla wszystkich.

#### Wiele CashFlows per user:

| Segment | Wartość | Willingness to pay |
|---------|---------|-------------------|
| **B2C** | 🔶 Średnia | Pro (29 zł) - max 3 CashFlows |
| **B2B SMB** | ✅ Wysoka | Business (299 zł) - unlimited |
| **B2B Enterprise** | ✅ Krytyczna | Enterprise (999+ zł) |

**Wniosek:** Ważne dla B2B, nice-to-have dla B2C.

#### Consolidated View:

| Segment | Wartość | Willingness to pay |
|---------|---------|-------------------|
| **B2C** | ❌ Niska | Nie potrzebują |
| **B2B SMB** | 🔶 Średnia | Business (299 zł) - prosty |
| **B2B Enterprise** | ✅ **Bardzo wysoka** | Enterprise (999+ zł) |
| **Biuro rachunkowe** | ✅ **Krytyczna** | Enterprise (999+ zł) |

**Wniosek:** Premium Enterprise feature.

---

### Roadmap multi-CashFlow

| Faza | Funkcja | Priorytet | Czas |
|------|---------|-----------|------|
| **1** | Wiele kont bankowych → 1 CashFlow | 🔴 Must (MVP) | 1-2 tyg |
| **2** | Wiele CashFlows per user | 🔴 Must (B2B) | Już masz? |
| **3** | UI: przełączanie między CashFlows | 🔴 Must | 1 tyg |
| **4** | Consolidated View (basic) | 🟡 Should | 2-3 tyg |
| **5** | Multi-currency consolidation | 🟢 Nice | 1-2 tyg |
| **6** | Intercompany elimination | 🟢 Nice (Enterprise) | 2 tyg |

---

### Podsumowanie multi-CashFlow

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy wiele kont → 1 CashFlow? | ✅ **TAK - must-have** |
| Czy wiele CashFlows per user? | ✅ **TAK - ważne dla B2B** |
| Czy Consolidated View? | ✅ **TAK - Enterprise feature** |
| Kiedy budować? | Faza 1-2 teraz, Consolidated później |
| Czy konkurencja ma? | ✅ TAK (Agicap ma wszystko) |

**Rekomendacja architektury:**

```
User
 │
 ├── CashFlow A ←── [Bank 1, Bank 2, Bank 3]  (wiele kont)
 ├── CashFlow B ←── [Bank 4]
 ├── CashFlow C ←── [Bank 5, Bank 6]
 │
 └── Consolidated View "Wszystko" ←── [A, B, C]  (agregacja)
```

---

## Wielowalutowość - koncepcja i architektura

### Co to jest CashFlow koncepcyjnie?

**CashFlow = przepływy pieniężne JEDNEGO podmiotu w JEDNEJ walucie operacyjnej**

To jak **konto bankowe** - ma jedną walutę. Nawet jeśli wpływa przelew w EUR na konto PLN, bank konwertuje i zapisuje w PLN.

---

### Dwa podejścia koncepcyjne

#### Podejście A: CashFlow = jedna waluta (✅ REKOMENDOWANE)

```
User "Firma ABC"
│
├── CashFlow "Operacje PLN" (waluta: PLN)
│       └── Konta: PKO BP, mBank (oba PLN)
│
├── CashFlow "Operacje EUR" (waluta: EUR)
│       └── Konta: Revolut EUR
│
├── CashFlow "Operacje USD" (waluta: USD)
│       └── Konta: Wise USD
│
└── Consolidated View "Wszystko" (waluta raportowania: PLN)
        └── Agreguje: PLN + EUR + USD → pokazuje w PLN
```

**Analogia:** Jak w księgowości - każde konto księgowe ma jedną walutę, a bilans jest w walucie sprawozdawczej.

#### Podejście B: CashFlow = wiele walut (❌ NIE REKOMENDOWANE)

```
CashFlow "Firma ABC" {
    transakcje: [
        { 1000 PLN },
        { 500 EUR },
        { 200 USD }
    ]

    // Problem: jaki jest "balance"?
    balance: ???
}
```

**Problem:** Co to znaczy "saldo" gdy masz 1000 PLN + 500 EUR + 200 USD? Musisz i tak konwertować.

---

### Dlaczego 1 CashFlow = 1 waluta jest lepsze

| Aspekt | 1 waluta | Wiele walut |
|--------|----------|-------------|
| **Prostota** | ✅ Proste | ❌ Skomplikowane |
| **Balance** | ✅ Jasny (np. 10,000 PLN) | ❌ Niejasny (trzeba konwertować) |
| **Forecasting** | ✅ Prosty | ❌ Wymaga prognoz kursów! |
| **Reconciliation** | ✅ 1:1 matching | ❌ Skomplikowany |
| **Raporty** | ✅ Proste | ❌ Wymaga konwersji |
| **Błędy użytkownika** | ✅ Mniej | ❌ Więcej (pomylone waluty) |

---

### Consolidated View - tam jest wielowalutowość

```
┌─────────────────────────────────────────────────────────────┐
│              CONSOLIDATED VIEW                              │
│              Waluta raportowania: PLN                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  CashFlow PLN:     50,000 PLN   ──────→   50,000 PLN       │
│  CashFlow EUR:      5,000 EUR   ×4.30→   21,500 PLN       │
│  CashFlow USD:      2,000 USD   ×4.00→    8,000 PLN       │
│                                         ─────────────       │
│  RAZEM:                                  79,500 PLN        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Consolidated View:**
- Agreguje wiele CashFlows
- Konwertuje do jednej waluty raportowania
- Pokazuje breakdown per waluta/CashFlow

---

### Use Cases - wielowalutowość w praktyce

#### Use Case 1: Freelancer z klientami PL i DE

```
Sytuacja:
- Klient Polski płaci w PLN na konto PKO
- Klient Niemiecki płaci w EUR na Revolut

Rozwiązanie:
├── CashFlow "Klienci PL" (PLN)
│       ├── Recurring: Klient A → 5000 PLN/msc
│       └── Recurring: Klient B → 3000 PLN/msc
│
├── CashFlow "Klienci DE" (EUR)
│       └── Recurring: Klient C → 2000 EUR/msc
│
└── Consolidated "Wszystkie przychody"
        └── Pokazuje: 8000 PLN + 2000 EUR = ~16,600 PLN
```

#### Use Case 2: Firma eksportowa

```
Sytuacja:
- Przychody w PLN (rynek krajowy) i EUR (eksport)
- Koszty głównie w PLN
- Kredyt w EUR

Rozwiązanie:
├── CashFlow "Operacje PLN"
│       ├── Inflow: Sprzedaż krajowa
│       ├── Outflow: Pensje, czynsz, materiały
│       └── Forecast: Cashflow operacyjny PLN
│
├── CashFlow "Operacje EUR"
│       ├── Inflow: Eksport
│       ├── Outflow: Rata kredytu EUR
│       └── Forecast: Cashflow EUR
│
└── Consolidated "Firma - pełny obraz"
        ├── Czy mamy płynność? (w PLN)
        └── Breakdown per waluta
```

#### Use Case 3: Co gdy klient EUR płaci na konto PLN?

```
Scenariusz:
Klient DE płaci 1000 EUR, ale bank konwertuje na PLN i wpływa 4300 PLN.

Rozwiązanie:
CashFlow PLN:
    Transaction: +4300 PLN (już po konwersji przez bank)
    Metadata: originalAmount=1000 EUR, rate=4.30

LUB (jeśli user chce śledzić w EUR):
CashFlow EUR:
    Transaction: +1000 EUR

// Bank fizycznie ma PLN, ale user śledzi w EUR
// Bo to jest "klient eurowy"
```

---

### Gdzie jest konwersja walut?

| Miejsce | Konwersja? | Dlaczego |
|---------|------------|----------|
| **CashFlow** | ❌ NIE | Jedna waluta, brak konwersji |
| **Consolidated View** | ✅ TAK | Agregacja wymaga wspólnej waluty |
| **Import z banku** | 🔶 Opcjonalnie | Jeśli bank już skonwertował |
| **Raporty** | ✅ TAK | Raport w walucie sprawozdawczej |

---

### Zasady wielowalutowości

```
1. CashFlow = jedna waluta (np. PLN)
2. Wszystkie konta w CashFlow = ta sama waluta
3. Wszystkie transakcje w CashFlow = ta sama waluta
4. Chcesz drugą walutę? → Drugi CashFlow
5. Chcesz pełny obraz? → Consolidated View
```

---

### Obecna architektura - problemy do naprawy

#### Problem 1: Brak walidacji waluty w Money

```java
// Money.java - OBECNY KOD (BUG!)
public Money plus(Money other) {
    return new Money(amount.add(other.amount), currency);
    // ❌ NIE SPRAWDZA czy other.currency == this.currency!
}
```

**Konsekwencja:** Jeśli dodasz transakcję w EUR do CashFlow w PLN:
```java
Money balance = Money.of(1000, "PLN");
Money eurTransaction = Money.of(100, "EUR");
Money result = balance.plus(eurTransaction);
// result = Money(1100, "PLN") ← BŁĄD! 100 EUR != 100 PLN
```

#### Problem 2: Jeden BankAccount zamiast listy

```java
// Obecna struktura - JEDEN bank account
CashFlow {
    BankAccount bankAccount;  // ← tylko jeden!
}

// Potrzebna struktura - WIELE bank accounts
CashFlow {
    List<BankAccount> bankAccounts;  // ← wiele kont
    Currency baseCurrency;            // ← waluta CashFlow
}
```

---

### Co TRZEBA zmienić

| Zmiana | Priorytet | Opis |
|--------|-----------|------|
| Walidacja waluty w `Money.plus/minus` | 🔴 Must | Rzucaj exception przy mismatch |
| `List<BankAccount>` w CashFlow | 🔴 Must | Ale wszystkie w tej samej walucie |
| Walidacja waluty BankAccount | 🔴 Must | Konto musi mieć tę samą walutę co CashFlow |
| `Currency baseCurrency` w CashFlow | 🟡 Should | Explicit waluta CashFlow |

### Co NIE TRZEBA robić

| Zmiana | Priorytet | Dlaczego |
|--------|-----------|----------|
| Multi-currency w CashFlow | ❌ Skip | Zbyt skomplikowane, niepotrzebne |
| ExchangeRateService w CashFlow | ❌ Skip | Nie ma konwersji |
| Historyczne kursy w CashFlow | ❌ Skip | Tylko dla Consolidated View |

---

### Podsumowanie wielowalutowości

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy CashFlow powinien być wielowalutowy? | ❌ **NIE** |
| Czy Consolidated View powinien być wielowalutowy? | ✅ **TAK** |
| Dlaczego? | CashFlow = operacyjny (prosta waluta), Consolidated = raportowy (agregacja) |
| Co zmienić w architekturze? | Walidacja waluty, `baseCurrency` explicit, `List<BankAccount>` |

**Konkluzja:**

```
CashFlow = "konto operacyjne" → jedna waluta
Consolidated = "raport zarządczy" → wielowalutowy z konwersją
```

---

## Support B2C vs B2B

### Oczekiwania klientów

| Aspekt | B2C (osoba) | B2B (firma) |
|--------|-------------|-------------|
| **Czas odpowiedzi** | 24-48h OK | 4-8h oczekiwane |
| **Kanał** | Email, FAQ, chatbot | Email, telefon, dedykowany opiekun |
| **Godziny** | Brak oczekiwań 24/7 | Godziny biznesowe minimum |
| **Język** | Lokalny (PL) | PL + EN (dla międzynarodowych) |
| **Złożoność problemów** | Proste ("jak dodać transakcję?") | Złożone ("reconciliation nie działa dla 500 faktur") |
| **SLA** | Brak | Wymagane (99.9% uptime, response time) |
| **Onboarding** | Self-service | Dedykowany + szkolenie |
| **Eskalacja** | Brak | Do managera/CTO |

---

### Model supportu per tier

#### Free / Basic (B2C)

| Element | Opis | Koszt dla Ciebie |
|---------|------|------------------|
| **Kanał** | Email + FAQ + Community | Niski |
| **Czas odpowiedzi** | 48-72h | - |
| **Godziny** | Brak gwarancji | - |
| **Onboarding** | Self-service (docs, videos) | Jednorazowy (tworzenie materiałów) |
| **Priorytet** | Niski | - |

**Narzędzia:** Zendesk Free / Freshdesk Free / Help Scout

#### Pro (B2C premium + mały B2B)

| Element | Opis | Koszt dla Ciebie |
|---------|------|------------------|
| **Kanał** | Email + Chat (godziny biznesowe) | Średni |
| **Czas odpowiedzi** | 24h | - |
| **Godziny** | Pn-Pt 9:00-17:00 | - |
| **Onboarding** | Self-service + opcjonalny call | ~30 min/klient |
| **Priorytet** | Średni | - |

**Narzędzia:** Intercom / Crisp / Zendesk

#### Business (B2B SMB)

| Element | Opis | Koszt dla Ciebie |
|---------|------|------------------|
| **Kanał** | Email + Chat + Telefon | Wysoki |
| **Czas odpowiedzi** | 8h (critical: 2h) | - |
| **Godziny** | Pn-Pt 8:00-18:00 | - |
| **Onboarding** | Dedykowany call (1-2h) | 1-2h/klient |
| **Priorytet** | Wysoki | - |
| **SLA** | 99.5% uptime | - |

**Narzędzia:** Zendesk Pro / Intercom / HubSpot

#### Enterprise (B2B duży)

| Element | Opis | Koszt dla Ciebie |
|---------|------|------------------|
| **Kanał** | Dedykowany Account Manager | Bardzo wysoki |
| **Czas odpowiedzi** | 4h (critical: 1h) | - |
| **Godziny** | Pn-Pt 8:00-20:00 + on-call | - |
| **Onboarding** | Pełne wdrożenie (kilka dni) | 8-40h/klient |
| **Priorytet** | Najwyższy | - |
| **SLA** | 99.9% uptime + penalties | - |
| **Dedykowany opiekun** | TAK | ~10-20 klientów/osoba |
| **Quarterly Business Review** | TAK | 2h/kwartał/klient |

**Narzędzia:** Salesforce Service Cloud / Zendesk Enterprise

---

### Koszty supportu

#### Struktura kosztów per tier:

| Tier | Cena/msc | Koszt supportu | % przychodu |
|------|----------|----------------|-------------|
| **Free** | 0 zł | ~0 zł (self-service) | N/A |
| **Pro** | 29-49 zł | ~2-5 zł/user | ~10% |
| **Business** | 299 zł | ~30-50 zł/user | ~15% |
| **Enterprise** | 999+ zł | ~150-200 zł/user | ~15-20% |

#### Koszty ukryte:

| Koszt | Opis |
|-------|------|
| **Narzędzia** | Zendesk/Intercom: $50-150/agent/msc |
| **Personel** | Support agent: 5-8k zł/msc brutto |
| **Szkolenia** | Czas na onboarding nowych agentów |
| **Dokumentacja** | Tworzenie i aktualizacja FAQ/docs |
| **Telefon** | Centralka, numery: ~200-500 zł/msc |

---

### Skalowanie supportu

#### Faza 1: Solo founder (0-100 klientów)

```
Ty robisz wszystko:
├── Email support (sprawdzasz 2x dziennie)
├── FAQ / dokumentacja
└── Onboarding calls (sam prowadzisz)

Narzędzia: Gmail + Notion + Calendly
Koszt: 0 zł
```

#### Faza 2: Pierwszy support (100-500 klientów)

```
├── Ty: Klienci Enterprise + eskalacje
├── Support agent (part-time/VA): Tier 1 (proste pytania)
└── Self-service: FAQ, chatbot

Narzędzia: Freshdesk/Zendesk + Chatbot (Tidio)
Koszt: ~3-5k zł/msc
```

#### Faza 3: Team (500-2000 klientów)

```
├── Customer Success Manager: Enterprise klienci
├── Support agent 1: Business tier
├── Support agent 2: Pro tier + Tier 1
└── Chatbot + AI: Automacja prostych pytań

Narzędzia: Intercom/Zendesk Pro
Koszt: ~15-25k zł/msc
```

#### Faza 4: Departament (2000+ klientów)

```
├── Head of Customer Success
├── Account Managers (Enterprise): 2-3 osoby
├── Support Team Lead
├── Support agents: 3-5 osób
├── Technical Support: 1-2 osoby (dla API/integracji)
└── AI/Chatbot: 60-70% prostych pytań

Narzędzia: Salesforce/Zendesk Enterprise
Koszt: ~50-100k zł/msc
```

---

### Metryki supportu (KPIs)

| Metryka | B2C target | B2B target |
|---------|------------|------------|
| **First Response Time** | <24h | <4h |
| **Resolution Time** | <72h | <24h |
| **CSAT (Customer Satisfaction)** | >4.0/5 | >4.5/5 |
| **NPS (Net Promoter Score)** | >30 | >50 |
| **Ticket volume/user/month** | <0.5 | <1.0 |
| **Self-service resolution** | >60% | >40% |

---

### Automatyzacja supportu (zmniejszenie kosztów)

#### Co automatyzować:

| Zadanie | Narzędzie | Oszczędność |
|---------|-----------|-------------|
| **FAQ/Knowledge Base** | Notion/GitBook/Intercom | 30-50% ticketów |
| **Chatbot (proste pytania)** | Intercom/Tidio/Crisp | 20-40% ticketów |
| **Onboarding videos** | Loom/YouTube | 50% czasu onboardingu |
| **Status page** | Statuspage.io/Instatus | Redukcja "czy działa?" ticketów |
| **In-app guides** | Intercom/Pendo/Appcues | 30% "jak to zrobić?" |
| **AI support (GPT)** | Intercom Fin/Zendesk AI | 40-60% Tier 1 |

#### ROI automatyzacji:

```
Bez automatyzacji:
  1000 klientów × 0.5 ticket/msc = 500 ticketów/msc
  500 ticketów × 15 min = 125h/msc = 0.8 FTE

Z automatyzacją (60% self-service):
  500 ticketów × 40% = 200 ticketów/msc
  200 ticketów × 15 min = 50h/msc = 0.3 FTE

Oszczędność: 0.5 FTE = ~3-4k zł/msc
```

---

### Support jako przewaga konkurencyjna

#### B2C - gdzie wygrać:

| Element | Konkurencja | Twoja przewaga |
|---------|-------------|----------------|
| **Szybkość** | 48-72h | 24h |
| **Język** | Często tylko EN | PL natywny |
| **Jakość docs** | Słaba | Świetna (video + tekst) |
| **Community** | Brak | Discord/Slack dla power users |

#### B2B - gdzie wygrać:

| Element | Konkurencja (Agicap) | Twoja przewaga |
|---------|---------------------|----------------|
| **Cena** | €200-2000/msc | 299-999 zł/msc |
| **Lokalność** | Support w EN/FR | Support w PL |
| **Elastyczność** | Korporacja (sztywne) | Startup (elastyczny) |
| **Onboarding** | Standardowy | Dedykowany, dopasowany |
| **Feedback loop** | Wolny | Szybki (bezpośredni kontakt z devs) |

---

### Podsumowanie supportu

| Tier | Support model | Koszt % | Kluczowe |
|------|---------------|---------|----------|
| **Free/Basic** | Self-service | ~0% | FAQ, docs, chatbot |
| **Pro** | Email + chat | ~10% | 24h response |
| **Business** | + Telefon + onboarding | ~15% | 8h response, SLA |
| **Enterprise** | Dedykowany AM | ~15-20% | 4h response, QBR |

#### Rekomendacje per faza:

| Faza | Co robić |
|------|----------|
| **Teraz** | Self-service (docs, FAQ, video) |
| **100+ klientów** | Pierwszy support agent (part-time) |
| **B2B launch** | Dedykowany onboarding process |
| **Enterprise** | Customer Success Manager |

**Kluczowa zasada:**

```
B2C: Automatyzuj maksymalnie (self-service)
B2B: Personalizuj maksymalnie (dedykowany support)

Bo:
- B2C płaci mało → support musi być tani
- B2B płaci dużo → support musi być świetny
```
