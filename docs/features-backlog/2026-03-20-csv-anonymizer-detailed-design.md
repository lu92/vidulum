# CsvAnonymizer - Szczegółowy Design

*Data utworzenia: 2026-03-20*
*Status: TODO (niezaimplementowane)*
*Powiązany dokument: [AI Bank CSV Adapter Design](./2026-03-19-ai-bank-csv-adapter-design.md)*

---

## Spis treści

1. [Cel i motywacja](#1-cel-i-motywacja)
2. [Architektura przepływu danych](#2-architektura-przepływu-danych)
3. [Strategie anonimizacji per typ danych](#3-strategie-anonimizacji-per-typ-danych)
4. [Algorytm CsvAnonymizer](#4-algorytm-csvanonymizer)
5. [Wykrywanie typu danych - Regexy](#5-wykrywanie-typu-danych---regexy)
6. [Obsługa wielu języków i krajów](#6-obsługa-wielu-języków-i-krajów)
7. [CountryConfig - konfiguracja per kraj](#7-countryconfig---konfiguracja-per-kraj)
8. [Wykorzystanie kraju z CashFlow](#8-wykorzystanie-kraju-z-cashflow)
9. [Biblioteki Java do anonimizacji](#9-biblioteki-java-do-anonimizacji)
10. [Słabe strony rozwiązania Regex](#10-słabe-strony-rozwiązania-regex)
11. [Alternatywa: AI do wykrywania typów](#11-alternatywa-ai-do-wykrywania-typów)
12. [Porównanie: Regex vs AI](#12-porównanie-regex-vs-ai)
13. [Rekomendowane podejście hybrydowe](#13-rekomendowane-podejście-hybrydowe)
14. [Plan implementacji](#14-plan-implementacji)
15. [Mapping Rules Cache - przechowywanie reguł mapowania](#15-mapping-rules-cache---przechowywanie-reguł-mapowania)
16. [Zabezpieczenia i walidacja reguł](#16-zabezpieczenia-i-walidacja-reguł)
17. [Znane luki do rozwiązania (TODO)](#17-znane-luki-do-rozwiązania-todo)

---

## 1. Cel i motywacja

### Problem

Obecna implementacja AI Bank CSV Adapter wysyła **pełny plik CSV** (np. 400 wierszy) do AI, co powoduje:

| Problem | Skutek |
|---------|--------|
| **Prywatność** | PII (dane osobowe) wysyłane do zewnętrznego API |
| **Koszt** | ~$0.21 per plik (400 wierszy) |
| **Czas** | ~35 sekund dla 20 wierszy, ~12 minut dla 400 |
| **GDPR** | Potencjalne naruszenie przepisów |

### Rozwiązanie

Anonimizacja danych **przed wysłaniem do AI**:
- Wyślij tylko **5-10 wierszy** (sample)
- Zamień PII na **placeholdery** (`PERSON_1`, `IBAN_1`)
- AI zwraca **reguły mapowania** (nie przetworzone dane)
- Reguły stosowane **lokalnie** na pełnych danych

### Korzyści

| Metryka | Przed | Po anonimizacji |
|---------|-------|-----------------|
| **Payload do AI** | 400 wierszy | 5-10 wierszy |
| **Koszt** | ~$0.21 | ~$0.01 |
| **Czas** | ~12 min | ~2-3s |
| **Prywatność** | ❌ PII do AI | ✅ Tylko placeholdery |
| **Cache** | ❌ Każdy plik | ✅ Per bank (globalny) |

---

## 2. Architektura przepływu danych

### Obecny flow (bez anonimizacji)

```
┌─────────────────┐                              ┌─────────────────┐
│  Oryginalny CSV │─────────────────────────────▶│   Claude AI     │
│  (400 wierszy)  │         PEŁNE DANE           │                 │
│  z PII          │                              │                 │
└─────────────────┘                              └────────┬────────┘
                                                          │
                                                 Canonical CSV
                                                          │
                                                          ▼
                                                 ┌─────────────────┐
                                                 │  Bank Ingestion │
                                                 └─────────────────┘
```

### Docelowy flow (z anonimizacją)

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Oryginalny CSV │────▶│  CsvAnonymizer   │────▶│   Sample 5-10   │
│  (400 wierszy)  │     │                  │     │   wierszy       │
│  z PII          │     │  - Wykryj typy   │     │   (anonimizowane)
└─────────────────┘     │  - Zamień PII    │     └────────┬────────┘
        │               └──────────────────┘              │
        │                                                 ▼
        │                                        ┌─────────────────┐
        │                                        │   Claude AI     │
        │                                        │   (reguły)      │
        │                                        └────────┬────────┘
        │                                                 │
        │                          Reguły mapowania JSON  │
        │                          (nie dane!)            │
        │                                                 ▼
        │               ┌──────────────────┐     ┌───────────────────┐
        │               │ LocalTransformer │◀────│ MappingRules JSON │
        │               │ (stosuje reguły) │     │                   │
        │               └────────┬─────────┘     └───────────────────┘
        │                        │
        └────────────────────────┘
                                 │
                                 ▼
                        ┌─────────────────┐
                        │  Canonical CSV  │
                        │  (400 wierszy)  │
                        └─────────────────┘
```

---

## 3. Strategie anonimizacji per typ danych

### Tabela strategii

| Typ danych | Oryginał | Zanonimizowany | Strategia | Dlaczego? |
|------------|----------|----------------|-----------|-----------|
| **Kwota** | `-3000.50` | `-1847.23` | Losowa kwota (zachowuje znak) | AI musi wiedzieć co to INFLOW/OUTFLOW |
| **IBAN** | `PL98124014441111001078171074` | `IBAN_1` | Placeholder z indeksem | Unikalny identyfikator |
| **Nazwa osoby** | `Jan Kowalski` | `PERSON_1` | Placeholder z indeksem | PII do usunięcia |
| **Nazwa firmy** | `NETFLIX SP. Z O.O.` | `COMPANY_1` | Placeholder z indeksem | Może zawierać PII |
| **Opis/Tytuł** | `czynsz za grudzień` | `TEXT_1` | Placeholder z indeksem | Może zawierać PII |
| **Data** | `31-12-2025` | `31-12-2025` | **Bez zmian** | AI musi wykryć format daty |
| **Kategoria bankowa** | `Przelewy wychodzące` | `Przelewy wychodzące` | **Bez zmian** | AI musi zmapować na INFLOW/OUTFLOW |
| **Waluta** | `PLN` | `PLN` | **Bez zmian** | Potrzebne do walidacji |

### Kluczowe zasady

1. **Ta sama wartość = ten sam placeholder**
   - `Jan Kowalski` zawsze → `PERSON_1`
   - AI widzi powtórzenia (ten sam odbiorca)

2. **Zachowaj znak kwoty**
   - `-3000` → `-1847.23` (ujemna = OUTFLOW)
   - `5500` → `4521.89` (dodatnia = INFLOW)

3. **Metadata bez zmian**
   - `"Numer rachunku: 93187010..."` - pomaga wykryć bank

---

## 4. Algorytm CsvAnonymizer

### Struktura klasy

```java
@Component
public class CsvAnonymizer {

    // Placeholdery z indeksem dla unikalnych wartości
    private final Map<String, String> ibanPlaceholders = new HashMap<>();
    private final Map<String, String> personPlaceholders = new HashMap<>();
    private final Map<String, String> companyPlaceholders = new HashMap<>();
    private final Map<String, String> textPlaceholders = new HashMap<>();

    private int ibanCounter = 1;
    private int personCounter = 1;
    private int companyCounter = 1;
    private int textCounter = 1;

    private final Faker faker;
    private final CountryConfig countryConfig;

    public CsvAnonymizer(String countryCode) {
        this.faker = new Faker(getLocale(countryCode));
        this.countryConfig = CountryConfig.forCountry(countryCode);
    }
}
```

### Główny flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                    anonymize(csvContent, sampleSize=10)              │
└──────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│  1. Parsuj CSV → List<String[]> rows                                 │
│  2. Zachowaj header rows (metadata) BEZ ZMIAN                        │
│  3. Znajdź headerRow (pierwsza z nazwami kolumn)                     │
│  4. Wybierz sample dataRows (losowo lub pierwsze N)                  │
└──────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│  5. Dla każdej kolumny: detectColumnType(columnValues)               │
│     → Map<Integer, DataType> columnTypes                             │
└──────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│  6. Dla każdego sample row:                                          │
│     Dla każdej komórki: anonymizeCell(value, columnType)             │
└──────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│  7. Zbuduj anonymized CSV (metadata + header + sample rows)          │
└──────────────────────────────────────────────────────────────────────┘
```

### Implementacja głównej metody

```java
public AnonymizationResult anonymize(String csvContent, int sampleSize) {
    // 1. Reset counters
    resetPlaceholders();

    // 2. Parse CSV
    List<String[]> allRows = parseCsv(csvContent);

    // 3. Find header row (first row with column names)
    int headerRowIndex = findHeaderRowIndex(allRows);

    // 4. Separate metadata, header, and data rows
    List<String[]> metadataRows = allRows.subList(0, headerRowIndex);
    String[] headerRow = allRows.get(headerRowIndex);
    List<String[]> dataRows = allRows.subList(headerRowIndex + 1, allRows.size());

    // 5. Select sample rows
    List<String[]> sampleRows = selectSample(dataRows, sampleSize);

    // 6. Detect column types
    Map<Integer, DataType> columnTypes = detectColumnTypes(sampleRows, headerRow);

    // 7. Anonymize sample rows
    List<String[]> anonymizedRows = new ArrayList<>();
    for (String[] row : sampleRows) {
        String[] anonymizedRow = new String[row.length];
        for (int i = 0; i < row.length; i++) {
            anonymizedRow[i] = anonymizeCell(row[i], columnTypes.get(i));
        }
        anonymizedRows.add(anonymizedRow);
    }

    // 8. Build result CSV
    String anonymizedCsv = buildCsv(metadataRows, headerRow, anonymizedRows);

    return new AnonymizationResult(
        anonymizedCsv,
        columnTypes,
        sampleRows.size(),
        allRows.size()
    );
}
```

### Wykrywanie typu kolumny

```java
public enum DataType {
    DATE,           // Bez zmian
    AMOUNT,         // Losowa kwota (zachowaj znak)
    CURRENCY,       // Bez zmian
    IBAN,           // IBAN_1, IBAN_2...
    PERSON_NAME,    // PERSON_1, PERSON_2...
    COMPANY_NAME,   // COMPANY_1, COMPANY_2...
    BANK_CATEGORY,  // Bez zmian
    FREE_TEXT       // TEXT_1, TEXT_2...
}

DataType detectColumnType(List<String> columnValues) {
    int total = columnValues.size();
    int dateMatches = 0, amountMatches = 0, ibanMatches = 0;
    int personMatches = 0, companyMatches = 0, currencyMatches = 0;
    int categoryMatches = 0;

    for (String value : columnValues) {
        if (value == null || value.isBlank()) continue;

        if (isDate(value))           dateMatches++;
        if (isAmount(value))         amountMatches++;
        if (isIban(value))           ibanMatches++;
        if (isPerson(value))         personMatches++;
        if (isCompany(value))        companyMatches++;
        if (isCurrency(value))       currencyMatches++;
        if (isBankCategory(value))   categoryMatches++;
    }

    // >80% match = pewny typ
    if (dateMatches > total * 0.8)     return DATE;
    if (amountMatches > total * 0.8)   return AMOUNT;
    if (ibanMatches > total * 0.8)     return IBAN;
    if (currencyMatches > total * 0.8) return CURRENCY;
    if (categoryMatches > total * 0.5) return BANK_CATEGORY;
    if (companyMatches > total * 0.5)  return COMPANY_NAME;
    if (personMatches > total * 0.5)   return PERSON_NAME;

    return FREE_TEXT;  // Default
}
```

### Anonimizacja komórki

```java
String anonymizeCell(String value, DataType type) {
    if (value == null || value.isBlank()) {
        return value;
    }

    return switch (type) {
        case DATE -> value;  // BEZ ZMIAN
        case CURRENCY -> value;  // BEZ ZMIAN
        case BANK_CATEGORY -> value;  // BEZ ZMIAN

        case AMOUNT -> anonymizeAmount(value);
        case IBAN -> getOrCreatePlaceholder(value, ibanPlaceholders, "IBAN_", ibanCounter++);
        case PERSON_NAME -> getOrCreatePlaceholder(value, personPlaceholders, "PERSON_", personCounter++);
        case COMPANY_NAME -> getOrCreatePlaceholder(value, companyPlaceholders, "COMPANY_", companyCounter++);
        case FREE_TEXT -> getOrCreatePlaceholder(value, textPlaceholders, "TEXT_", textCounter++);
    };
}

// Ta sama wartość → ten sam placeholder
String getOrCreatePlaceholder(String value, Map<String, String> cache, String prefix, int counter) {
    return cache.computeIfAbsent(value, v -> prefix + counter);
}

String anonymizeAmount(String value) {
    // Zachowaj znak (+ lub -)
    boolean negative = value.trim().startsWith("-");

    // Losowa kwota 100-9999 z 2 miejscami po przecinku
    double randomAmount = 100 + Math.random() * 9899;
    String formatted = String.format("%.2f", randomAmount);

    // Zachowaj oryginalny separator dziesiętny
    if (value.contains(",")) {
        formatted = formatted.replace(".", ",");
    }

    return negative ? "-" + formatted : formatted;
}
```

---

## 5. Wykrywanie typu danych - Regexy

### Wzorce dla poszczególnych typów

```java
// === DATE - różne formaty ===
private static final Pattern DATE_PATTERN = Pattern.compile(
    "^(\\d{2}[-./]\\d{2}[-./]\\d{4}|\\d{4}[-./]\\d{2}[-./]\\d{2})$"
);
// Matches: 31-12-2025, 31.12.2025, 2025-12-31, 12/31/2025

// === AMOUNT - liczby z opcjonalnym znakiem i separatorem ===
private static final Pattern AMOUNT_PATTERN = Pattern.compile(
    "^-?\\d{1,3}([\\s.,]?\\d{3})*([.,]\\d{1,2})?$"
);
// Matches: -3000, 3000.50, -3000,50, 3 000,50

// === IBAN - międzynarodowy format ===
private static final Pattern IBAN_PATTERN = Pattern.compile(
    "^[A-Z]{2}\\d{2}[A-Z0-9]{10,30}$"
);
// Matches: PL98124014441111001078171074, DE89370400440532013000

// === CURRENCY - 3-literowy kod ===
private static final Pattern CURRENCY_PATTERN = Pattern.compile(
    "^(PLN|EUR|USD|GBP|CHF|CZK|SEK|NOK|DKK)$"
);

// === COMPANY - suffiksy firmowe (międzynarodowe) ===
private static final Pattern COMPANY_PATTERN = Pattern.compile(
    ".*(" +
    // Polska
    "SP\\.?\\s*(Z\\.?\\s*O\\.?\\s*O\\.?|J\\.?)|S\\.?A\\.?|" +
    // Niemcy/Austria/Szwajcaria
    "GMBH|AG|KG|OHG|E\\.?\\s*V\\.?|" +
    // UK/USA/Irlandia
    "LTD\\.?|LLC|INC\\.?|CORP\\.?|PLC|LLP|" +
    // Francja
    "SARL|SAS|SA|EURL|SCI|" +
    // Hiszpania
    "S\\.?L\\.?|S\\.?L\\.?U\\.?|" +
    // Włochy
    "SRL|SPA|SNCS|" +
    // Holandia/Belgia
    "BV|NV|VOF|CV|" +
    // Skandynawia
    "A/S|AS|AB|OY|" +
    // Inne
    "PTY|CO\\.?\\s*LTD" +
    ").*",
    Pattern.CASE_INSENSITIVE
);

// === PERSON NAME - wielojęzyczne znaki (Unicode) ===
private static final Pattern PERSON_PATTERN = Pattern.compile(
    "^\\p{Lu}\\p{Ll}+\\s+\\p{Lu}\\p{Ll}+.*$",
    Pattern.UNICODE_CHARACTER_CLASS
);
// \p{Lu} = Unicode uppercase letter (Ą, Ö, É, Ñ...)
// \p{Ll} = Unicode lowercase letter (ą, ö, é, ñ...)
// Matches: Jan Kowalski, Hans Müller, Jean Dupont, Juan García
```

### Implementacja metod sprawdzających

```java
private boolean isDate(String value) {
    return DATE_PATTERN.matcher(value.trim()).matches();
}

private boolean isAmount(String value) {
    return AMOUNT_PATTERN.matcher(value.trim()).matches();
}

private boolean isIban(String value) {
    if (value == null || value.length() < 15) return false;
    String cleaned = value.replaceAll("\\s", "").toUpperCase();
    return IBAN_PATTERN.matcher(cleaned).matches();
}

private boolean isCurrency(String value) {
    return CURRENCY_PATTERN.matcher(value.trim().toUpperCase()).matches();
}

private boolean isCompany(String value) {
    return COMPANY_PATTERN.matcher(value.trim()).matches();
}

private boolean isPerson(String value) {
    return PERSON_PATTERN.matcher(value.trim()).matches();
}

private boolean isBankCategory(String value) {
    return countryConfig.bankCategories().contains(value) ||
           countryConfig.bankCategories().stream()
               .anyMatch(cat -> value.toLowerCase().contains(cat.toLowerCase()));
}
```

---

## 6. Obsługa wielu języków i krajów

### Wyzwania językowe

| Język | Imię/Nazwisko | Firma | Kategoria bankowa |
|-------|---------------|-------|-------------------|
| 🇵🇱 PL | `Jan Kowalski` | `ACME SP. Z O.O.` | `Przelewy wychodzące` |
| 🇩🇪 DE | `Hans Müller` | `ACME GMBH` | `Überweisung` |
| 🇬🇧 UK | `John Smith` | `ACME LTD` | `Transfer Out` |
| 🇫🇷 FR | `Jean Dupont` | `ACME SARL` | `Virement sortant` |
| 🇪🇸 ES | `Juan García` | `ACME S.L.` | `Transferencia saliente` |
| 🇮🇹 IT | `Marco Rossi` | `ACME SRL` | `Bonifico` |
| 🇳🇱 NL | `Jan de Vries` | `ACME BV` | `Overboeking` |

### Wykrywanie języka CSV

```java
public enum CsvLanguage {
    PL, DE, EN, FR, ES, IT, NL, UNKNOWN
}

CsvLanguage detectLanguage(String csvContent) {
    String lower = csvContent.toLowerCase();

    // Słowa kluczowe per język (nagłówki, kategorie)
    Map<CsvLanguage, List<String>> keywords = Map.of(
        PL, List.of("data", "kwota", "opis", "przelew", "wpłata", "wypłata", "odbiorca"),
        DE, List.of("datum", "betrag", "verwendungszweck", "überweisung", "empfänger"),
        EN, List.of("date", "amount", "description", "transfer", "payee", "recipient"),
        FR, List.of("date", "montant", "libellé", "virement", "bénéficiaire"),
        ES, List.of("fecha", "importe", "concepto", "transferencia", "beneficiario"),
        IT, List.of("data", "importo", "descrizione", "bonifico", "beneficiario"),
        NL, List.of("datum", "bedrag", "omschrijving", "overboeking", "begunstigde")
    );

    // Licz dopasowania
    Map<CsvLanguage, Integer> scores = new EnumMap<>(CsvLanguage.class);
    for (var entry : keywords.entrySet()) {
        int score = (int) entry.getValue().stream()
            .filter(lower::contains)
            .count();
        scores.put(entry.getKey(), score);
    }

    // Zwróć język z najwyższym score
    return scores.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .filter(e -> e.getValue() >= 2)  // minimum 2 dopasowania
        .map(Map.Entry::getKey)
        .orElse(UNKNOWN);
}
```

### IBAN - długości per kraj

```java
private static final Map<String, Integer> IBAN_LENGTHS = Map.ofEntries(
    entry("PL", 28),  // PL + 26 cyfr
    entry("DE", 22),  // DE + 20 znaków
    entry("GB", 22),
    entry("FR", 27),
    entry("ES", 24),
    entry("IT", 27),
    entry("NL", 18),
    entry("AT", 20),
    entry("CH", 21),
    entry("BE", 16),
    entry("CZ", 24),
    entry("SE", 24),
    entry("NO", 15),
    entry("DK", 18)
);
```

### Formaty per kraj

| Kraj | Separator CSV | Separator dziesiętny | Format daty |
|------|---------------|---------------------|-------------|
| 🇵🇱 PL | `,` lub `;` | `,` (1234,56) | DD-MM-YYYY, DD.MM.YYYY |
| 🇩🇪 DE | `;` | `,` (1234,56) | DD.MM.YYYY |
| 🇬🇧 UK | `,` | `.` (1234.56) | DD/MM/YYYY |
| 🇺🇸 US | `,` | `.` (1234.56) | MM/DD/YYYY |
| 🇫🇷 FR | `;` | `,` (1234,56) | DD/MM/YYYY |

---

## 7. CountryConfig - konfiguracja per kraj

### Model konfiguracji

```java
public record CountryConfig(
    String countryCode,
    String ibanPrefix,
    int ibanLength,
    Pattern personNamePattern,
    Set<String> companySuffixes,
    Set<String> bankCategories,
    String decimalSeparator,
    String dateFormat
) {

    private static final Map<String, CountryConfig> CONFIGS = Map.of(
        "PL", new CountryConfig(
            "PL", "PL", 28,
            Pattern.compile("^[A-ZĄĆĘŁŃÓŚŹŻ][a-ząćęłńóśźż]+\\s+[A-ZĄĆĘŁŃÓŚŹŻ][a-ząćęłńóśźż]+.*$"),
            Set.of("SP. Z O.O.", "SP.Z.O.O.", "S.A.", "SP. J.", "SP.K."),
            Set.of("Przelewy wychodzące", "Przelewy przychodzące", "Opłaty i prowizje",
                   "Płatność kartą", "Wypłata gotówkowa", "Wpłata gotówkowa",
                   "Przelew własny", "Zlecenie stałe", "Polecenie zapłaty"),
            ",",
            "dd-MM-yyyy"
        ),
        "DE", new CountryConfig(
            "DE", "DE", 22,
            Pattern.compile("^[A-ZÄÖÜß][a-zäöüß]+\\s+[A-ZÄÖÜß][a-zäöüß]+.*$"),
            Set.of("GMBH", "AG", "KG", "OHG", "E.V.", "UG"),
            Set.of("Überweisung", "Lastschrift", "Dauerauftrag", "Kartenzahlung",
                   "Bargeldabhebung", "Bargeldeinzahlung", "Gebühren", "Zinsen"),
            ",",
            "dd.MM.yyyy"
        ),
        "GB", new CountryConfig(
            "GB", "GB", 22,
            Pattern.compile("^[A-Z][a-z]+\\s+[A-Z][a-z]+.*$"),
            Set.of("LTD", "LTD.", "PLC", "LLP", "LIMITED"),
            Set.of("Transfer Out", "Transfer In", "Direct Debit", "Standing Order",
                   "Card Payment", "Cash Withdrawal", "Cash Deposit", "Fees", "Interest"),
            ".",
            "dd/MM/yyyy"
        ),
        "FR", new CountryConfig(
            "FR", "FR", 27,
            Pattern.compile("^[A-ZÀÂÇÉÈÊËÎÏÔÙÛÜ][a-zàâçéèêëîïôùûü]+\\s+[A-ZÀÂÇÉÈÊËÎÏÔÙÛÜ][a-zàâçéèêëîïôùûü]+.*$"),
            Set.of("SARL", "SAS", "SA", "EURL", "SCI"),
            Set.of("Virement sortant", "Virement entrant", "Prélèvement",
                   "Paiement carte", "Retrait", "Dépôt", "Frais"),
            ",",
            "dd/MM/yyyy"
        ),
        "ES", new CountryConfig(
            "ES", "ES", 24,
            Pattern.compile("^[A-ZÁÉÍÓÚÑ][a-záéíóúñ]+\\s+[A-ZÁÉÍÓÚÑ][a-záéíóúñ]+.*$"),
            Set.of("S.L.", "S.L.U.", "S.A."),
            Set.of("Transferencia saliente", "Transferencia entrante", "Domiciliación",
                   "Pago con tarjeta", "Retirada de efectivo", "Comisiones"),
            ",",
            "dd/MM/yyyy"
        )
        // ... więcej krajów
    );

    public static CountryConfig forCountry(String code) {
        return CONFIGS.getOrDefault(code.toUpperCase(), CONFIGS.get("GB"));  // Default: UK/English
    }
}
```

### Użycie w CsvAnonymizer

```java
@Component
public class CsvAnonymizer {

    private final Faker faker;
    private final CountryConfig countryConfig;

    public CsvAnonymizer(String countryCode) {
        this.faker = new Faker(getLocale(countryCode));
        this.countryConfig = CountryConfig.forCountry(countryCode);
    }

    private Locale getLocale(String country) {
        return switch (country) {
            case "PL" -> new Locale("pl", "PL");
            case "DE" -> new Locale("de", "DE");
            case "FR" -> new Locale("fr", "FR");
            case "ES" -> new Locale("es", "ES");
            case "IT" -> new Locale("it", "IT");
            case "NL" -> new Locale("nl", "NL");
            case "GB" -> Locale.UK;
            default -> Locale.ENGLISH;
        };
    }

    // Metody używające countryConfig
    private boolean isIbanForCountry(String value) {
        if (value == null) return false;
        String cleaned = value.replaceAll("\\s", "").toUpperCase();
        return cleaned.startsWith(countryConfig.ibanPrefix()) &&
               cleaned.length() == countryConfig.ibanLength();
    }

    private boolean hasCompanySuffix(String value) {
        String upper = value.toUpperCase();
        return countryConfig.companySuffixes().stream().anyMatch(upper::contains);
    }

    private boolean isBankCategory(String value) {
        return countryConfig.bankCategories().contains(value) ||
               countryConfig.bankCategories().stream()
                   .anyMatch(cat -> value.toLowerCase().contains(cat.toLowerCase()));
    }
}
```

---

## 8. Wykorzystanie kraju z CashFlow

### Kontekst z CashFlow

CashFlow jest tworzony **przed** wgraniem pliku CSV, więc znamy:

```java
CashFlow {
    currency: "PLN",                              // → Kraj: PL
    bankAccount: {
        bankAccountNumber: "PL93187010452083...", // → IBAN prefix: PL
        bankName: "Nest Bank"                     // → Bank
    }
}
```

### Wyciąganie kraju z CashFlow

```java
String getCountryFromCashFlow(CashFlow cf) {
    // Priorytet: IBAN > waluta
    if (cf.getBankAccount() != null &&
        cf.getBankAccount().getIban() != null &&
        cf.getBankAccount().getIban().length() >= 2) {
        return cf.getBankAccount().getIban().substring(0, 2);  // "PL", "DE"...
    }
    return currencyToCountry(cf.getCurrency());  // Fallback
}

private String currencyToCountry(String currency) {
    return switch (currency) {
        case "PLN" -> "PL";
        case "EUR" -> "DE";  // Default EUR country
        case "GBP" -> "GB";
        case "CHF" -> "CH";
        case "CZK" -> "CZ";
        case "SEK" -> "SE";
        case "NOK" -> "NO";
        case "DKK" -> "DK";
        default -> "GB";  // Fallback to English
    };
}
```

### Jak to upraszcza CsvAnonymizer

| Aspekt | Bez wiedzy o kraju | Z krajem z CashFlow |
|--------|-------------------|---------------------|
| **Regexy** | Muszą obsłużyć WSZYSTKIE formaty | Tylko format dla JEDNEGO kraju |
| **False positives** | Wysokie (UK date vs US date) | Niskie |
| **Suffiksy firmowe** | ~50 różnych | ~5-6 dla kraju |
| **Kategorie bankowe** | ~100+ we wszystkich językach | ~10-15 dla kraju |
| **IBAN walidacja** | Musi sprawdzić 30+ formatów | Jeden format |
| **Złożoność** | O(n) - wszystkie kraje | O(1) - jeden kraj |
| **Wydajność** | Wolniejsze | Szybsze |

### Flow z krajem z CashFlow

```
┌─────────────────────────────────────────────────────────────────────┐
│  FLOW Z KRAJEM Z CASHFLOW                                           │
└─────────────────────────────────────────────────────────────────────┘

  CashFlow                    CsvAnonymizer                    AI
     │                             │                            │
     │ currency: PLN               │                            │
     │ iban: PL9318701...          │                            │
     │                             │                            │
     └──────────────┬──────────────┘                            │
                    │                                           │
                    ▼                                           │
           ┌───────────────┐                                    │
           │ CountryConfig │                                    │
           │ country: "PL" │                                    │
           │ locale: pl_PL │                                    │
           │ suffixes: [SP.│                                    │
           │   Z O.O., ...] │                                    │
           └───────┬───────┘                                    │
                   │                                            │
                   ▼                                            │
           ┌───────────────┐     ┌───────────────┐              │
           │ Detect types  │────▶│ Anonymize     │──────────────▶
           │ (PL patterns) │     │ (PL Faker)    │   5-10 rows
           └───────────────┘     └───────────────┘
```

---

## 9. Biblioteki Java do anonimizacji

### 1. DataFaker (Rekomendowane)

Najlepsza opcja do generowania fake data i anonimizacji.

**Maven:**
```xml
<dependency>
    <groupId>net.datafaker</groupId>
    <artifactId>datafaker</artifactId>
    <version>2.5.4</version>
</dependency>
```

**Użycie:**
```java
Faker faker = new Faker(new Locale("pl"));  // Polski!

String fakeName = faker.name().fullName();        // "Piotr Nowak"
String fakeIban = faker.finance().iban("PL");     // Fake polski IBAN
String fakeCompany = faker.company().name();      // "Kowalski Sp. z o.o."
BigDecimal fakeAmount = faker.money().amount();   // Losowa kwota
```

**Zalety:**
- Wielojęzyczne (60+ locales)
- Aktywny rozwój
- Prosta integracja

**Źródła:**
- [DataFaker - GitHub](https://github.com/datafaker-net/datafaker)
- [DataFaker - Maven](https://mvnrepository.com/artifact/net.datafaker/datafaker)
- [Introduction to Datafaker - Baeldung](https://www.baeldung.com/java-datafaker)

### 2. ARX Data Anonymization Tool

Profesjonalne narzędzie do anonimizacji z metodami k-anonymity, l-diversity.

```java
// Manualna instalacja do lokalnego Maven repo
mvn install:install-file -Dfile=libarx-3.9.2.jar \
    -DgroupId=org.deidentifier -DartifactId=libarx -Dversion=3.9.2
```

```java
ARXAnonymizer anonymizer = new ARXAnonymizer();
ARXConfiguration config = ARXConfiguration.create();
config.addPrivacyModel(new KAnonymity(2));
ARXResult result = anonymizer.anonymize(data, config);
```

**Zalety:** GDPR compliance, academic-grade, CSV support

**Źródła:**
- [ARX Data Anonymization Tool](https://arx.deidentifier.org/)
- [ARX GitHub](https://github.com/arx-deidentifier/arx)

### 3. J-DOTM (Java Data Obfuscation Through Masking)

Lekkie narzędzie do maskowania.

**Źródło:** [J-DOTM GitHub](https://github.com/pajohri/jdotm)

### Porównanie dla CsvAnonymizer

| Biblioteka | Wykrywanie PII | Wielojęzyczne | CSV | Rekomendacja |
|------------|----------------|---------------|-----|--------------|
| **DataFaker** | ❌ (ręczne) | ✅ 60+ locales | ❌ | Generowanie fake data |
| **ARX** | ❌ (ręczne) | ⚠️ Ograniczone | ✅ | GDPR compliance |
| **Stanford NLP** | ✅ NER | ✅ | ❌ | Wykrywanie osób/firm |
| **Własna impl.** | ✅ Regex | ✅ | ✅ | **Najlepsze dla CSV** |

### Rekomendacja

**Hybryda: własne wykrywanie + DataFaker/placeholdery do generowania:**

```java
@Component
@RequiredArgsConstructor
public class CsvAnonymizer {

    private final Faker faker = new Faker(new Locale("pl"));

    // Opcja A: DataFaker (realistyczne dane)
    String anonymizeCellWithFaker(String value, DataType type) {
        return switch (type) {
            case PERSON_NAME -> faker.name().fullName();
            case COMPANY_NAME -> faker.company().name();
            case IBAN -> faker.finance().iban("PL");
            case AMOUNT -> faker.number().randomDouble(2, 100, 9999) + "";
            case FREE_TEXT -> faker.lorem().sentence();
            default -> value;
        };
    }

    // Opcja B: Placeholdery (prostsze dla AI)
    String anonymizeCellWithPlaceholders(String value, DataType type) {
        return switch (type) {
            case PERSON_NAME -> getOrCreatePlaceholder(value, personPlaceholders, "PERSON_");
            case COMPANY_NAME -> getOrCreatePlaceholder(value, companyPlaceholders, "COMPANY_");
            case IBAN -> getOrCreatePlaceholder(value, ibanPlaceholders, "IBAN_");
            case FREE_TEXT -> getOrCreatePlaceholder(value, textPlaceholders, "TEXT_");
            default -> value;
        };
    }
}
```

**Dlaczego nie gotowa biblioteka?**
- Żadna nie wykrywa automatycznie typów kolumn w bank CSV
- ARX jest overkill (k-anonymity nie potrzebne dla sample)
- Stanford NLP jest ciężkie (~500MB model)

---

## 10. Słabe strony rozwiązania Regex

### 1. Wykrywanie typu kolumny - fałszywe pozytywne/negatywne

| Problem | Przykład | Skutek |
|---------|----------|--------|
| **Imię jak firma** | `"Adam Opel"` (osoba) vs `"Opel"` (firma) | Błędna klasyfikacja |
| **Firma bez suffiksu** | `"Netflix"`, `"Amazon"`, `"Allegro"` | Nie wykryje jako COMPANY |
| **Imię jednoczłonowe** | `"Madonna"`, `"Shakira"` w opisie | Nie wykryje jako PERSON |
| **Tekst z liczbą** | `"Faktura 12345"` | Może wykryć jako AMOUNT |
| **IBAN w opisie** | `"Zwrot za przelew PL123..."` | Wykryje IBAN w złej kolumnie |

### 2. Mieszane typy w jednej kolumnie

Bank CSV często ma kolumnę `Odbiorca/Nadawca` z różnymi typami:

```csv
Odbiorca
"Jan Kowalski"              // PERSON
"NETFLIX SP. Z O.O."        // COMPANY
"ZUS"                       // INSTITUTION (bez suffiksu)
"Urząd Skarbowy Warszawa"   // GOVERNMENT
"BLIK - 123456"             // SYSTEM/CODE
""                          // EMPTY
```

**Problem:** Algorytm zakłada JEDEN typ per kolumna, ale rzeczywistość jest inna.

### 3. Kategorie bankowe - niestandardowe/nowe

```java
// Config ma stałą listę:
Set.of("Przelewy wychodzące", "Przelewy przychodzące", ...)

// Ale bank może mieć:
"Przelew wychodzący krajowy"      // Wariant
"PRZELEW WYCHODZĄCY"              // UPPERCASE
"Przelew - wychodzący"            // Z myślnikiem
"Opłata za kartę Mastercard Gold" // Specyficzna karta
"Prowizja za przewalutowanie"     // Nowa kategoria
```

### 4. Formaty dat - więcej niż jeden w pliku

```csv
Data operacji,Data księgowania
31-12-2025,2025-12-31          // Dwa różne formaty w jednym CSV!
```

### 5. Kwoty - edge cases

```csv
Kwota
"-3 000,50"      // Spacja jako separator tysięcy
"(3000.50)"      // Nawiasy = ujemna (standard amerykański)
"3000,50-"       // Minus na końcu (standard niemiecki)
"PLN 3000.50"    // Waluta przed kwotą
"3.000,50 EUR"   // Separator tysięcy = kropka
```

### 6. Wielojęzyczne opisy w polskim CSV

```csv
// Polski bank, ale transakcje międzynarodowe:
"NETFLIX.COM AMSTERDAM"           // Angielski + holenderski
"AMAZON EU SARL LUXEMBOURG"       // Angielski + francuski
"SPOTIFY AB STOCKHOLM"            // Szwedzki
"Überweisung von Hans Müller"     // Niemiecki (przelew zagraniczny)
```

**Problem:** CountryConfig("PL") nie wykryje `SARL` (francuski suffix) ani `AB` (szwedzki).

### 7. Anonimizacja niszczy kontekst dla AI

```csv
# ORYGINAŁ:
"Jan Kowalski","PL98124014441111001078171074","czynsz za grudzień"
"Jan Kowalski","PL98124014441111001078171074","czynsz za styczeń"

# PO ANONIMIZACJI:
"PERSON_1","IBAN_1","TEXT_1"
"PERSON_1","IBAN_1","TEXT_2"
```

**Problem:** AI nie widzi, że `TEXT_1` i `TEXT_2` to podobne opisy (czynsz).

### 8. Placeholder collision

```csv
# Kolumna: Nadawca
"Bank PKO"           // Nazwa banku
# Kolumna: Opis
"Przelew z Bank PKO" // Opis zawierający nazwę banku
```

**Problem:** Czy anonimizować fragmenty tekstu, czy całe wartości?

### 9. Performance przy dużych plikach

```java
// Dla każdej komórki:
for (String value : allCells) {        // 402 rows × 10 columns = 4020 cells
    isIban(value);      // Regex match
    isCompany(value);   // Set.contains + uppercase
    isPerson(value);    // Regex match
    isAmount(value);    // Regex match
    isDate(value);      // Regex match
}
// = ~20,000 regex operations
```

### 10. DataFaker vs Placeholder

```java
faker.name().fullName()  // "Bogusław Różycki"
// AI może nie rozpoznać jako imię, bo rzadkie
```

vs Placeholder:
```java
"PERSON_1"  // AI wie, że to placeholder
```

### Podsumowanie słabych stron

| Kategoria | Ryzyko | Mitygacja |
|-----------|--------|-----------|
| **Wykrywanie typów** | 🔴 Wysokie | Użyj AI do wykrycia typów |
| **Mieszane typy** | 🔴 Wysokie | Per-cell detection, nie per-column |
| **Kategorie bankowe** | 🟡 Średnie | Fuzzy matching / contains |
| **Formaty dat/kwot** | 🟡 Średnie | Multiple patterns per country |
| **Międzynarodowe opisy** | 🟡 Średnie | Globalna lista suffixów jako fallback |
| **Utrata kontekstu** | 🟡 Średnie | Zachowaj podobieństwo (TEXT_1a, TEXT_1b) |
| **Partial anonymization** | 🟡 Średnie | Tylko całe wartości, nie fragmenty |
| **Performance** | 🟢 Niskie | Lazy evaluation, cache compiled regex |
| **Fake vs Placeholder** | 🟢 Niskie | Użyj placeholderów (prostsze dla AI) |

---

## 11. Alternatywa: AI do wykrywania typów

### Prompt do AI

```
Mam CSV z kolumnami: Data,Data księgowania,Typ,Kwota,Waluta,Odbiorca,Nr konta,Opis

Sample:
31-12-2025,31-12-2025,Przelewy wychodzące,-3000,PLN,Jan Kowalski,PL98124...,czynsz

Które kolumny zawierają: DATE, AMOUNT, IBAN, PERSON, COMPANY, CATEGORY, TEXT?
Odpowiedz JSON: {"0": "DATE", "1": "DATE", "2": "CATEGORY", ...}
```

### Koszt AI call

```
INPUT (~150 tokenów):
- System prompt: 50 tokenów
- Nagłówki CSV: 20 tokenów
- 3 sample rows: 80 tokenów

OUTPUT (~50 tokenów):
{"0": "DATE", "1": "DATE", "2": "CATEGORY", "3": "AMOUNT", ...}

KOSZT:
- GPT-4o-mini: 150 × $0.15/1M + 50 × $0.60/1M = $0.00005
- Claude Haiku: 150 × $1/1M + 50 × $5/1M = $0.0004

≈ $0.0004 per plik (nieistotne)
```

### Zalety AI do wykrywania typów

| Przypadek | Regex | AI |
|-----------|-------|-----|
| Mieszane typy w kolumnie | ❌ Cała kolumna → jeden typ | ✅ "Mix: osoby, firmy, instytucje" |
| Firma bez suffiksu | ❌ Nie wykryje | ✅ "Netflix, Amazon = znane firmy" |
| Kontekst z nagłówka | ❌ Ignoruje | ✅ "Nagłówek 'Nr rachunku' → IBAN" |

---

## 12. Porównanie: Regex vs AI

### Bezpośrednie porównanie

| Aspekt | Regex + CountryConfig | AI (mini call) |
|--------|----------------------|----------------|
| **Koszt** | $0.00 | ~$0.001-0.005 |
| **Latencja** | ~10ms | ~500-1000ms |
| **Dokładność typowa** | ~85-90% | ~95-99% |
| **Edge cases** | ❌ Słabo | ✅ Dobrze |
| **Mieszane typy** | ❌ Nie obsługuje | ✅ Rozumie kontekst |
| **Nowe formaty** | ❌ Wymaga kodu | ✅ Automatycznie |
| **Maintenance** | 🔴 Dużo regexów | 🟢 Zero |
| **Testowanie** | ✅ Łatwe (deterministyczne) | ⚠️ Trudniejsze |
| **Offline** | ✅ Działa | ❌ Wymaga API |

### Kiedy które podejście?

| Scenariusz | Rekomendacja |
|------------|--------------|
| **MVP / szybki start** | Tylko Regex (prostsze) |
| **Produkcja z wieloma bankami** | Hybrid (regex + AI fallback) |
| **Maksymalna dokładność** | AI dla wszystkich kolumn |
| **Offline / air-gapped** | Tylko Regex |
| **Bardzo duży wolumen** | Regex + cache reguł |

---

## 13. Rekomendowane podejście hybrydowe

### Architektura

```
┌─────────────────────────────────────────────────────────────────────┐
│  FLOW: Regex first, AI fallback                                     │
└─────────────────────────────────────────────────────────────────────┘

                     CSV Input
                         │
                         ▼
              ┌─────────────────────┐
              │  Quick Regex Check  │
              │  (pewne typy)       │
              └──────────┬──────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
    ┌─────────┐    ┌──────────┐    ┌──────────┐
    │ IBAN    │    │ DATE     │    │ AMOUNT   │
    │ (pewne) │    │ (pewne)  │    │ (pewne)  │
    └─────────┘    └──────────┘    └──────────┘
         │               │               │
         └───────────────┼───────────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │  Uncertain columns? │
              │  (PERSON vs COMPANY │
              │   vs TEXT)          │
              └──────────┬──────────┘
                         │
              ┌──────────┴──────────┐
              │                     │
              ▼                     ▼
         ALL CERTAIN           SOME UNCERTAIN
              │                     │
              ▼                     ▼
         Use Regex            ┌─────────────┐
         Results              │  AI Call    │
              │               │  (tylko dla │
              │               │  uncertain) │
              │               └──────┬──────┘
              │                      │
              └──────────┬───────────┘
                         │
                         ▼
                   Anonymize
```

### Implementacja

```java
public class HybridTypeDetector {

    private final AiTypeDetector aiDetector;
    private final RegexTypeDetector regexDetector;

    public Map<Integer, DataType> detectTypes(List<String[]> rows, String[] headers) {
        Map<Integer, DataType> result = new HashMap<>();
        List<Integer> uncertainColumns = new ArrayList<>();

        // Krok 1: Pewne typy (regex)
        for (int col = 0; col < headers.length; col++) {
            List<String> values = getColumnValues(rows, col);

            DataType detected = regexDetector.detect(values);

            if (isCertainType(detected)) {
                // Pewne typy - regex wystarczy
                result.put(col, detected);
            } else {
                // Niepewne - PERSON vs COMPANY vs TEXT
                uncertainColumns.add(col);
            }
        }

        // Krok 2: AI tylko dla niepewnych kolumn
        if (!uncertainColumns.isEmpty()) {
            Map<Integer, DataType> aiResults = aiDetector.detectTypes(
                headers,
                getSampleRows(rows, 3),
                uncertainColumns
            );
            result.putAll(aiResults);
        }

        return result;
    }

    private boolean isCertainType(DataType type) {
        return type == DATE || type == AMOUNT ||
               type == CURRENCY || type == IBAN ||
               type == BANK_CATEGORY;
    }
}
```

---

## 14. Plan implementacji

### Fazy

| Faza | Zakres | Złożoność |
|------|--------|-----------|
| **Faza 1** | Tylko regex + CountryConfig | 🟢 Niska |
| **Faza 2** | Dodaj AI fallback dla uncertain | 🟡 Średnia |
| **Faza 3** | Cache AI decisions per bank | 🟡 Średnia |

### Faza 1: MVP (rekomendowane na start)

**Zakres:**
- `CsvAnonymizer` z regex detection
- `CountryConfig` dla PL, DE, GB
- Placeholdery (nie DataFaker)
- Sample size: 10 wierszy

**Pokrycie:** ~85-90% przypadków

**Klasy do utworzenia:**
```
bank_data_adapter/
├── domain/
│   └── anonymization/
│       ├── DataType.java
│       ├── AnonymizationResult.java
│       └── CountryConfig.java
└── infrastructure/
    └── anonymization/
        ├── CsvAnonymizer.java
        ├── RegexTypeDetector.java
        └── PlaceholderGenerator.java
```

### Faza 2: AI Fallback

**Zakres:**
- `AiTypeDetector` dla uncertain columns
- `HybridTypeDetector` łączący oba podejścia
- Prompt engineering dla type detection

**Dodatkowe klasy:**
```
infrastructure/
└── anonymization/
    ├── AiTypeDetector.java
    └── HybridTypeDetector.java
```

### Faza 3: Mapping Rules Cache

**Zakres:**
- `MappingRulesCacheService` (L1 memory + L2 MongoDB)
- `BankOriginCsvMappingRulesDocument` (MongoDB: `bank_origin_csv_mapping_rules`)
- Cache key: `{bankName}_{currency}_{structureHash}`
- Automatic cache invalidation (successRate < 85%)
- Admin API do zarządzania regułami

**Dodatkowe klasy:**
```
infrastructure/
└── cache/
    ├── MappingRulesCacheService.java
    ├── BankOriginCsvMappingRulesDocument.java
    ├── BankOriginCsvMappingRulesRepository.java
    └── MappingRulesHealthChecker.java
```

Szczegóły w sekcji [15. Mapping Rules Cache](#15-mapping-rules-cache---przechowywanie-reguł-mapowania).

---

## 15. Mapping Rules Cache - przechowywanie reguł mapowania

### Problem

Jak przechowywać i ponownie wykorzystywać reguły mapowania kolumn CSV na Canonical CSV, aby nie powtarzać kosztownych wywołań AI dla tego samego banku/formatu?

### Rozwiązanie: MongoDB Collection `bank_origin_csv_mapping_rules`

#### Model dokumentu

```java
@Document(collection = "bank_origin_csv_mapping_rules")
public class BankOriginCsvMappingRulesDocument {

    @Id
    private String id;  // UUID

    // === KLUCZ CACHE (unikalna identyfikacja formatu) ===
    private String cacheKey;           // "NEST_BANK_PLN_a1b2c3d4" (hash struktury)
    private String bankName;           // "Nest Bank"
    private String bankCode;           // "1870" (z IBAN)
    private String country;            // "PL"
    private String currency;           // "PLN"
    private String structureHash;      // SHA-256 z nagłówków CSV

    // === REGUŁY PARSOWANIA ===
    private int headerRowIndex;        // np. 6 dla Nest Bank
    private List<Integer> metadataRows; // [0,1,2,3,4,5]
    private String delimiter;          // "," lub ";"
    private String encoding;           // "UTF-8", "CP1250"

    // === MAPOWANIE KOLUMN → CANONICAL ===
    private List<ColumnMapping> columnMappings;

    // === MAPOWANIE KATEGORII → TYP ===
    private Map<String, TransactionType> categoryToTypeMapping;

    // === WERSJONOWANIE ===
    private int version;               // 1, 2, 3... (inkrementowane przy zmianach)
    private String aiModel;            // "gpt-4o-mini"
    private String aiPromptVersion;    // "v2.1" (do invalidacji)

    // === STATYSTYKI UŻYCIA ===
    private int timesUsedSuccessfully;
    private int timesUsedWithErrors;
    private double successRate;
    private Set<String> usersUsed;     // Którzy userzy używali

    // === AUDIT ===
    private String createdByUserId;
    private ZonedDateTime createdAt;
    private ZonedDateTime lastUsedAt;
    private ZonedDateTime lastModifiedAt;

    // === STATUS ===
    private MappingStatus status;      // ACTIVE, DEPRECATED, FAILED
}

public record ColumnMapping(
    int sourceColumnIndex,
    String sourceColumnName,      // "Kwota", "Data operacji"
    String targetField,           // "amount", "operationDate"
    String transformation,        // "ABS", "NEGATE", "DATE_FORMAT", null
    String inputFormat,           // "dd-MM-yyyy"
    String outputFormat,          // "yyyy-MM-dd"
    String decimalSeparator,      // ","
    String defaultValue           // null
) {}

public enum MappingStatus {
    ACTIVE,      // Używany
    DEPRECATED,  // Stary, ale jeszcze działa (dla starych CSV)
    FAILED       // Zbyt wiele błędów, wymaga regeneracji
}
```

### Generowanie Cache Key

```java
public String generateCacheKey(String csvContent, String bankHint, String currency) {
    // 1. Wykryj bank (z IBAN lub hint)
    String bankName = detectBankName(csvContent, bankHint);

    // 2. Oblicz hash struktury (nagłówki + delimiter)
    String structureHash = calculateStructureHash(csvContent);

    // 3. Złóż klucz
    return String.format("%s_%s_%s",
        bankName.toUpperCase().replace(" ", "_"),
        currency,
        structureHash.substring(0, 8)  // Pierwsze 8 znaków hash
    );
    // Przykład: "NEST_BANK_PLN_a1b2c3d4"
}

private String calculateStructureHash(String csvContent) {
    // Hash z:
    // - Nagłówków kolumn (nazwy, kolejność)
    // - Delimitera
    // - Liczby metadata rows

    String headerRow = findHeaderRow(csvContent);
    String delimiter = detectDelimiter(csvContent);
    int metadataCount = countMetadataRows(csvContent);

    String toHash = headerRow + "|" + delimiter + "|" + metadataCount;
    return DigestUtils.sha256Hex(toHash);
}
```

### Dlaczego Structure Hash?

```
Ten sam bank może mieć RÓŻNE formaty:

NEST BANK (format A - stary):
Data,Kwota,Opis
→ hash: "a1b2c3d4"

NEST BANK (format B - nowy):
Data operacji,Data księgowania,Kwota,Waluta,Tytuł
→ hash: "e5f6g7h8"

= DWA OSOBNE WPISY W CACHE
```

### Flow: Sprawdź cache → użyj lub stwórz

```
┌─────────────────────────────────────────────────────────────────────┐
│  Upload CSV                                                         │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  1. Generuj cacheKey                                                │
│     bankName + currency + structureHash                             │
│     → "NEST_BANK_PLN_a1b2c3d4"                                      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  2. Sprawdź cache (MongoDB)                                         │
│     findByCacheKeyAndStatus(cacheKey, ACTIVE)                       │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
              ┌────────────────┴────────────────┐
              │                                 │
              ▼                                 ▼
         CACHE HIT                         CACHE MISS
              │                                 │
              ▼                                 ▼
┌─────────────────────────┐      ┌─────────────────────────────────┐
│ 3a. Użyj cached rules   │      │ 3b. Anonimizuj + AI call        │
│     → LocalTransformer  │      │     → Otrzymaj nowe reguły      │
│     → ~50ms             │      │     → Zapisz do cache           │
│     → $0.00             │      │     → ~3s, ~$0.01               │
└───────────┬─────────────┘      └───────────────┬─────────────────┘
            │                                    │
            └────────────────┬───────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│  4. Transformuj pełny CSV lokalnie                                  │
│     → Canonical CSV (400 wierszy)                                   │
└─────────────────────────────────────────────────────────────────────┘
```

### Service do zarządzania cache

```java
@Service
@RequiredArgsConstructor
public class MappingRulesCacheService {

    private final BankOriginCsvMappingRulesRepository repository;
    private final AiBankCsvTransformService aiService;

    // L1 cache (in-memory) dla szybkiego dostępu
    private final Map<String, BankOriginCsvMappingRulesDocument> l1Cache =
        new ConcurrentHashMap<>();

    public Optional<BankOriginCsvMappingRulesDocument> findRules(String cacheKey) {
        // 1. L1 cache (memory)
        var cached = l1Cache.get(cacheKey);
        if (cached != null && cached.getStatus() == ACTIVE) {
            return Optional.of(cached);
        }

        // 2. L2 cache (MongoDB)
        return repository.findByCacheKeyAndStatus(cacheKey, ACTIVE)
            .map(rules -> {
                l1Cache.put(cacheKey, rules);  // Populate L1
                return rules;
            });
    }

    public BankOriginCsvMappingRulesDocument createOrUpdateRules(
            String cacheKey,
            String csvSample,
            String userId) {

        // 1. Wywołaj AI dla nowych reguł
        MappingRules newRules = aiService.detectMappingRules(csvSample);

        // 2. Sprawdź czy istnieją stare reguły
        Optional<BankOriginCsvMappingRulesDocument> existing =
            repository.findByCacheKey(cacheKey);

        if (existing.isPresent()) {
            // 3a. Deprecate stare, dodaj nowe z version++
            var old = existing.get();
            old.setStatus(DEPRECATED);
            repository.save(old);

            return saveNewRules(cacheKey, newRules, old.getVersion() + 1, userId);
        } else {
            // 3b. Pierwszy wpis dla tego formatu
            return saveNewRules(cacheKey, newRules, 1, userId);
        }
    }

    public void recordUsage(String cacheKey, boolean success) {
        repository.findByCacheKey(cacheKey).ifPresent(rules -> {
            if (success) {
                rules.setTimesUsedSuccessfully(rules.getTimesUsedSuccessfully() + 1);
            } else {
                rules.setTimesUsedWithErrors(rules.getTimesUsedWithErrors() + 1);
            }
            rules.setLastUsedAt(ZonedDateTime.now());
            rules.setSuccessRate(calculateSuccessRate(rules));
            repository.save(rules);
            l1Cache.put(cacheKey, rules);
        });
    }
}
```

### Co gdy bank zmieni format CSV?

**Automatyczne wykrycie przez Structure Hash:**

```
Timeline:
─────────────────────────────────────────────────────────────────────
Styczeń 2026:   Bank używa formatu A
                → User eksportuje CSV (format A)
                → Cache: NEST_BANK_PLN_a1b2c3d4 (ACTIVE)

Luty 2026:      Bank zmienia na format B
                → Inny user eksportuje CSV (format B)
                → Cache: NEST_BANK_PLN_e5f6g7h8 (ACTIVE) ← NOWY!
                → Stary: NEST_BANK_PLN_a1b2c3d4 (nadal ACTIVE!)

Marzec 2026:    Pierwszy user wgrywa swój stary CSV (format A)
                → structureHash = "a1b2c3d4"
                → CACHE HIT! Stare reguły nadal działają ✓
```

**Wniosek:** Zmiana formatu = nowy hash = automatycznie nowy wpis w cache. Stary format nadal działa dla starych plików.

### Przykład danych w MongoDB

```javascript
// Kolekcja: bank_origin_csv_mapping_rules

// Wpis 1: Stary format (nadal działa dla starych plików)
{
  "_id": "uuid-1",
  "cacheKey": "NEST_BANK_PLN_a1b2c3d4",
  "bankName": "Nest Bank",
  "bankCode": "1870",
  "country": "PL",
  "currency": "PLN",
  "structureHash": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
  "version": 1,
  "status": "ACTIVE",
  "headerRowIndex": 6,
  "delimiter": ",",
  "columnMappings": [
    {"sourceColumnIndex": 0, "sourceColumnName": "Data", "targetField": "operationDate",
     "inputFormat": "dd-MM-yyyy", "outputFormat": "yyyy-MM-dd"},
    {"sourceColumnIndex": 1, "sourceColumnName": "Kwota", "targetField": "amount",
     "transformation": "ABS", "decimalSeparator": ","},
    {"sourceColumnIndex": 2, "sourceColumnName": "Opis", "targetField": "description"}
  ],
  "categoryToTypeMapping": {
    "Przelewy wychodzące": "OUTFLOW",
    "Przelewy przychodzące": "INFLOW",
    "Opłaty i prowizje": "OUTFLOW"
  },
  "aiModel": "gpt-4o-mini",
  "aiPromptVersion": "v2.1",
  "timesUsedSuccessfully": 47,
  "timesUsedWithErrors": 2,
  "successRate": 0.959,
  "usersUsed": ["U10000001", "U10000015", "U10000023"],
  "createdByUserId": "U10000001",
  "createdAt": "2026-01-15T10:00:00Z",
  "lastUsedAt": "2026-03-20T15:30:00Z",
  "lastModifiedAt": "2026-01-15T10:00:00Z"
}

// Wpis 2: Nowy format (dla nowych plików)
{
  "_id": "uuid-2",
  "cacheKey": "NEST_BANK_PLN_e5f6g7h8",
  "bankName": "Nest Bank",
  "bankCode": "1870",
  "country": "PL",
  "currency": "PLN",
  "structureHash": "e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0",
  "version": 1,
  "status": "ACTIVE",
  "headerRowIndex": 0,
  "delimiter": ";",
  "columnMappings": [
    {"sourceColumnIndex": 0, "sourceColumnName": "Data operacji", "targetField": "operationDate",
     "inputFormat": "yyyy-MM-dd", "outputFormat": "yyyy-MM-dd"},
    {"sourceColumnIndex": 1, "sourceColumnName": "Data księgowania", "targetField": "bookingDate",
     "inputFormat": "yyyy-MM-dd", "outputFormat": "yyyy-MM-dd"},
    {"sourceColumnIndex": 2, "sourceColumnName": "Kwota", "targetField": "amount",
     "transformation": "ABS", "decimalSeparator": ","},
    {"sourceColumnIndex": 3, "sourceColumnName": "Waluta", "targetField": "currency"},
    {"sourceColumnIndex": 4, "sourceColumnName": "Tytuł", "targetField": "description"}
  ],
  "categoryToTypeMapping": {
    "Przelew wychodzący": "OUTFLOW",
    "Przelew przychodzący": "INFLOW"
  },
  "aiModel": "gpt-4o-mini",
  "aiPromptVersion": "v2.1",
  "timesUsedSuccessfully": 23,
  "timesUsedWithErrors": 0,
  "successRate": 1.0,
  "usersUsed": ["U10000042", "U10000056"],
  "createdByUserId": "U10000042",
  "createdAt": "2026-02-20T10:00:00Z",
  "lastUsedAt": "2026-03-20T16:45:00Z",
  "lastModifiedAt": "2026-02-20T10:00:00Z"
}
```

### Automatyczna invalidacja reguł

```java
@Service
public class MappingRulesHealthChecker {

    private static final double MIN_SUCCESS_RATE = 0.85;  // 85%
    private static final int MIN_USES_FOR_EVALUATION = 10;

    @Scheduled(cron = "0 0 * * * *")  // Co godzinę
    public void checkRulesHealth() {
        List<BankOriginCsvMappingRulesDocument> activeRules =
            repository.findByStatus(ACTIVE);

        for (var rules : activeRules) {
            int totalUses = rules.getTimesUsedSuccessfully() + rules.getTimesUsedWithErrors();

            if (totalUses >= MIN_USES_FOR_EVALUATION) {
                if (rules.getSuccessRate() < MIN_SUCCESS_RATE) {
                    // Zbyt wiele błędów → oznacz jako FAILED
                    rules.setStatus(FAILED);
                    repository.save(rules);

                    log.warn("Mapping rules {} marked as FAILED. Success rate: {}%",
                        rules.getCacheKey(), rules.getSuccessRate() * 100);

                    // Alert do administratora
                    alertService.sendMappingRulesFailedAlert(rules);
                }
            }
        }
    }
}
```

### Admin API do zarządzania regułami

```java
@RestController
@RequestMapping("/api/v1/admin/mapping-rules")
@PreAuthorize("hasRole('ADMIN')")
public class MappingRulesAdminController {

    @GetMapping
    public List<BankOriginCsvMappingRulesDocument> listAllRules(
            @RequestParam(required = false) MappingStatus status) {
        if (status != null) {
            return repository.findByStatus(status);
        }
        return repository.findAll();
    }

    @PostMapping("/{cacheKey}/regenerate")
    public ResponseEntity<BankOriginCsvMappingRulesDocument> regenerateRules(
            @PathVariable String cacheKey,
            @RequestBody RegenerateRequest request) {

        // 1. Pobierz sample CSV (z ostatniej transformacji lub request)
        String sampleCsv = request.sampleCsv();

        // 2. Wymuś nowy AI call
        var newRules = cacheService.forceRegenerateRules(
            cacheKey, sampleCsv, getCurrentUserId());

        return ResponseEntity.ok(newRules);
    }

    @PostMapping("/{cacheKey}/deprecate")
    public ResponseEntity<Void> deprecateRules(@PathVariable String cacheKey) {
        cacheService.deprecateRules(cacheKey);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{cacheKey}")
    public ResponseEntity<Void> deleteRules(@PathVariable String cacheKey) {
        repository.deleteByCacheKey(cacheKey);
        l1Cache.remove(cacheKey);
        return ResponseEntity.noContent().build();
    }
}
```

### Architektura cache

```
┌─────────────────────────────────────────────────────────────────────┐
│                    MAPPING RULES CACHE ARCHITECTURE                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   L1 CACHE      │     │   L2 CACHE      │     │   AI SERVICE    │
│   (Memory)      │     │   (MongoDB)     │     │   (Claude/GPT)  │
│                 │     │                 │     │                 │
│  ConcurrentMap  │────▶│  bank_origin_   │────▶│  Generuj nowe   │
│  <cacheKey,     │     │  csv_mapping_   │     │  reguły         │
│   rules>        │     │  rules          │     │                 │
│                 │     │                 │     │  Koszt: ~$0.01  │
│  TTL: 1h        │     │  Indexes:       │     │  Czas: ~3s      │
│  Size: 100      │     │  - cacheKey     │     │                 │
└─────────────────┘     │  - status       │     └─────────────────┘
                        │  - bankName     │
                        └─────────────────┘

CACHE KEY FORMAT:
{BANK_NAME}_{CURRENCY}_{STRUCTURE_HASH_8}

PRZYKŁADY:
- NEST_BANK_PLN_a1b2c3d4
- MBANK_PLN_e5f6g7h8
- DEUTSCHE_BANK_EUR_i9j0k1l2
- BARCLAYS_GBP_m3n4o5p6

LIFECYCLE:
1. ACTIVE     → Używany, działa poprawnie
2. DEPRECATED → Stary format, ale nadal działa (dla starych CSV)
3. FAILED     → Zbyt wiele błędów (successRate < 85%), wymaga regeneracji
```

### Podsumowanie Mapping Rules Cache

| Pytanie | Odpowiedź |
|---------|-----------|
| **Gdzie zapisywać?** | MongoDB: `bank_origin_csv_mapping_rules` + L1 memory cache |
| **Jak identyfikować format?** | `cacheKey = bank + currency + structureHash` |
| **Co gdy bank zmieni format?** | Nowy hash = nowy wpis (stary nadal działa dla starych CSV) |
| **Co gdy user ma stary CSV?** | Hash = stary = stare reguły nadal działają |
| **Kiedy regenerować?** | Auto: successRate < 85%, Manual: admin endpoint |
| **Kto może zarządzać?** | Admin przez `/api/v1/admin/mapping-rules` |

---

## 16. Zabezpieczenia i walidacja reguł

### 16.1 Walidacja reguł przed zapisem (Luka #2)

**Problem:** AI może zwrócić niepoprawne reguły (brakujące kolumny, złe formaty).

**Rozwiązanie:** Walidacja + dry-run przed zapisem do cache.

```java
public BankOriginCsvMappingRulesDocument createNewRules(
        String cacheKey,
        String csvSample,
        String userId) {

    MappingRules newRules = aiService.detectMappingRules(csvSample);

    // WALIDACJA przed zapisem
    ValidationResult validation = validateRules(newRules, csvSample);

    if (!validation.isValid()) {
        log.error("AI returned invalid rules for {}: {}", cacheKey, validation.errors());
        throw new InvalidMappingRulesException(validation.errors());
    }

    // Test na sample przed zapisem (dry-run)
    try {
        transformWithRules(csvSample, newRules);
    } catch (Exception e) {
        log.error("Rules failed dry-run for {}", cacheKey, e);
        throw new MappingRulesTestFailedException(e);
    }

    return saveNewRules(cacheKey, newRules, 1, userId);
}
```

#### Walidator reguł

```java
@Component
public class MappingRulesValidator {

    // Wymagane pola w Canonical CSV
    private static final Set<String> REQUIRED_FIELDS = Set.of(
        "operationDate", "amount", "type", "description"
    );

    public ValidationResult validate(MappingRules rules, String csvSample) {
        List<String> errors = new ArrayList<>();

        // 1. Sprawdź wymagane pola
        Set<String> mappedFields = rules.columnMappings().stream()
            .map(ColumnMapping::targetField)
            .collect(toSet());

        for (String required : REQUIRED_FIELDS) {
            if (!mappedFields.contains(required)) {
                errors.add("Missing required mapping for: " + required);
            }
        }

        // 2. Sprawdź indeksy kolumn
        int maxColumnIndex = countColumns(csvSample);
        for (var mapping : rules.columnMappings()) {
            if (mapping.sourceColumnIndex() >= maxColumnIndex) {
                errors.add("Column index " + mapping.sourceColumnIndex() +
                           " out of bounds (max: " + (maxColumnIndex - 1) + ")");
            }
        }

        // 3. Sprawdź formaty dat
        for (var mapping : rules.columnMappings()) {
            if ("operationDate".equals(mapping.targetField()) ||
                "bookingDate".equals(mapping.targetField())) {
                if (mapping.inputFormat() == null) {
                    errors.add("Date field " + mapping.targetField() +
                               " requires inputFormat");
                }
            }
        }

        // 4. Sprawdź categoryToTypeMapping
        if (rules.categoryToTypeMapping() == null ||
            rules.categoryToTypeMapping().isEmpty()) {
            errors.add("categoryToTypeMapping cannot be empty");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private int countColumns(String csvSample) {
        String headerRow = findHeaderRow(csvSample);
        String delimiter = detectDelimiter(csvSample);
        return headerRow.split(Pattern.quote(delimiter)).length;
    }
}

public record ValidationResult(
    boolean valid,
    List<String> errors
) {
    public boolean isValid() {
        return valid;
    }
}
```

### 16.2 Ulepszone generowanie Structure Hash (Luka #3)

**Problem:** Dwa różne banki mogą mieć identyczne nagłówki, co prowadzi do hash collision.

**Rozwiązanie:** Uwzględnij więcej danych w hash.

```java
@Component
public class StructureHashCalculator {

    /**
     * Generuje unikalny hash struktury CSV.
     * Uwzględnia: nagłówki, delimiter, metadata count, sample danych, bank hint.
     */
    public String calculateStructureHash(String csvContent, String bankHint) {
        String headerRow = findHeaderRow(csvContent);
        String delimiter = detectDelimiter(csvContent);
        int metadataCount = countMetadataRows(csvContent);
        String firstDataRow = getFirstDataRow(csvContent);

        // Złóż wszystkie elementy do hash
        String toHash = String.join("|",
            normalizeHeader(headerRow),
            delimiter,
            String.valueOf(metadataCount),
            extractDataPattern(firstDataRow),  // Wzorzec danych (nie wartości!)
            bankHint != null ? bankHint.toUpperCase() : ""
        );

        return DigestUtils.sha256Hex(toHash).substring(0, 16);  // 16 znaków
    }

    /**
     * Normalizuje nagłówek (lowercase, trim, sort).
     */
    private String normalizeHeader(String headerRow) {
        return Arrays.stream(headerRow.split("[,;]"))
            .map(String::trim)
            .map(String::toLowerCase)
            .sorted()
            .collect(Collectors.joining(","));
    }

    /**
     * Wyciąga wzorzec danych (typy, nie wartości).
     * Przykład: "31-12-2025,-3000.50,PLN,Jan Kowalski"
     *        → "DATE,AMOUNT,CURRENCY,TEXT"
     */
    private String extractDataPattern(String dataRow) {
        return Arrays.stream(dataRow.split("[,;]"))
            .map(this::detectValueType)
            .collect(Collectors.joining(","));
    }

    private String detectValueType(String value) {
        value = value.trim();
        if (value.matches("\\d{2}[-./]\\d{2}[-./]\\d{4}|\\d{4}[-./]\\d{2}[-./]\\d{2}")) {
            return "DATE";
        }
        if (value.matches("-?\\d+[.,]?\\d*")) {
            return "AMOUNT";
        }
        if (value.matches("[A-Z]{3}")) {
            return "CURRENCY";
        }
        if (value.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) {
            return "IBAN";
        }
        return "TEXT";
    }
}
```

#### Przykład hash dla różnych banków

```
Bank A (Nest):
- Header: "Data,Kwota,Opis"
- Delimiter: ","
- Metadata: 6
- Pattern: "DATE,AMOUNT,TEXT"
- BankHint: "NEST"
→ Hash: "a1b2c3d4e5f6g7h8"

Bank B (mBank) - te same nagłówki, ale inne dane:
- Header: "Data,Kwota,Opis"
- Delimiter: ";"           ← RÓŻNICA
- Metadata: 0              ← RÓŻNICA
- Pattern: "DATE,AMOUNT,TEXT"
- BankHint: "MBANK"        ← RÓŻNICA
→ Hash: "x9y8z7w6v5u4t3s2"  ← RÓŻNY HASH!
```

### 16.3 Wersjonowanie AI Promptu (Luka #5)

**Problem:** Zmiana promptu AI może spowodować niekompatybilność starych reguł.

**Rozwiązanie:** Semantic versioning promptu + auto-deprecation.

```java
@Component
@RequiredArgsConstructor
public class PromptVersionManager {

    @Value("${ai.prompt.version}")
    private String currentPromptVersion;  // np. "v2.1"

    private final BankOriginCsvMappingRulesRepository repository;

    /**
     * Sprawdza czy reguły są kompatybilne z obecnym promptem.
     * Semantic versioning: major.minor
     * - Major change (v1.x → v2.x) = niekompatybilne
     * - Minor change (v2.1 → v2.2) = kompatybilne
     */
    public boolean isCompatible(String rulesPromptVersion) {
        if (rulesPromptVersion == null) {
            return false;  // Stare reguły bez wersji
        }

        String rulesMajor = extractMajorVersion(rulesPromptVersion);
        String currentMajor = extractMajorVersion(currentPromptVersion);

        return rulesMajor.equals(currentMajor);
    }

    private String extractMajorVersion(String version) {
        // "v2.1" → "2"
        return version.replaceAll("[^0-9.]", "").split("\\.")[0];
    }

    /**
     * Znajduje tylko kompatybilne reguły.
     */
    public Optional<BankOriginCsvMappingRulesDocument> findCompatibleRules(String cacheKey) {
        return repository.findByCacheKeyAndStatus(cacheKey, ACTIVE)
            .filter(rules -> isCompatible(rules.getAiPromptVersion()));
    }

    /**
     * Deprecuje niekompatybilne reguły przy starcie aplikacji.
     * Uruchamiane automatycznie po upgrade promptu.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void deprecateIncompatibleRulesOnStartup() {
        String currentMajor = extractMajorVersion(currentPromptVersion);

        List<BankOriginCsvMappingRulesDocument> incompatibleRules =
            repository.findByStatus(ACTIVE).stream()
                .filter(rules -> !isCompatible(rules.getAiPromptVersion()))
                .toList();

        if (!incompatibleRules.isEmpty()) {
            log.info("Found {} rules incompatible with prompt version {}. Deprecating...",
                incompatibleRules.size(), currentPromptVersion);

            for (var rules : incompatibleRules) {
                rules.setStatus(DEPRECATED);
                rules.setLastModifiedAt(ZonedDateTime.now());
                repository.save(rules);

                log.info("Deprecated rules {} (prompt version: {} → current: {})",
                    rules.getCacheKey(),
                    rules.getAiPromptVersion(),
                    currentPromptVersion);
            }
        }
    }
}
```

#### Konfiguracja

```yaml
# application.yml
ai:
  prompt:
    version: "v2.1"  # Zmień na v3.0 przy breaking changes
```

#### Schemat wersjonowania

| Zmiana | Stara wersja | Nowa wersja | Kompatybilność |
|--------|--------------|-------------|----------------|
| Poprawka literówki | v2.1 | v2.2 | ✅ Kompatybilne |
| Dodanie opcjonalnego pola | v2.2 | v2.3 | ✅ Kompatybilne |
| Zmiana formatu output | v2.3 | v3.0 | ❌ **Niekompatybilne** |
| Zmiana wymaganych pól | v3.0 | v4.0 | ❌ **Niekompatybilne** |

### 16.4 Podsumowanie zabezpieczeń

| Luka | Rozwiązanie | Komponent |
|------|-------------|-----------|
| **#2 Brak walidacji** | Validate + dry-run przed zapisem | `MappingRulesValidator` |
| **#3 Hash collision** | Więcej danych w hash (delimiter, metadata, pattern, bankHint) | `StructureHashCalculator` |
| **#5 Wersjonowanie promptu** | Semantic versioning + auto-deprecate | `PromptVersionManager` |

---

## 17. Znane luki do rozwiązania (TODO)

Poniższe luki zostały zidentyfikowane podczas analizy designu. Do rozwiązania w kolejnych iteracjach.

### 17.1 Race condition przy tworzeniu reguł (Luka #1)

**Problem:**
Dwóch użytkowników wgrywa CSV tego samego banku w tym samym czasie. Obaj nie znajdują reguł w cache, obaj wywołują AI, obaj próbują zapisać.

**Scenariusz:**
```
User A: findRules(NEST_BANK_PLN_abc) → MISS
User B: findRules(NEST_BANK_PLN_abc) → MISS
User A: callAI() → rules_A
User B: callAI() → rules_B
User A: saveRules(rules_A) → OK
User B: saveRules(rules_B) → OVERWRITE rules_A!
```

**Proponowane rozwiązanie:**
```java
@Service
public class MappingRulesCacheService {

    private final RedisLockRegistry lockRegistry;  // Lub MongoDBLock

    public BankOriginCsvMappingRulesDocument getOrCreateRules(
            String cacheKey, String csvSample, String userId) {

        // 1. Najpierw próbuj znaleźć bez locka (fast path)
        Optional<BankOriginCsvMappingRulesDocument> cached = findRules(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 2. Lock na poziomie cacheKey
        Lock lock = lockRegistry.obtain(cacheKey);
        boolean acquired = lock.tryLock(30, TimeUnit.SECONDS);

        if (!acquired) {
            throw new CacheLockTimeoutException(cacheKey);
        }

        try {
            // 3. Double-check po uzyskaniu locka
            cached = findRules(cacheKey);
            if (cached.isPresent()) {
                return cached.get();  // Ktoś już utworzył
            }

            // 4. Teraz bezpiecznie tworzymy
            return createOrUpdateRules(cacheKey, csvSample, userId);
        } finally {
            lock.unlock();
        }
    }
}
```

**Alternatywa bez distributed lock:**
MongoDB unique index + upsert:
```java
@CompoundIndex(name = "unique_cache_key", def = "{'cacheKey': 1, 'status': 1}", unique = true)
```

**Status:** TODO - do implementacji gdy będzie więcej niż 1 instancja aplikacji.

---

### 17.2 Brak obsługi częściowego sukcesu (Luka #4)

**Problem:**
AI zwraca reguły, dry-run przechodzi dla sample (10 wierszy), ale przy pełnej transformacji (400 wierszy) część wierszy failuje. Czy traktować jako sukces czy błąd?

**Przykład:**
```
Wiersze 1-350: OK
Wiersze 351-400: Parse error (nowy format daty w ostatnich transakcjach)

Co robić?
- Zapisać 350 poprawnych?
- Odrzucić wszystkie?
- Oznaczyć w UI?
```

**Proponowane rozwiązanie:**

```java
public record TransformationResult(
    List<CanonicalCsvRow> successfulRows,
    List<FailedRow> failedRows,
    double successRate,
    TransformationStatus status
) {
    public boolean isFullSuccess() {
        return failedRows.isEmpty();
    }

    public boolean isPartialSuccess() {
        return !failedRows.isEmpty() && successRate >= 0.90;  // 90%+
    }

    public boolean isFailed() {
        return successRate < 0.90;
    }
}

public record FailedRow(
    int rowNumber,
    String originalContent,
    String errorMessage,
    String failedColumn
) {}

public enum TransformationStatus {
    FULL_SUCCESS,    // 100% wierszy OK
    PARTIAL_SUCCESS, // 90-99% wierszy OK
    FAILED           // <90% wierszy OK
}
```

**Strategia:**
| SuccessRate | Akcja |
|-------------|-------|
| 100% | Zapisz wszystko, rules.successRate++ |
| 90-99% | Zapisz udane, pokaż warning w UI, rules.successRate ± |
| <90% | Odrzuć wszystko, rules.errorRate++, suggest manual |

**Status:** TODO - wymaga zmian w UI i logice importu.

---

### 17.3 L1 Cache nie synchronizowany między instancjami (Luka #6)

**Problem:**
Przy wielu instancjach aplikacji, L1 cache (ConcurrentHashMap) jest per-JVM. Admin deprecuje regułę w instancji A, instancja B nadal używa starej z L1.

**Scenariusz:**
```
Instance A: l1Cache = {NEST_BANK: rules_v1}
Instance B: l1Cache = {NEST_BANK: rules_v1}

Admin via A: deprecateRules(NEST_BANK)
- Instance A: MongoDB DEPRECATED, l1Cache.remove()
- Instance B: nadal ma rules_v1 w l1Cache!  ← BUG

User via B: getOrCreateRules(NEST_BANK)
- Returns stale rules_v1 from l1Cache
```

**Proponowane rozwiązania:**

**A) TTL na L1 cache (proste):**
```java
private final Cache<String, BankOriginCsvMappingRulesDocument> l1Cache =
    Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)  // Max 5 min stale
        .maximumSize(100)
        .build();
```

**B) Redis Pub/Sub (dla real-time):**
```java
@Component
public class CacheInvalidationListener {

    @Autowired
    private RedisMessageListenerContainer container;

    @PostConstruct
    public void subscribe() {
        container.addMessageListener(
            (message, pattern) -> {
                String cacheKey = new String(message.getBody());
                l1Cache.invalidate(cacheKey);
                log.info("Invalidated L1 cache for: {}", cacheKey);
            },
            new PatternTopic("cache:invalidate:mapping-rules:*")
        );
    }
}

// Przy deprecacji:
public void deprecateRules(String cacheKey) {
    repository.deprecate(cacheKey);
    l1Cache.invalidate(cacheKey);
    redisTemplate.convertAndSend("cache:invalidate:mapping-rules:" + cacheKey, cacheKey);
}
```

**C) Hybrid: TTL + invalidate on read:**
```java
public Optional<BankOriginCsvMappingRulesDocument> findRules(String cacheKey) {
    var cached = l1Cache.getIfPresent(cacheKey);

    if (cached != null) {
        // Verify status in DB every N minutes or every time
        if (cached.getStatus() == DEPRECATED) {
            l1Cache.invalidate(cacheKey);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    // L2 lookup...
}
```

**Rekomendacja:** Zacznij od TTL (5 min), dodaj Redis Pub/Sub gdy pojawi się problem.

**Status:** TODO - aktualnie single instance, nie krytyczne.

---

### 17.4 Brak limitu wersji per bank (Luka #7)

**Problem:**
Bank często zmienia format → wiele wersji w cache → unbounded growth.

**Przykład:**
```
Rok 2026:
- NEST_BANK_PLN_abc_v1 (styczen)
- NEST_BANK_PLN_def_v1 (luty)
- NEST_BANK_PLN_ghi_v1 (marzec)
- ...
- NEST_BANK_PLN_xyz_v1 (grudzień)

= 12 wersji per bank per rok × 50 banków = 600 wpisów/rok
```

**Proponowane rozwiązanie:**

```java
@Service
public class MappingRulesCleanupService {

    private static final int MAX_VERSIONS_PER_BANK = 5;
    private static final int MAX_DEPRECATED_AGE_DAYS = 90;

    @Scheduled(cron = "0 0 3 * * *")  // Codziennie o 3:00
    public void cleanupOldRules() {
        // 1. Usuń DEPRECATED starsze niż 90 dni
        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(MAX_DEPRECATED_AGE_DAYS);
        int deleted = repository.deleteByStatusAndLastUsedAtBefore(DEPRECATED, cutoff);
        log.info("Deleted {} deprecated rules older than {} days", deleted, MAX_DEPRECATED_AGE_DAYS);

        // 2. Ogranicz liczbę ACTIVE per bank+currency
        Map<String, List<BankOriginCsvMappingRulesDocument>> byBank =
            repository.findByStatus(ACTIVE).stream()
                .collect(groupingBy(r -> r.getBankName() + "_" + r.getCurrency()));

        for (var entry : byBank.entrySet()) {
            List<BankOriginCsvMappingRulesDocument> rules = entry.getValue();

            if (rules.size() > MAX_VERSIONS_PER_BANK) {
                // Sortuj po lastUsedAt, zachowaj najnowsze
                rules.sort(comparing(BankOriginCsvMappingRulesDocument::getLastUsedAt).reversed());

                List<BankOriginCsvMappingRulesDocument> toDeprecate =
                    rules.subList(MAX_VERSIONS_PER_BANK, rules.size());

                for (var rule : toDeprecate) {
                    rule.setStatus(DEPRECATED);
                    repository.save(rule);
                    log.info("Auto-deprecated old rule: {} (last used: {})",
                        rule.getCacheKey(), rule.getLastUsedAt());
                }
            }
        }
    }
}
```

**Konfiguracja:**
```yaml
mapping-rules:
  cleanup:
    max-versions-per-bank: 5
    max-deprecated-age-days: 90
    cron: "0 0 3 * * *"
```

**Status:** TODO - nie krytyczne na start, dodać przy >100 wpisach w cache.

---

### 17.5 categoryToTypeMapping nie obsługuje nowych kategorii (Luka #8)

**Problem:**
Bank dodaje nową kategorię (np. "Płatność BLIK"), której nie ma w zapisanych regułach.

**Scenariusz:**
```
Cached rules:
categoryToTypeMapping = {
  "Przelewy wychodzące": OUTFLOW,
  "Przelewy przychodzące": INFLOW
}

Nowy CSV zawiera:
"Płatność BLIK"  ← NIE MA W MAPPING!

Co robić?
```

**Proponowane rozwiązanie:**

```java
public TransactionType resolveType(String bankCategory, Map<String, TransactionType> mapping) {
    // 1. Exact match
    if (mapping.containsKey(bankCategory)) {
        return mapping.get(bankCategory);
    }

    // 2. Case-insensitive match
    for (var entry : mapping.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(bankCategory)) {
            return entry.getValue();
        }
    }

    // 3. Partial/fuzzy match
    for (var entry : mapping.entrySet()) {
        if (bankCategory.toLowerCase().contains(entry.getKey().toLowerCase()) ||
            entry.getKey().toLowerCase().contains(bankCategory.toLowerCase())) {
            return entry.getValue();
        }
    }

    // 4. Keyword-based fallback
    String lower = bankCategory.toLowerCase();
    if (containsAny(lower, "wychodzące", "wypłata", "płatność", "opłata", "przelew na")) {
        return OUTFLOW;
    }
    if (containsAny(lower, "przychodzące", "wpłata", "zwrot", "przelew od")) {
        return INFLOW;
    }

    // 5. Default + log warning
    log.warn("Unknown bank category '{}', defaulting to OUTFLOW", bankCategory);
    return OUTFLOW;
}

private boolean containsAny(String text, String... keywords) {
    return Arrays.stream(keywords).anyMatch(text::contains);
}
```

**Dodatkowa strategia:**
Przy nieznanej kategorii:
1. Log warning z pełnym kontekstem
2. Użyj fallback
3. Zwiększ `unknownCategoriesCount` w rules
4. Przy >10% unknown → alert do admina

```java
if (unknownCategoryRate > 0.10) {
    alertService.sendAlert(
        "High unknown category rate for " + cacheKey + ": " + unknownCategoryRate);
    rules.setStatus(NEEDS_UPDATE);
}
```

**Status:** TODO - dodać przy pierwszym realnym przypadku.

---

### 17.6 Brak audit trail dla zmian reguł (Luka #9)

**Problem:**
Brak historii kto i kiedy zmienił reguły. Przy problemach trudno debugować.

**Proponowane rozwiązanie:**

**A) Osobna kolekcja audit:**

```java
@Document(collection = "mapping_rules_audit")
public class MappingRulesAuditDocument {

    @Id
    private String id;

    private String rulesId;           // ID dokumentu reguł
    private String cacheKey;
    private String action;            // CREATED, UPDATED, DEPRECATED, DELETED, REGENERATED
    private String performedByUserId; // User lub "SYSTEM"
    private String performedByRole;   // ADMIN, USER, SCHEDULER
    private ZonedDateTime performedAt;

    private String previousStatus;
    private String newStatus;
    private Integer previousVersion;
    private Integer newVersion;

    private String reason;            // "Low success rate", "Prompt version upgrade", etc.
    private Map<String, Object> metadata;  // Dodatkowe info
}
```

**B) Serwis audit:**

```java
@Service
@RequiredArgsConstructor
public class MappingRulesAuditService {

    private final MappingRulesAuditRepository auditRepository;

    public void logCreation(BankOriginCsvMappingRulesDocument rules, String userId) {
        save(MappingRulesAuditDocument.builder()
            .rulesId(rules.getId())
            .cacheKey(rules.getCacheKey())
            .action("CREATED")
            .performedByUserId(userId)
            .performedAt(ZonedDateTime.now())
            .newStatus(rules.getStatus().name())
            .newVersion(rules.getVersion())
            .build());
    }

    public void logDeprecation(BankOriginCsvMappingRulesDocument rules,
                               String userId, String reason) {
        save(MappingRulesAuditDocument.builder()
            .rulesId(rules.getId())
            .cacheKey(rules.getCacheKey())
            .action("DEPRECATED")
            .performedByUserId(userId)
            .performedAt(ZonedDateTime.now())
            .previousStatus("ACTIVE")
            .newStatus("DEPRECATED")
            .reason(reason)
            .build());
    }

    public void logAutoDeprecation(BankOriginCsvMappingRulesDocument rules, String reason) {
        save(MappingRulesAuditDocument.builder()
            .rulesId(rules.getId())
            .cacheKey(rules.getCacheKey())
            .action("AUTO_DEPRECATED")
            .performedByUserId("SYSTEM")
            .performedByRole("SCHEDULER")
            .performedAt(ZonedDateTime.now())
            .previousStatus("ACTIVE")
            .newStatus("DEPRECATED")
            .reason(reason)
            .build());
    }

    // API do przeglądania
    public List<MappingRulesAuditDocument> getAuditHistory(String cacheKey) {
        return auditRepository.findByCacheKeyOrderByPerformedAtDesc(cacheKey);
    }
}
```

**C) Admin endpoint:**

```java
@GetMapping("/{cacheKey}/audit")
public List<MappingRulesAuditDocument> getAuditHistory(@PathVariable String cacheKey) {
    return auditService.getAuditHistory(cacheKey);
}
```

**Przykład wpisu audit:**
```json
{
  "id": "audit-001",
  "rulesId": "rules-123",
  "cacheKey": "NEST_BANK_PLN_a1b2c3d4",
  "action": "AUTO_DEPRECATED",
  "performedByUserId": "SYSTEM",
  "performedByRole": "SCHEDULER",
  "performedAt": "2026-03-20T03:00:00Z",
  "previousStatus": "ACTIVE",
  "newStatus": "DEPRECATED",
  "reason": "Success rate dropped below 85% (current: 78%)"
}
```

**Status:** TODO - nice to have, dodać przy wdrożeniu na produkcję.

---

### 17.7 Podsumowanie wszystkich luk

| # | Luka | Priorytet | Status |
|---|------|-----------|--------|
| 1 | Race condition przy tworzeniu reguł | 🟡 Średni | TODO (single instance OK) |
| 2 | Brak walidacji AI rules | 🔴 Wysoki | ✅ **ZROBIONE** (sekcja 16.1) |
| 3 | Structure Hash collision | 🔴 Wysoki | ✅ **ZROBIONE** (sekcja 16.2) |
| 4 | Brak obsługi częściowego sukcesu | 🟡 Średni | TODO |
| 5 | Wersjonowanie promptu AI | 🔴 Wysoki | ✅ **ZROBIONE** (sekcja 16.3) |
| 6 | L1 cache stale data | 🟡 Średni | TODO (single instance OK) |
| 7 | Unbounded growth | 🟢 Niski | TODO (nie krytyczne na start) |
| 8 | Nowe kategorie bankowe | 🟡 Średni | TODO |
| 9 | Brak audit trail | 🟢 Niski | TODO (nice to have) |

**Kolejność implementacji:**
1. ✅ Luki #2, #3, #5 - krytyczne, zrobione
2. Luka #4 (partial success) - przy pierwszych testach E2E
3. Luki #1, #6 - przy skalowaniu do wielu instancji
4. Luka #8 - przy pierwszym realnym przypadku
5. Luki #7, #9 - przy wdrożeniu produkcyjnym

---

## Podsumowanie

### Rekomendacja końcowa

1. **Start z Faza 1** (regex + CountryConfig)
   - Pokryje większość przypadków
   - Zero dodatkowych kosztów
   - Szybkie do implementacji

2. **Dodaj Faza 2** gdy pojawią się problemy
   - AI fallback tylko dla uncertain columns
   - Koszt nieistotny (~$0.0004 per plik)

3. **Faza 3** przy dużej skali
   - Cache decisions per bank
   - Zero AI calls dla znanych formatów

### Kluczowe decyzje

| Decyzja | Wybór | Uzasadnienie |
|---------|-------|--------------|
| Fake data vs Placeholder | **Placeholder** | Prostsze dla AI |
| Per-column vs Per-cell detection | **Per-column** (start) | Prostsze, wystarczy dla 90% |
| Regex vs AI | **Hybrid** | Najlepsza równowaga |
| Gotowa biblioteka vs własna | **Własna + DataFaker** | Lepsza kontrola |

---

*Dokument wygenerowany: 2026-03-20*
