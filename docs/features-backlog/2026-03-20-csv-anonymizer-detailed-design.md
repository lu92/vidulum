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

### Faza 3: Cache

**Zakres:**
- `TypeDetectionCache` (MongoDB)
- Cache key: `{bankName}_{structureHash}`
- Automatic cache invalidation

**Dodatkowe klasy:**
```
infrastructure/
└── anonymization/
    ├── TypeDetectionCache.java
    └── TypeDetectionCacheDocument.java (MongoDB)
```

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
