package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.Currency;
import org.iban4j.IbanFormatException;
import org.iban4j.IbanUtil;
import org.iban4j.InvalidCheckDigitException;
import org.iban4j.UnsupportedCountryException;

/**
 * An account number as this module holds it: the IBAN as text, and what it is denominated in.
 *
 * <p><b>Why not cashflow's {@code BankAccountNumber}.</b> This module used to borrow it, which was
 * the last import keeping it inside the cashflow artifact - and borrowing it was never what the
 * code wanted. Every reader here immediately flattens it:
 * {@code bankAccountNumber().fetchRawIban()} and {@code .denomination().getId()}, in all seven
 * places. The entity stores two strings. Nothing in this module uses the validated value object as
 * a value object.
 *
 * <p><b>And borrowing it had a cost that was easy to miss.</b> Cashflow's version validates the
 * IBAN through iban4j on construction, and {@code UserFinancialProfileEntity} rebuilt it with
 * {@code BankAccountNumber.fromIban(...)} on every read - so an account whose stored IBAN iban4j
 * would reject today made the whole profile unreadable. Storage re-validating what it stored is a
 * failure mode with no upside: the number was validated by cashflow when the account was created,
 * which is where validation belongs.
 *
 * <p>So this record does not validate. It is a carrier, and the authority on whether an IBAN is
 * well formed stays in cashflow, at the point of entry.
 */
public record OwnedAccountNumber(String iban, Currency denomination) {

    /**
     * Builds one from text a person supplied, validating the IBAN first.
     *
     * <p><b>This module validates its own input, and that is not duplication.</b> Accounts arrive
     * here two ways: carried inside a cash flow's creation event, already validated by cashflow,
     * and typed by a user into {@code POST /user-financial-profile/accounts} - its own door, which
     * needs its own lock. Borrowing cashflow's value object for the second case is what made this
     * module unextractable, and it also validated the first case twice.
     *
     * <p>{@link #OwnedAccountNumber} itself does not validate: a number read back from storage was
     * checked when it was accepted, and re-checking it only invents a way for old data to become
     * unreadable.
     */
    public static OwnedAccountNumber fromIban(String rawIban, Currency denomination) {
        if (rawIban == null || rawIban.isBlank()) {
            throw new InvalidOwnedAccountNumberException("IBAN cannot be null or blank", rawIban);
        }
        String normalized = rawIban.replace(" ", "").toUpperCase();
        try {
            IbanUtil.validate(normalized);
        } catch (IbanFormatException | InvalidCheckDigitException | UnsupportedCountryException e) {
            throw new InvalidOwnedAccountNumberException(e.getMessage(), rawIban);
        }
        return new OwnedAccountNumber(normalized, denomination);
    }
}
