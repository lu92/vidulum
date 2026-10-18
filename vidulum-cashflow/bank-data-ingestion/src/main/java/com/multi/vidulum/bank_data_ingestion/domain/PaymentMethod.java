package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Payment method used for a transaction.
 * This is HOW the payment was made, not WHAT was purchased.
 *
 * Examples:
 * - CARD: "TRANSAKCJA KARTĄ PŁATNICZĄ", "Płatność kartą"
 * - TRANSFER: "PRZELEW", "Przelewy wychodzące", "Przelewy przychodzące"
 * - BLIK: "PŁATNOŚĆ BLIK", "PRZELEW BLIK"
 * - DIRECT_DEBIT: "POLECENIE ZAPŁATY", "OBCIĄŻENIE Z TYTUŁU POLECENIA ZAPŁATY"
 * - STANDING_ORDER: "ZLECENIE STAŁE"
 * - CASH: "WPŁATA GOTÓWKOWA", "WYPŁATA Z BANKOMATU"
 * - OTHER: Unknown or unrecognized payment method
 */
public enum PaymentMethod {
    CARD,           // Card payment (debit/credit)
    TRANSFER,       // Bank transfer (wire transfer)
    BLIK,           // BLIK payment (Polish instant payment)
    DIRECT_DEBIT,   // Direct debit / standing order debit
    STANDING_ORDER, // Recurring standing order
    CASH,           // Cash deposit/withdrawal
    OTHER;          // Unknown or other method

    /**
     * Attempts to parse payment method from Polish bank operation type.
     * Returns OTHER if not recognized.
     */
    public static PaymentMethod fromPolishBankType(String operationType) {
        if (operationType == null || operationType.isBlank()) {
            return OTHER;
        }

        String upper = operationType.toUpperCase();

        // Card payments
        if (upper.contains("KART") || upper.contains("CARD")) {
            return CARD;
        }

        // BLIK payments
        if (upper.contains("BLIK")) {
            return BLIK;
        }

        // Direct debit
        if (upper.contains("POLECENIE ZAPŁATY") || upper.contains("POLECENIA ZAPŁATY")) {
            return DIRECT_DEBIT;
        }

        // Standing order
        if (upper.contains("ZLECENIE STAŁE") || upper.contains("STANDING ORDER")) {
            return STANDING_ORDER;
        }

        // Cash
        if (upper.contains("GOTÓWK") || upper.contains("BANKOMAT") || upper.contains("ATM") ||
            upper.contains("WPŁATA") || upper.contains("WYPŁATA")) {
            return CASH;
        }

        // Transfer (most generic - check last)
        if (upper.contains("PRZELEW") || upper.contains("TRANSFER") || upper.contains("ELIXIR")) {
            return TRANSFER;
        }

        return OTHER;
    }
}
