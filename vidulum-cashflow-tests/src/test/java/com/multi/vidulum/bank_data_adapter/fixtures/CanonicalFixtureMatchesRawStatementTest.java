package com.multi.vidulum.bank_data_adapter.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture kanoniczny musi zgadzać się z wyciągiem, z którego powstał.
 *
 * <p><b>Dlaczego to test, a nie zaufanie.</b> Oba pliki w {@code resources/canonical} są artefaktami:
 * powstały raz, ze skryptu, i leżą w repo. Artefakt, którego nikt nie weryfikuje, zaczyna kłamać po
 * pierwszej ręcznej edycji - ktoś poprawi jeden wiersz, żeby test przeszedł, i od tego momentu
 * fixture opisuje coś innego niż wyciąg. Ten test porównuje go ze <b>źródłem</b>, nie z własnymi
 * oczekiwaniami wpisanymi na sztywno.
 *
 * <p>Bez Springa, bez Dockera, bez modelu - czyta dwa pliki z classpathu i liczy. Dwie sekundy.
 *
 * <p>Jedna asercja jest mocniejsza niż pozostałe i warto ją nazwać: wyciąg Nest <b>sam deklaruje</b>
 * w metadanych {@code Suma uznań} i {@code Suma obciążeń}, więc zgodność co do grosza dowodzi, że
 * przepisanie nie zgubiło ani nie zniekształciło pieniędzy. Pekao metadanych nie ma, więc tam sumy
 * liczymy niezależnie z pliku surowego.
 */
@DisplayName("Fixture kanoniczny zgadza się z surowym wyciągiem")
class CanonicalFixtureMatchesRawStatementTest {

    private static final String EXPECTED_HEADER = "bankTransactionId,name,description,bankCategory,"
            + "amount,currency,type,operationDate,bookingDate,sourceAccountNumber,"
            + "targetAccountNumber,merchant,merchantConfidence,paymentMethod,"
            + "classification,classificationReason,location";

    @Test
    @DisplayName("Nest: 402 wiersze i sumy zgodne z metadanymi wyciągu")
    void nestFixtureMatchesTheRawStatement() throws IOException {
        List<Map<String, String>> canonical = readCsv("/canonical/nestbank-canonical.csv", ',');

        assertThat(canonical).hasSize(402);
        assertThat(sumOf(canonical, "INFLOW")).isEqualByComparingTo("1217096.34");   // Suma uznań
        assertThat(sumOf(canonical, "OUTFLOW")).isEqualByComparingTo("1141912.03");  // Suma obciążeń
        assertThat(datesOf(canonical)).first().isEqualTo("2023-01-13");
        assertThat(datesOf(canonical)).last().isEqualTo("2025-12-31");
        assertThat(currenciesOf(canonical)).containsExactly("PLN");

        // Nest nie daje numeru referencyjnego, więc id jest skrótem - a skrót, który się powtarza,
        // kazałby importowi uznać różne transakcje za jedną. Tu pilnujemy, że się nie powtarza.
        assertThat(idsOf(canonical)).doesNotHaveDuplicates().hasSize(402);
    }

    @Test
    @DisplayName("Pekao: 791 wierszy, sumy policzone niezależnie z pliku surowego")
    void pekaoFixtureMatchesTheRawStatement() throws IOException {
        List<Map<String, String>> canonical = readCsv("/canonical/pekao-canonical.csv", ',');
        List<Map<String, String>> raw = readCsv("/pekao_sa_Lista_operacji_20260111_013400.csv", ';');

        assertThat(canonical).hasSize(raw.size()).hasSize(791);

        BigDecimal rawInflow = BigDecimal.ZERO;
        BigDecimal rawOutflow = BigDecimal.ZERO;
        for (Map<String, String> row : raw) {
            // Pekao pisze tysiące ze spacją: "3 000,00". Spacja zwykła i twarda, bo w wyciągach
            // bywają obie, a BigDecimal nie wybacza ani jednej.
            BigDecimal amount = new BigDecimal(row.get("Kwota operacji").trim()
                    .replace("\u00a0", "").replace(" ", "").replace(",", "."));
            if (amount.signum() < 0) {
                rawOutflow = rawOutflow.add(amount.abs());
            } else {
                rawInflow = rawInflow.add(amount);
            }
        }
        assertThat(sumOf(canonical, "INFLOW")).isEqualByComparingTo(rawInflow);
        assertThat(sumOf(canonical, "OUTFLOW")).isEqualByComparingTo(rawOutflow);
        assertThat(currenciesOf(canonical)).containsExactlyInAnyOrder("PLN", "EUR");

        // Jedyny duplikat w tych danych jest prawdziwy: bank nadał ten sam numer referencyjny obu
        // stronom przelewu wewnętrznego. Pinujemy go, bo to on jest przypadkiem testowym dla
        // deduplikacji - gdyby zniknął, zniknąłby też jedyny realny dowód na ten scenariusz.
        assertThat(idsOf(canonical)).hasSize(791);
        assertThat(Set.copyOf(idsOf(canonical))).hasSize(790);
        assertThat(idsOf(canonical).stream().filter("1342556624908662"::equals).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Oba pliki mają dokładnie 17 kolumn kanonicznych, w kolejności")
    void bothFixturesCarryTheCanonicalHeader() throws IOException {
        for (String resource : List.of("/canonical/nestbank-canonical.csv", "/canonical/pekao-canonical.csv")) {
            assertThat(firstLine(resource))
                    .as("nagłówek %s musi być kontraktem tekstowym z CsvParserService.HEADERS", resource)
                    .isEqualTo(EXPECTED_HEADER);
        }
    }

    @Test
    @DisplayName("Każdy wiersz ma wypełnione pola wymagane i dopuszczalną klasyfikację")
    void everyRowIsComplete() throws IOException {
        Set<String> vocabulary = Set.of("MERCHANT", "BANK_FEE", "CASH_WITHDRAWAL", "CASH_DEPOSIT",
                "SELF_TRANSFER", "INTEREST", "UNKNOWN");
        for (String resource : List.of("/canonical/nestbank-canonical.csv", "/canonical/pekao-canonical.csv")) {
            for (Map<String, String> row : readCsv(resource, ',')) {
                assertThat(row.get("bankTransactionId")).as("id w %s", resource).isNotBlank();
                assertThat(row.get("name")).as("name w %s", resource).isNotBlank();
                assertThat(row.get("type")).isIn("INFLOW", "OUTFLOW");
                assertThat(new BigDecimal(row.get("amount"))).isGreaterThan(BigDecimal.ZERO);
                assertThat(new BigDecimal(row.get("amount")).scale())
                        .as("kwota trzyma skalę 2 - inaczej 1234.50 stanie się 1234.5")
                        .isEqualTo(2);
                assertThat(row.get("operationDate")).matches("\\d{4}-\\d{2}-\\d{2}");
                assertThat(row.get("classification")).isIn(vocabulary);
                assertThat(row.get("classificationReason")).isNotBlank();
            }
        }
    }

    // ───────────────────────────── czytanie CSV, na tyle, na ile trzeba

    private static BigDecimal sumOf(List<Map<String, String>> rows, String type) {
        return rows.stream()
                .filter(row -> type.equals(row.get("type")))
                .map(row -> new BigDecimal(row.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> datesOf(List<Map<String, String>> rows) {
        return rows.stream().map(row -> row.get("operationDate")).sorted().toList();
    }

    private static Set<String> currenciesOf(List<Map<String, String>> rows) {
        return new HashSet<>(rows.stream().map(row -> row.get("currency")).toList());
    }

    private static List<String> idsOf(List<Map<String, String>> rows) {
        return rows.stream().map(row -> row.get("bankTransactionId")).toList();
    }

    private static String firstLine(String resource) throws IOException {
        return new String(read(resource), StandardCharsets.UTF_8).lines().findFirst().orElseThrow();
    }

    private static List<Map<String, String>> readCsv(String resource, char delimiter) throws IOException {
        List<String> lines = new String(read(resource), StandardCharsets.UTF_8).lines().toList();
        List<String> header = split(lines.get(0), delimiter);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = split(line, delimiter);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < values.size() ? values.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /** Minimalny podział z obsługą cudzysłowów - tyle, ile te dwa pliki potrzebują. */
    private static List<String> split(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == delimiter && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream stream = CanonicalFixtureMatchesRawStatementTest.class.getResourceAsStream(resource)) {
            return Objects.requireNonNull(stream, "brak zasobu: " + resource).readAllBytes();
        }
    }
}
