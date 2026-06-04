package com.multi.vidulum.user_financial_profile.infrastructure;

import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.user_financial_profile.domain.AccountSource;
import com.multi.vidulum.user_financial_profile.domain.AccountStatus;
import com.multi.vidulum.user_financial_profile.domain.OwnedBankAccount;
import com.multi.vidulum.user_financial_profile.domain.UserFinancialProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("user_financial_profiles")
public class UserFinancialProfileEntity {

    @Id
    private String userId;
    private List<OwnedBankAccountDocument> ownedAccounts;
    private Date created;
    private Date lastModified;

    public static UserFinancialProfileEntity from(UserFinancialProfile profile) {
        List<OwnedBankAccountDocument> docs = profile.getOwnedAccounts().stream()
                .map(OwnedBankAccountDocument::from)
                .collect(Collectors.toList());
        return UserFinancialProfileEntity.builder()
                .userId(profile.getUserId().getId())
                .ownedAccounts(docs)
                .created(toDate(profile.getCreated()))
                .lastModified(toDate(profile.getLastModified()))
                .build();
    }

    public UserFinancialProfile toDomain() {
        List<OwnedBankAccount> accounts = ownedAccounts.stream()
                .map(OwnedBankAccountDocument::toDomain)
                .collect(Collectors.toList());
        return UserFinancialProfile.builder()
                .userId(UserId.of(userId))
                .ownedAccounts(accounts)
                .created(toZonedDateTime(created))
                .lastModified(toZonedDateTime(lastModified))
                .build();
    }

    private static Date toDate(ZonedDateTime zdt) {
        return zdt != null ? Date.from(zdt.toInstant()) : null;
    }

    private static ZonedDateTime toZonedDateTime(Date date) {
        return date != null ? ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC) : null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnedBankAccountDocument {
        private String iban;
        private String currency;
        private String bankName;
        private String label;
        private String status;
        private String source;
        private String linkedCashFlowId;
        private Date addedAt;
        private Date closedAt;

        public static OwnedBankAccountDocument from(OwnedBankAccount account) {
            return OwnedBankAccountDocument.builder()
                    .iban(account.bankAccountNumber().fetchRawIban())
                    .currency(account.bankAccountNumber().denomination().getId())
                    .bankName(account.bankName() != null ? account.bankName().name() : null)
                    .label(account.label())
                    .status(account.status().name())
                    .source(account.source().name())
                    .linkedCashFlowId(account.linkedCashFlowId() != null ? account.linkedCashFlowId().id() : null)
                    .addedAt(toDate(account.addedAt()))
                    .closedAt(toDate(account.closedAt()))
                    .build();
        }

        public OwnedBankAccount toDomain() {
            return new OwnedBankAccount(
                    BankAccountNumber.fromIban(iban, Currency.of(currency)),
                    bankName != null ? new BankName(bankName) : null,
                    label,
                    AccountStatus.valueOf(status),
                    AccountSource.valueOf(source),
                    linkedCashFlowId != null ? CashFlowId.of(linkedCashFlowId) : null,
                    toZonedDateTime(addedAt),
                    toZonedDateTime(closedAt)
            );
        }
    }
}
