package com.multi.vidulum.bank_data_adapter.fixtures;

import com.multi.vidulum.bank_data_adapter.app.AiBankCsvAdapterDto;
import com.multi.vidulum.bank_data_adapter.app.AiBankCsvAdapterHttpActor;
import com.multi.vidulum.cashflow.CashFlowIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wytwórca fixture'u: surowy wyciąg banku przechodzi raz przez adapter, a wynik kanoniczny
 * zostaje zapisany na dysk i zacommitowany.
 *
 * <p><b>Dlaczego to jest test, a nie skrypt.</b> Transformacja wymaga działającego kontekstu
 * Springa, Mongo (dokument transformacji jest pisany) i uwierzytelnienia - czyli dokładnie tego, co
 * {@link CashFlowIntegrationTest} już stawia. Skrypt musiałby to wszystko powtórzyć, a aktor HTTP i
 * rejestracja użytkownika istnieją tu od dawna.
 *
 * <p><b>Nie biega w CI i to jest zamierzone.</b> Warunek
 * {@code @EnabledIfEnvironmentVariable(ANTHROPIC_API_KEY)} wyłącza go, gdy klucza nie ma - bo wtedy
 * jedyne, co mógłby zrobić, to zawieść z powodu niezwiązanego z kodem. Z kluczem woła prawdziwy
 * model, kosztuje pieniądze i jest niedeterministyczny, dlatego ma zostać uruchomiony <b>raz</b>, a
 * jego wynik ma być artefaktem w repo - nie czymś, co się odtwarza przy każdym przebiegu.
 *
 * <p>Uruchomienie:
 * <pre>
 * export ANTHROPIC_API_KEY="sk-ant-..."
 * ./mvnw test -pl :vidulum-cashflow-tests -Dtest=CanonicalCsvFixtureGenerator
 * </pre>
 *
 * <p>Wynik: {@code src/test/resources/canonical/*.csv} - dwa pliki w formacie kanonicznym, gotowe do
 * skopiowania do {@code vidulum-ingestion-tests}, gdy ten moduł powstanie (zadanie K12 i T1 tablicy
 * {@code docs/features-backlog/ingestion-v2-tasks.md}).
 */
@Slf4j
@DisplayName("Fixture: surowy wyciąg → CSV kanoniczny, przez adapter")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+",
        disabledReason = "wytwórca fixture'u woła prawdziwy model; bez klucza nie ma czego wołać")
class CanonicalCsvFixtureGenerator extends CashFlowIntegrationTest {

    /** Dokąd trafia wynik - źródła testowe tego modułu, bo tu biegnie wytwórca. */
    private static final Path OUTPUT_DIR = Path.of("src/test/resources/canonical");

    /**
     * Siedemnaście kolumn, których oczekuje parser ingestion. Trzy ostatnie dokłada enrichment,
     * więc ich obecność jest jednocześnie sprawdzeniem, że ten krok naprawdę się wykonał.
     */
    private static final String EXPECTED_HEADER = String.join(",", List.of(
            "bankTransactionId", "name", "description", "bankCategory", "amount", "currency", "type",
            "operationDate", "bookingDate", "sourceAccountNumber", "targetAccountNumber",
            "merchant", "merchantConfidence", "paymentMethod",
            "classification", "classificationReason", "location"));

    private AiBankCsvAdapterHttpActor actor;

    @BeforeEach
    void setUp() {
        registerAndAuthenticate();
        actor = new AiBankCsvAdapterHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    @Test
    @DisplayName("Nest Bank: 402 wiersze surowe → kanoniczne")
    void shouldProduceCanonicalCsvForNestBank() throws IOException {
        generate("nestbank_lista_operacji_20260111.csv", "Nest Bank", "nestbank-canonical.csv");
    }

    @Test
    @DisplayName("Pekao SA: wyciąg surowy → kanoniczny")
    void shouldProduceCanonicalCsvForPekao() throws IOException {
        generate("pekao_sa_Lista_operacji_20260111_013400.csv", "Pekao SA", "pekao-canonical.csv");
    }

    private void generate(String rawResource, String bankHint, String outputName) throws IOException {
        AiBankCsvAdapterDto.TransformResponse transformation = actor.transform("/" + rawResource, bankHint);

        assertThat(transformation.isSuccess())
                .as("transformacja %s musi się udać, inaczej nie ma czego commitować", rawResource)
                .isTrue();

        String canonical = actor.downloadCsv(transformation.getTransformationId());
        String header = canonical.lines().findFirst().orElseThrow();
        long dataRows = canonical.lines().skip(1).filter(line -> !line.isBlank()).count();

        // Te dwie asercje są całym sensem wytwórcy: bez nich zacommitowalibyśmy plik, którego parser
        // ingestion nie przyjmie, i dowiedzielibyśmy się o tym dopiero w K6.
        assertThat(header)
                .as("nagłówek musi mieć 17 kolumn kanonicznych - trzy ostatnie dokłada enrichment")
                .isEqualTo(EXPECTED_HEADER);
        assertThat(dataRows)
                .as("plik bez wierszy danych nie jest fixture'em")
                .isGreaterThan(0);

        Files.createDirectories(OUTPUT_DIR);
        Path output = OUTPUT_DIR.resolve(outputName);
        Files.writeString(output, canonical, StandardCharsets.UTF_8);

        // Liczba wierszy idzie do logu, bo to ona - nie szacunek z dokumentacji - jest tą liczbą,
        // którą wolno potem wpisać w asercje testów ingestion.
        log.info("FIXTURE [{}]: {} wierszy danych, bank=[{}], transformationId=[{}], plik=[{}]",
                outputName, dataRows, transformation.getDetectedBank(),
                transformation.getTransformationId(), output.toAbsolutePath());
    }
}
