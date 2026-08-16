# VID-154: Analiza jakości kategoryzacji transakcji

**Data analizy:** 2026-04-24
**Wersja:** Po implementacji B3 (auto-categorizable suggestions)
**Testowane pliki:** Pekao (791 txns), Nest Bank (402 txns)

## Podsumowanie wyników

### Auto-kategoryzacja B3 (nowa funkcja)

| Bank | Transakcje | Auto-kategoryzowalne | % pokrycia | Kwota |
|------|------------|---------------------|------------|-------|
| **Pekao** | 791 | 49 txns | 6.2% | 95,321 PLN |
| **Nest Bank** | 402 | 28 txns | 7.0% | 280 PLN |

#### Pekao - wykryte typy:
- `BANK_FEE`: 7 txns (45 PLN) → Opłaty bankowe
- `SELF_TRANSFER`: 30 txns (80,776 PLN) → Przelewy własne
- `CASH_WITHDRAWAL`: 12 txns (14,500 PLN) → Wypłaty gotówkowe

#### Nest Bank - wykryte typy:
- `BANK_FEE`: 26 txns (260 PLN) → Opłaty bankowe
- `SELF_TRANSFER`: 2 txns (20 PLN) → Przelewy własne

### Pokrycie kategorii bankowych

| Bank | Mapowania | Pokrycie transakcji |
|------|-----------|---------------------|
| Pekao | 8 kategorii | 742 txns (93.8%) |
| Nest Bank | 7 kategorii | 374 txns (93.0%) |

### Koszt AI
- **~0.06 USD per import** (~$0.00008-0.00015 per transakcję)
- Tokeny: ~6000 per import

---

## Zidentyfikowane anomalie

### ANOMALIA 1: Przelewy do właściciela konta klasyfikowane jako "Inne wydatki"

**Problem:**
W Nest Bank mamy 111 transakcji z wzorcem "LUCJAN BIK" (właściciel konta), które AI zaklasyfikował jako "Inne wydatki" z confidence 90%.

To błąd logiczny - przelewy DO SIEBIE (na inne własne konto) powinny być klasyfikowane jako `SELF_TRANSFER`, nie jako wydatki.

**Dane:**
```
Wzorzec: LUCJAN BIK
Kategoria: Inne wydatki (Inne wydatki)
Transakcje: 111
Kwota: 540,938.77 PLN
Confidence: 90%
Typ: OUTFLOW
```

**Przyczyna:**
Obecnie klasyfikacja `SELF_TRANSFER` działa tylko na podstawie:
1. Typ operacji (np. "Przelew środków własnych")
2. Ten sam numer konta nadawcy i odbiorcy

Ale NIE sprawdzamy czy nazwa odbiorcy/nadawcy to właściciel konta.

**Rozwiązanie:**
Dodać heurystykę w `TransactionClassifier.java`:

1. Przy rejestracji użytkownika zapisywać jego imię i nazwisko
2. W fazie enrichment porównywać nazwę kontrahenta z właścicielem
3. Jeśli nazwa zawiera imię+nazwisko właściciela → `SELF_TRANSFER`

```java
private boolean isOwnerTransfer(String counterpartyName, String ownerName) {
    if (ownerName == null || counterpartyName == null) return false;
    String normalized = counterpartyName.toUpperCase()
        .replaceAll("[^A-Z\\s]", "");
    return normalized.contains(ownerName.toUpperCase());
}
```

**Wpływ:**
- 111 transakcji w Nest Bank zostanie poprawnie sklasyfikowanych
- Prawdopodobnie podobne przypadki w innych importach
- Lepsza jakość kategorii "Przelewy własne"
- Korekta raportu wydatków o ~541,000 PLN

---

### ANOMALIA 2: Firma MINDBOX pojawia się w INFLOW i OUTFLOW

**Problem:**
Wzorzec "MINDBOX" jest sugerowany zarówno dla OUTFLOW jak i INFLOW:

| Typ | Transakcje | Kwota | Kategoria |
|-----|------------|-------|-----------|
| OUTFLOW | 36 | 1,217,094.34 PLN | Inne wydatki |
| INFLOW | 36 | 1,217,094.34 PLN | Zwroty |

**Analiza:**
MINDBOX to firma (spółka z o.o.) - prawdopodobnie:
- OUTFLOW: płatności za usługi/faktury
- INFLOW: zwroty, korekty, lub wypłaty z firmy

To jest POPRAWNE zachowanie AI - ta sama firma może być zarówno źródłem przychodów jak i wydatków.

**Problem głębszy:**
AI sugeruje "Inne wydatki" dla firmy, ale lepiej byłoby:
- Rozpoznać że to transakcje B2B (firma-firma)
- Zaproponować kategorię "Działalność gospodarcza" lub "Usługi biznesowe"

**Rozwiązanie:**

1. **Dodać detekcję firm w enrichment:**
   - Szukać "Sp. z o.o.", "S.A.", "Spółka", "REGON", "NIP" w nazwie
   - Flagować jako `B2B_TRANSACTION`

2. **Dla B2B transakcji sugerować odpowiednie kategorie:**
   ```java
   if (isBusinessEntity(counterpartyName)) {
       return new CategorySuggestion(
           "Usługi biznesowe",
           "Działalność gospodarcza",
           confidence: 85
       );
   }
   ```

3. **W UI pokazywać badge "B2B" przy takich transakcjach**

**Wpływ:**
- Lepsza segmentacja osobiste vs biznesowe
- Możliwość filtrowania transakcji firmowych
- Dokładniejsze raporty wydatków osobistych

---

### ANOMALIA 3: Duży bucket "Inne" w Nest Bank (588 txns, 5.3M PLN)

**Problem:**
W Nest Bank struktura kategorii pokazuje:

| Kategoria | Transakcje | Kwota | % całości |
|-----------|------------|-------|-----------|
| Opłaty obowiązkowe | 333 | 1,165,782 PLN | 36% |
| **Inne** | **588** | **5,323,414 PLN** | **64%** |

588 transakcji (64% wszystkich!) trafia do "Inne" - to za dużo. Oznacza to że AI nie potrafi ich sklasyfikować.

**Możliwe przyczyny:**
1. Brak `bankCategory` w tych transakcjach
2. Nierozpoznawalne wzorce (np. kody referencyjne)
3. Zbyt ogólne opisy transakcji

**Przykłady transakcji w "Inne" (z patternów):**
- LUCJAN BIK: 111 txns → Inne wydatki
- MINDBOX: 36 txns → Inne wydatki
- IFIRMA: 35 txns → Inne wydatki
- PGE: 1 txns → Inne wydatki

**Rozwiązanie wieloetapowe:**

#### ETAP 1: Lepsze podkategorie dla "Inne"
Zamiast jednego bucketu "Inne", podzielić na:
- "Inne - rozrywka" (Badoo, Netflix, Spotify)
- "Inne - zakupy online" (Allegro, Amazon)
- "Inne - usługi" (ifirma, księgowość)
- "Inne - niezidentyfikowane" (ostateczny fallback)

#### ETAP 2: Pattern learning z historii użytkownika
Zapisywać jak użytkownik kategoryzuje transakcje i stosować te same reguły dla przyszłych importów:

```java
// UserPatternCache
Map<String, String> userPatterns = getUserPatterns(userId);
if (userPatterns.containsKey(normalizedDescription)) {
    return userPatterns.get(normalizedDescription);
}
```

#### ETAP 3: Interaktywne uczenie
Po imporcie pokazać użytkownikowi "niezidentyfikowane" transakcje i poprosić o kategoryzację - zapisać jako wzorzec.

**Wpływ:**
- Redukcja bucketu "Inne" z 64% do <20%
- Lepsze raporty wydatków
- Personalizacja pod użytkownika

---

### ANOMALIA 4: Brak CASH_WITHDRAWAL w Nest Bank

**Obserwacja:**
- Pekao ma wykryte `CASH_WITHDRAWAL` (12 txns, 14,500 PLN)
- Nest Bank NIE MA żadnych wypłat gotówkowych

**Możliwe przyczyny:**
1. Użytkownik Nest Bank faktycznie nie wypłaca gotówki
2. Wypłaty są inaczej opisane i nie są wykrywane

**Weryfikacja:**
Przeszukanie wzorców Nest Bank pod kątem "wypłat", "bankomat", "atm" - brak wyników. To oznacza że faktycznie brak wypłat w tym CSV.

**Rozwiązanie (na przyszłość):**
Rozszerzyć detekcję `CASH_WITHDRAWAL` o więcej wzorców:
- "WYPŁATA BANKOMAT"
- "ATM WITHDRAWAL"
- "CASH"
- "BANKOMAT"
- "WYPŁATA WŁASNA"

---

## Podsumowanie priorytetów

| Priorytet | Rekomendacja | Wpływ | Szacowany czas |
|-----------|--------------|-------|----------------|
| **WYSOKI** | Heurystyka self-transfer po nazwie właściciela | 111+ txns, ~541k PLN | 2-3h |
| **WYSOKI** | Rozbicie bucketu "Inne" na podkategorie | 588 txns, 64% → <20% | 4-6h |
| **ŚREDNI** | Detekcja transakcji B2B (firmy) | Lepsza segmentacja | 3-4h |
| **NISKI** | Rozszerzenie wzorców CASH_WITHDRAWAL | Mały wpływ | 1h |

---

## Metryki sukcesu

### Obecny stan:
- Auto-kategoryzacja B3: 6-7% transakcji
- Bucket "Inne": 64% (Nest Bank)
- Błędna kategoryzacja self-transfer: 111 txns

### Cel po implementacji rekomendacji:
- Auto-kategoryzacja: 15-20% transakcji
- Bucket "Inne": <20%
- Błędna kategoryzacja self-transfer: 0

---

## Pliki testowe

| Plik | Bank | Transakcje | Okres |
|------|------|------------|-------|
| `Lista_operacji_20260111_013400.csv` | Pekao | 791 | 2022-01 do 2025-12 |
| `lista_operacji_20260111.csv` | Nest Bank | 402 | 2023-01 do 2025-12 |

---

## Problem z timeoutem frontendu przy uploadzieCSV

### Obserwacja

Podczas testów z aplikacją Flutter wykryto problem z timeoutem:

```
>>> AI ADAPTER REQUEST: POST http://localhost:9090/api/v1/csv-import/upload
<<< AI ADAPTER ERROR: null
<<< MESSAGE: The request took longer than 0:01:30.000000 to receive data.
    It was aborted.
```

### Analiza

| Element | Wartość | Uwagi |
|---------|---------|-------|
| Endpoint | `/api/v1/csv-import/upload` | Działa poprawnie |
| Timeout frontendu | 90 sekund | Za krótki |
| Czas transformacji Nest Bank (403 txns) | ~57 sekund | OK |
| Czas transformacji Pekao (791 txns) | ~60-90 sekund | Przekracza timeout |

### Problem

Frontend (Flutter/Dio) ma ustawiony `receiveTimeout: 90 sekund`, ale transformacja AI dla większych plików może trwać 1-2 minuty. Request jest przerywany przed otrzymaniem odpowiedzi.

### Rozwiązania

#### Opcja A: Zwiększyć timeout we frontendzie (szybkie)

```dart
// W Dio configuration:
BaseOptions(
  receiveTimeout: Duration(minutes: 5), // zamiast 90s
  sendTimeout: Duration(minutes: 5),
  connectTimeout: Duration(seconds: 30),
)
```

**Zalety:** Szybka implementacja
**Wady:** Użytkownik nie widzi postępu, UI "zamiera"

#### Opcja B: Asynchroniczne przetwarzanie z pollowaniem (zalecane)

1. Frontend wysyła plik → Backend natychmiast zwraca `jobId`
2. Frontend polluje status: `GET /api/v1/csv-import/{jobId}/status`
3. Gdy gotowe → pobiera wynik

```
Sekwencja:
┌─────────┐     POST /upload        ┌─────────┐
│ Flutter │ ─────────────────────► │ Backend │
│         │ ◄───────────────────── │         │
│         │   { jobId: "abc123" }  │         │
│         │                        │         │
│         │   GET /status/abc123   │         │
│         │ ─────────────────────► │         │
│         │ ◄───────────────────── │         │
│         │   { progress: 30% }    │         │
│         │                        │         │
│         │   GET /status/abc123   │         │
│         │ ─────────────────────► │         │
│         │ ◄───────────────────── │         │
│         │   { status: DONE }     │         │
└─────────┘                        └─────────┘
```

**Zalety:**
- Użytkownik widzi pasek postępu
- Brak zamrażania UI
- Możliwość anulowania

**Wady:**
- Wymaga zmian w backendzie i frontendzie

#### Opcja C: Server-Sent Events (SSE) dla progress

Backend wysyła eventy podczas przetwarzania:

```
Event: progress
Data: { "stage": "DETECTING_FORMAT", "percent": 10 }

Event: progress
Data: { "stage": "AI_TRANSFORM", "percent": 50 }

Event: complete
Data: { "transformationId": "abc123", ... }
```

**Zalety:** Real-time progress
**Wady:** Wymaga SSE support we Flutter

### Rekomendacja

**Krótkoterminowo (Opcja A):** Zwiększyć timeout do 5 minut.

**Długoterminowo (Opcja B):** Zaimplementować asynchroniczne przetwarzanie z pollowaniem statusu dla lepszego UX.

### Czasy przetwarzania (benchmark)

| Plik | Bank | Transakcje | Czas transformacji |
|------|------|------------|-------------------|
| lista_operacji_20260111.csv | Nest Bank | 403 | ~57s |
| Lista_operacji_20260111_013400.csv | Pekao | 791 | ~60s |

---

## Powiązane tickety

- VID-153: StagingSessionEntity for staging session lifecycle management
- VID-152: counterpartyAccount field and hybrid pattern grouping
- VID-151: AI-powered transaction categorization for bank CSV import
