package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CategoryName;

/**
 * A category name together with the direction it belongs to.
 *
 * <p>The pair exists because a cash flow may use one word on both sides - "Transfer" as income and
 * "Transfer" as expense are two different categories, and anything deciding whether a category
 * needs creating has to say which one it means. {@code CashFlowSkeletonView} keeps the two trees
 * apart for the same reason; this is what a flattened view of both looks like without losing it.
 *
 * <p>Replaces {@code CashFlowInfo.TypedCategoryKey}, which carried a bare {@code String} - so the
 * one consumer comparing these pairs was comparing a name read out of a bank statement against a
 * category by string equality.
 */
public record TypedCategoryName(CategoryName name, Type type) {
}
