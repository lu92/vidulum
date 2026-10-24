# 01 · `vidulum-ingestion-canonical`

**Rola:** słownik. Czym staje się wyciąg bankowy, kiedy przestaje być plikiem — i nic więcej.

**Posiada danych:** żadnych. Bez Springa, bez Mongo, bez storage.

**Zależności (`pom.xml`):**

```xml
<dependency>vidulum-shared-kernel</dependency>     <!-- Money -->
<dependency>vidulum-cashflow-api</dependency>      <!-- Type: INFLOW / OUTFLOW -->
```

Dwie, tak jak `vidulum-bank-data-ai-categorization-api` dziś. **Zero starterów Springa** — i to jest
to, co czyni ten moduł słownikiem, a nie biblioteką.

## Kontrakt

```java
/** 17 kolumn, dokładnie jak je produkuje bank-data-adapter. */
public record CanonicalRow(
        String bankTransactionId,      // opcjonalne; null → hash(date+amount+name) nadaje intake
        String name,                   // WYMAGANE
        String description,
        String bankCategory,
        BigDecimal amount,             // zawsze dodatnia; kierunek mówi type
        String currency,
        Type type,                     // WYMAGANE
        LocalDate operationDate,       // WYMAGANE
        LocalDate bookingDate,
        String sourceAccountNumber,
        String targetAccountNumber,
        String merchant,
        Double merchantConfidence,
        PaymentMethod paymentMethod,
        TransactionClassification classification,
        String classificationReason,
        String location) {

    LocalDate effectiveBookingDate();              // bookingDate ?: operationDate
    String effectiveDescription();                 // "" gdy null
    String effectiveBankCategory();                // "Uncategorized" gdy puste
    String effectiveMerchant();                    // name gdy merchant puste
    boolean hasHighConfidenceMerchant();           // merchantConfidence >= 0.7
    PaymentMethod effectivePaymentMethod();        // OTHER gdy null
    TransactionClassification effectiveClassification();   // UNKNOWN gdy null
    boolean isAutoCategorizeable();
    boolean includeInBudget();
    boolean hasMerchant();
    String counterpartyAccount();                  // OUTFLOW: target → source, INFLOW: source → target
    Money money();                                 // Money.of(amount, currency) — jedno miejsce
}

/** Słownik słów, nie typ wymieniany z adapterem. */
public enum TransactionClassification {
    MERCHANT, BANK_FEE, CASH_WITHDRAWAL, CASH_DEPOSIT, SELF_TRANSFER, INTEREST, UNKNOWN;
    boolean hasMerchant();
    boolean isAutoCategorizeable();
    boolean includeInBudget();
    static TransactionClassification fromString(String value);   // null/blank/nieznane → UNKNOWN
}

public enum PaymentMethod { CARD, TRANSFER, BLIK, DIRECT_DEBIT, STANDING_ORDER, CASH, OTHER }

/** Nazwy kolumn CSV — kontrakt tekstowy z adapterem, nie prywatna tablica w parserze. */
public final class CanonicalColumns {
    public static final List<String> HEADERS = List.of(/* 17 nazw w kolejności */);
}

/**
 * Klucz grupy: jednostka pracy CAŁEGO pipeline'u. Grup jest ~38 tam, gdzie wierszy 402.
 * Nadawany w VALIDATE, czytany przez conductor, resolution i classification.
 */
public record GroupKey(String value) {
    static GroupKey selfTransfer();                               // "SELF_TRANSFER"
    static GroupKey ofClassification(TransactionClassification c); // "CLASS|BANK_FEE"
    static GroupKey ofMerchant(String merchant, String bankCategory, Type type);
                                                                   // "MERCHANT|ŻABKA|Zakupy kartą|OUTFLOW"
    static GroupKey of(CanonicalRow row, boolean selfTransfer);     // reguła wyboru w jednym miejscu
}
```

### Dwie zmiany wobec dzisiejszych typów, obie z powodu

1. **`PaymentMethod.fromPolishBankType(String)` tu nie wchodzi.** To heurystyka po polskich nazwach
   operacji („KART", „BLIK", „BANKOMAT") — czyli parsowanie. Idzie do `ingestion-intake` (02).
   Słownik nie zgaduje, słownik nazywa.
2. **`money()` zamiast `BigDecimal amount` + `String currency` u każdego konsumenta.** Dziś
   `OriginalTransactionData` nosi `Money`, a `BankCsvRow` parę pól, więc konwersja żyje w trzech
   miejscach. Tu jest jedna, i to ona pilnuje skali (`1234.50` nie staje się `1234.5` — lekcja
   `BigDecimal.valueOf(double)` z CLAUDE.md).

## Scenariusze testowe — poziom unit, bez Springa

| test | given → then |
|---|---|
| `shouldDefaultEveryOptionalField` | wiersz z samymi wymaganymi polami → `effectiveBookingDate()==operationDate`, `effectiveDescription()==""`, `effectiveBankCategory()=="Uncategorized"`, `effectiveMerchant()==name`, `effectivePaymentMethod()==OTHER`, `effectiveClassification()==UNKNOWN` |
| `shouldNotDefaultWhatWasGiven` | wszystkie pola wypełnione → każda metoda `effective*` zwraca wartość z wiersza, nie domyślną |
| `shouldKeepMoneyScale` | `amount = 1234.50`, `currency = PLN` → `money().getAmount()` **equals** (nie tylko `compareTo`) `new BigDecimal("1234.50")` |
| `shouldTreatMerchantConfidenceBoundary` | 0.69 → `hasHighConfidenceMerchant()==false`; 0.70 → `true` |
| `shouldPreferSemanticColumnForCounterparty` | OUTFLOW z oboma kontami → zwraca `targetAccountNumber` |
| `shouldFallBackWhenAiPutAccountInWrongColumn` | OUTFLOW z pustym `target` i wypełnionym `source` → zwraca `source` (AI myli kolumny — udokumentowane) |
| `shouldAnswerUnknownForWordsItDoesNotKnow` | `fromString("CRYPTO_SWAP")`, `fromString(null)`, `fromString("  ")` → `UNKNOWN` |
| `shouldParseClassificationIgnoringCase` | `"bank_fee"`, `" BANK_FEE "` → `BANK_FEE` |
| `shouldSayWhichClassificationsSkipTheModel` | pętla po `values()` → `isAutoCategorizeable()` prawdziwe dokładnie dla BANK_FEE, CASH_WITHDRAWAL, CASH_DEPOSIT, SELF_TRANSFER, INTEREST |
| `shouldExcludeOnlySelfTransferFromBudget` | pętla po `values()` → `includeInBudget()` fałszywe dokładnie dla `SELF_TRANSFER` |
| `shouldGroupSelfTransferByItself` | wiersz oznaczony jako self-transfer → `GroupKey.of(...)` == `SELF_TRANSFER`, **niezależnie** od merchanta i bankCategory |
| `shouldGroupAutoCategorizableByClassificationNotMerchant` | dwa wiersze `BANK_FEE` o różnych nazwach → **ten sam** `GroupKey` `CLASS\|BANK_FEE` |
| `shouldGroupMerchantRowsByMerchantAndBankCategory` | ŻABKA + „Zakupy kartą" vs ŻABKA + „Przelewy" → **dwa różne** klucze |
| `shouldGroupByNameWhenMerchantMissing` | `merchant == null` → klucz zawiera `name` |
| `shouldSeparateDirectionsInGroupKey` | ten sam merchant i bankCategory, INFLOW vs OUTFLOW → dwa klucze (cash flow może mieć „Transfer" po obu stronach) |
| `shouldListSeventeenHeadersInOrder` | `CanonicalColumns.HEADERS` → dokładnie 17 nazw w kolejności, którą produkuje adapter |

Pętle po `values()` w trzech testach są celowe: nowa stała w enumie **musi** zmusić do decyzji, po
której stronie każdej z trzech reguł leży. To ten sam wzorzec, którym CLAUDE.md pilnuje
`MonthStatus`.

**Czas wykonania:** cały moduł < 1 s, zero Dockera.

## Reguły architektoniczne

**Granica Mavena zastępuje tu test ArchUnit.** Dzisiejszy `CanonicalRowIsSelfContainedTest`
sprawdza, że pakiet `canonical` nie zależy od sekcji modułu — bo wszystkie są w jednym jarze. Po
wydzieleniu artefaktu ten moduł **nie ma w classpathu** niczego z rodziny ingestion, więc reguła
jest wymuszona przez kompilator. To jest cała wartość tego wydzielenia.

Jedna reguła zostaje i trafia do `vidulum-ingestion-tests` (09): **słownik musi się zgadzać z
adapterem** — `TransactionClassificationVocabularyTest`, który dziś mieszka w
`vidulum-cashflow-tests` i porównuje stałe obu enumów. Jego nowa wersja porównuje
`ingestion-canonical` z `bank_data_adapter.domain.TransactionClassification`, bo tylko moduł testowy
widzi oba.

## Czego tu nie ma

| rzecz | gdzie jest | dlaczego nie tu |
|---|---|---|
| parsowanie CSV | 02 `ingestion-intake` | słownik nie czyta plików |
| `fromPolishBankType` | 02 `ingestion-intake` | heurystyka po tekście banku to parsowanie |
| walidacja wiersza, duplikaty, miesiące | 05 `ingestion-staging` | to decyzje o wierszu, nie jego nazwa |
| rozstrzygnięcie kategorii | 07 `category-resolution` | j.w. |
| `TransactionClassification` jako typ **wymieniany** z adapterem | nigdzie — to kopia | kontrakt to kolumna CSV i słownik słów; test w 09 pilnuje rozjazdu |
