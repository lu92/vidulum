package com.multi.vidulum.bank_data_adapter.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BankDataIngestionClient DTO mapping.
 * Verifies that JSON responses from bank-data-ingestion are correctly deserialized.
 */
@DisplayName("BankDataIngestionClient DTO Mapping")
class BankDataIngestionClientDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should deserialize UploadCsvResponse with stagingResult")
    void shouldDeserializeUploadCsvResponseWithStagingResult() throws Exception {
        // given - JSON response from bank-data-ingestion /upload endpoint
        String json = """
            {
                "parseSummary": {
                    "totalRows": 74,
                    "successfulRows": 74,
                    "failedRows": 0
                },
                "stagingResult": {
                    "stagingSessionId": "abc-123-staging",
                    "cashFlowId": "CF10000015",
                    "status": "HAS_UNMAPPED_CATEGORIES"
                }
            }
            """;

        // when
        BankDataIngestionClient.UploadCsvResponse response =
            objectMapper.readValue(json, BankDataIngestionClient.UploadCsvResponse.class);

        // then
        assertThat(response).isNotNull();
        assertThat(response.parseSummary()).isNotNull();
        assertThat(response.parseSummary().totalRows()).isEqualTo(74);
        assertThat(response.parseSummary().successfulRows()).isEqualTo(74);
        assertThat(response.parseSummary().failedRows()).isEqualTo(0);

        assertThat(response.stagingResult()).isNotNull();
        assertThat(response.stagingResult().stagingSessionId()).isEqualTo("abc-123-staging");
        assertThat(response.stagingResult().cashFlowId()).isEqualTo("CF10000015");
        assertThat(response.stagingResult().status()).isEqualTo("HAS_UNMAPPED_CATEGORIES");

        // Test helper method
        assertThat(response.getStagingSessionId()).isEqualTo("abc-123-staging");
    }

    @Test
    @DisplayName("Should deserialize full UploadCsvResponse with all backend fields")
    void shouldDeserializeFullUploadCsvResponseWithAllBackendFields() throws Exception {
        // given - Full JSON response from bank-data-ingestion /upload endpoint
        // This matches the actual StageTransactionsResponse structure from BankDataIngestionDto
        String json = """
            {
                "parseSummary": {
                    "totalRows": 74,
                    "successfulRows": 74,
                    "failedRows": 0,
                    "errors": []
                },
                "stagingResult": {
                    "stagingSessionId": "staging-session-uuid-123",
                    "cashFlowId": "CF10000015",
                    "status": "HAS_UNMAPPED_CATEGORIES",
                    "expiresAt": "2026-03-23T10:30:00Z",
                    "summary": {
                        "totalTransactions": 74,
                        "validTransactions": 70,
                        "invalidTransactions": 2,
                        "duplicateTransactions": 2
                    },
                    "categoryBreakdown": [
                        {
                            "targetCategory": "Groceries",
                            "parentCategory": "Food",
                            "transactionCount": 15,
                            "totalAmount": 1500.50,
                            "currency": "PLN",
                            "type": "OUTFLOW",
                            "isNewCategory": false
                        }
                    ],
                    "categoriesToCreate": [],
                    "monthlyBreakdown": [
                        {
                            "month": "2025-12",
                            "inflowTotal": 8500.00,
                            "outflowTotal": 3500.00,
                            "currency": "PLN",
                            "transactionCount": 25
                        }
                    ],
                    "duplicates": [],
                    "unmappedCategories": [
                        {
                            "bankCategory": "Unknown Category",
                            "count": 5,
                            "type": "OUTFLOW"
                        }
                    ]
                }
            }
            """;

        // when
        BankDataIngestionClient.UploadCsvResponse response =
            objectMapper.readValue(json, BankDataIngestionClient.UploadCsvResponse.class);

        // then - Should correctly parse the fields we care about and ignore the rest
        assertThat(response).isNotNull();
        assertThat(response.parseSummary()).isNotNull();
        assertThat(response.parseSummary().totalRows()).isEqualTo(74);
        assertThat(response.parseSummary().successfulRows()).isEqualTo(74);
        assertThat(response.parseSummary().failedRows()).isEqualTo(0);

        assertThat(response.stagingResult()).isNotNull();
        assertThat(response.stagingResult().stagingSessionId()).isEqualTo("staging-session-uuid-123");
        assertThat(response.stagingResult().cashFlowId()).isEqualTo("CF10000015");
        assertThat(response.stagingResult().status()).isEqualTo("HAS_UNMAPPED_CATEGORIES");

        // Test helper method
        assertThat(response.getStagingSessionId()).isEqualTo("staging-session-uuid-123");
    }

    @Test
    @DisplayName("Should deserialize UploadCsvResponse without stagingResult (parse only)")
    void shouldDeserializeUploadCsvResponseWithoutStagingResult() throws Exception {
        // given - JSON response when staging was not created (e.g., parse errors)
        String json = """
            {
                "parseSummary": {
                    "totalRows": 10,
                    "successfulRows": 5,
                    "failedRows": 5
                },
                "stagingResult": null
            }
            """;

        // when
        BankDataIngestionClient.UploadCsvResponse response =
            objectMapper.readValue(json, BankDataIngestionClient.UploadCsvResponse.class);

        // then
        assertThat(response).isNotNull();
        assertThat(response.parseSummary()).isNotNull();
        assertThat(response.parseSummary().totalRows()).isEqualTo(10);
        assertThat(response.parseSummary().successfulRows()).isEqualTo(5);
        assertThat(response.parseSummary().failedRows()).isEqualTo(5);

        assertThat(response.stagingResult()).isNull();
        assertThat(response.getStagingSessionId()).isNull();
    }

    @Test
    @DisplayName("Should deserialize UploadCsvResponse with missing stagingResult field")
    void shouldDeserializeUploadCsvResponseWithMissingStagingResultField() throws Exception {
        // given - JSON response without stagingResult field at all
        String json = """
            {
                "parseSummary": {
                    "totalRows": 100,
                    "successfulRows": 100,
                    "failedRows": 0
                }
            }
            """;

        // when
        BankDataIngestionClient.UploadCsvResponse response =
            objectMapper.readValue(json, BankDataIngestionClient.UploadCsvResponse.class);

        // then
        assertThat(response).isNotNull();
        assertThat(response.parseSummary()).isNotNull();
        assertThat(response.stagingResult()).isNull();
        assertThat(response.getStagingSessionId()).isNull();
    }

    @Test
    @DisplayName("Should deserialize StagingResult correctly")
    void shouldDeserializeStagingResult() throws Exception {
        // given
        String json = """
            {
                "stagingSessionId": "session-456",
                "cashFlowId": "CF999",
                "status": "READY_TO_IMPORT"
            }
            """;

        // when
        BankDataIngestionClient.StagingResult result =
            objectMapper.readValue(json, BankDataIngestionClient.StagingResult.class);

        // then
        assertThat(result.stagingSessionId()).isEqualTo("session-456");
        assertThat(result.cashFlowId()).isEqualTo("CF999");
        assertThat(result.status()).isEqualTo("READY_TO_IMPORT");
    }

    @Test
    @DisplayName("Should deserialize ParseSummary correctly")
    void shouldDeserializeParseSummary() throws Exception {
        // given
        String json = """
            {
                "totalRows": 500,
                "successfulRows": 490,
                "failedRows": 10
            }
            """;

        // when
        BankDataIngestionClient.ParseSummary summary =
            objectMapper.readValue(json, BankDataIngestionClient.ParseSummary.class);

        // then
        assertThat(summary.totalRows()).isEqualTo(500);
        assertThat(summary.successfulRows()).isEqualTo(490);
        assertThat(summary.failedRows()).isEqualTo(10);
    }
}
