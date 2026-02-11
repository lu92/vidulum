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
