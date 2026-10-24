# 02 · `vidulum-ingestion-intake`

**Rola:** bajty stają się wierszami kanonicznymi. Liść — nie wie o sesji, o cash flow ani o tym, co
się dalej stanie z wierszem.

**Posiada danych:** żadnych.

**Zależności (`pom.xml`):**

```xml
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>org.apache.commons:commons-csv</dependency>
```

**Bez Springa, świadomie.** Dzisiejszy `CsvParserService` jest `@Service` i bierze
`MultipartFile` — czyli parser trzyma typ webowy i wymaga kontekstu Springa, żeby go stworzyć. Tu
jest zwykłą klasą, którą conductor tworzy przez `new`. Konsekwencja praktyczna: test parsera nie
podnosi niczego, a moduł nie ciągnie `spring-web`.

## Kontrakt

```java
public final class CanonicalCsvParser {

    public CanonicalCsvParser();                       // bezstanowy, bezpieczny wątkowo

    /** Podstawowa metoda. Bajty, nie MultipartFile — web jest sprawą conductora. */
    public ParseResult parse(byte[] content, Charset charset);

    /** UTF-8, bo taki CSV produkuje adapter. */
    public ParseResult parse(byte[] content);
}

/**
 * Parsowanie NIGDY nie rzuca na błędzie wiersza. Zły wiersz to pozycja w errors[],
 * a nie utrata 401 pozostałych - to jest ta sama decyzja, co "wiersz nie blokuje wyciągu"
 * w §11 dokumentu nadrzędnego.
 */
public record ParseResult(
        List<CanonicalRow> rows,
        List<ParseError> errors,
        int totalRows) {
    boolean isEmpty();
    int successfulRows();
    int failedRows();
}

public record ParseError(int lineNo, String column, String message, ErrorCode code) {}

/** Heurystyka po nazwie operacji z polskiego banku - przeniesiona tu ze słownika. */
public final class PolishBankPaymentMethods {
    public static PaymentMethod fromOperationType(String operationType);   // nierozpoznane → OTHER
}

/** Nadaje id wierszowi, który go nie ma. Deterministycznie, bo od tego zależy idempotencja importu. */
public final class BankTransactionIdFallback {
    public static String of(CanonicalRow row);    // sha256(operationDate|amount|currency|name), 16 znaków
}
```

### Dwie rzeczy, które parser robi, a których nie widać z sygnatury

1. **Rzuca `BusinessException` tylko na błędzie *pliku***, nie wiersza: brak nagłówka, nagłówek
   niezgodny z `CanonicalColumns.HEADERS`, plik pusty, plik niebędący CSV. `ErrorCode` —
   `INGESTION_INVALID_CSV`. Wszystko inne to `errors[]`.
2. **Nadaje `bankTransactionId`, gdy go nie ma**, i robi to **deterministycznie**. To nie kosmetyka:
   idempotencja importu stoi na tym id (`CashFlow.findImported`), więc losowe UUID sprawiłoby, że
   ten sam plik wgrany dwa razy wchodzi dwa razy. Dziś komentarz w `BankCsvRow` mówi
   „hash(operationDate + amount + name)" — tu jest to funkcją z nazwą i z testem.

## Scenariusze testowe — poziom unit

### Plik jako całość

| test | given → then |
|---|---|
| `shouldParseTheGoldenFile` | `lista_operacji_20260111.csv` (402 wiersze, Nest Bank) → `rows.size()==402`, `errors` puste, pierwszy i ostatni wiersz asercją **całoobiektową** przez konstruktor `CanonicalRow` |
| `shouldRefuseFileWithoutHeader` | CSV od razu z danymi → `BusinessException(INGESTION_INVALID_CSV)` |
| `shouldRefuseHeaderWithMissingColumn` | nagłówek bez `type` → `BusinessException`, komunikat nazywa brakującą kolumnę |
| `shouldRefuseEmptyFile` | 0 bajtów → `BusinessException(INGESTION_INVALID_CSV)` |
| `shouldAcceptHeaderOnlyFile` | tylko nagłówek → `rows` puste, `errors` puste, `totalRows==0` (to nie błąd, to pusty wyciąg) |
| `shouldSkipBlankLines` | puste linie w środku → pomijane, numeracja `lineNo` pozostałych bez przeskoków błędnych |
| `shouldReadBomWithoutBreakingFirstColumn` | UTF-8 BOM na początku → pierwsza kolumna sparsowana, nie `﻿bankTransactionId` |
| `shouldReadCp1250` | plik w CP1250 z „Żabka" → `parse(bytes, CP1250)` zwraca poprawne polskie znaki |
| `shouldHandleCrlf` | końce linii `\r\n` → bez `\r` na końcu ostatniej kolumny |

### Wiersz

| test | given → then |
|---|---|
| `shouldReportBadDateAndKeepOtherRows` | wiersz 7 z datą `2023-13-45`, reszta poprawna → `rows.size()==401`, `errors` ma 1 pozycję z `lineNo==7` i kolumną `operationDate` |
| `shouldAcceptEverySupportedDateFormat` | `2023-01-13`, `13.01.2023`, `13/01/2023`, `2023/01/13` → ta sama `LocalDate` |
| `shouldParseAmountWithCommaDecimalSeparator` | `1234,50` → `new BigDecimal("1234.50")` ze **skalą 2** |
| `shouldParseAmountWithThousandsSeparator` | `1 234,50` i `1234.50` → ta sama wartość |
| `shouldReportNegativeAmount` | `-23.45` → błąd wiersza: kwota jest zawsze dodatnia, kierunek mówi `type` |
| `shouldReportUnknownType` | `type=WYDATEK` → błąd wiersza z kolumną `type` |
| `shouldDefaultUnknownClassificationToUnknown` | `classification=CRYPTO_SWAP` → `UNKNOWN`, **bez błędu** (producent może znać słowa, których my nie znamy) |
| `shouldDefaultUnknownPaymentMethodToOther` | `paymentMethod=BITCOIN` → `OTHER`, bez błędu |
| `shouldKeepQuotedCommasInDescription` | `"ŻABKA, WARSZAWA, UL. X"` → jedna kolumna z przecinkami |
| `shouldMintDeterministicIdWhenMissing` | dwa przebiegi tego samego wiersza bez `bankTransactionId` → **to samo** id |
| `shouldMintDifferentIdsForDifferentRows` | ta sama data i kwota, inna nazwa → różne id |
| `shouldNotMintIdWhenBankGaveOne` | `bankTransactionId=2023011301` → zachowane bez zmian |
| `shouldPassThroughDuplicateIdsWithinFile` | dwa wiersze z tym samym `bankTransactionId` → **oba** w `rows` (deduplikacja to sprawa stagingu, nie parsera) |

### `PolishBankPaymentMethods`

| test | given → then |
|---|---|
| `shouldRecognisePolishOperationTypes` | tabela: „TRANSAKCJA KARTĄ PŁATNICZĄ"→CARD, „PŁATNOŚĆ BLIK"→BLIK, „POLECENIE ZAPŁATY"→DIRECT_DEBIT, „ZLECENIE STAŁE"→STANDING_ORDER, „WYPŁATA Z BANKOMATU"→CASH, „PRZELEW ELIXIR"→TRANSFER |
| `shouldPreferCardOverTransferWhenBothWordsPresent` | „PRZELEW NA KARTĘ" → kolejność sprawdzania jest regułą, nie przypadkiem; test ją **zamraża** |
| `shouldAnswerOtherForNullAndBlank` | `null`, `""`, `"   "` → `OTHER` |

**Czas wykonania:** < 2 s z plikiem 402-wierszowym w zasobach testowych.

## Reguły architektoniczne

Jedna, i znów wymuszona Mavenem: moduł ma w classpathie **wyłącznie** `ingestion-canonical` i
commons-csv, więc parser nie może sięgnąć po sesję ani po cash flow. Dzisiejsza reguła
`intakeKnowsNothingButTheCanonicalRow` przestaje być testem i staje się faktem budowy.

## Czego tu nie ma

| rzecz | gdzie jest | dlaczego |
|---|---|---|
| `MultipartFile`, `@RequestParam`, metadane uploadu | 04 `ingestion-conductor` | to warstwa web |
| decyzja, co zrobić z `errors[]` | 04 `ingestion-conductor` | parser raportuje, saga decyduje |
| walidacja biznesowa daty (okno importu, status miesiąca) | 05 `ingestion-staging` | parser wie, czy to **jest** data; nie wie, czy **wolno** |
| wykrywanie formatu banku, transformacja AI | `bank-data-adapter` (**nietknięty**) | tu wchodzi już CSV kanoniczny |
