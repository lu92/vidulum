package com.multi.vidulum.cashflow.domain.snapshots;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public record CashFlowSnapshot(
        CashFlowId cashFlowId,
        UserId userId,
        Name name,
        Description description,
        BankAccount bankAccount,
        CashFlow.CashFlowStatus status,
        Map<CashChangeId, CashChangeSnapshot> cashChanges,
        YearMonth activePeriod,
        List<Category> inflowCategories,
        List<Category> outflowCategories,
        ZonedDateTime created,
        ZonedDateTime lastModification) implements EntitySnapshot<CashFlowId> {

//    public CategoryId findCategoryId(CategoryName categoryName) {
//        return Stream.concat(inflowCategories.stream(), outflowCategories.stream())
//                .filter(category -> category.getCategoryName().equals(categoryName))
//                .map(Category::getCategoryId)
//                .findFirst()
//                .orElseThrow(() -> new IllegalArgumentException("Invalid category-name"));
//    }

    @Override
    public CashFlowId id() {
        return cashFlowId;
    }
}
