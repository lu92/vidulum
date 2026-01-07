package com.multi.vidulum.cashflow.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Category for organizing cash changes (transactions) in a CashFlow.
 * <p>
 * Categories support:
 * <ul>
 *   <li><b>Hierarchical structure</b> - categories can have subcategories</li>
 *   <li><b>Validity periods</b> - categories can be active only within a date range</li>
 *   <li><b>Archiving</b> - categories can be archived to hide from new transactions while preserving history</li>
 *   <li><b>Origin tracking</b> - distinguishes system, imported, and user-created categories</li>
 * </ul>
 * <p>
 * <b>Category lifecycle:</b>
 * <pre>
 * Created (validFrom=now, validTo=null, archived=false)
 *    ↓
 * Active (visible in category selection for transactions)
 *    ↓
 * Archived (archived=true, validTo=archiveDate)
 *    ↓
 * Hidden from new transactions, but visible in historical data
 * </pre>
 *
 * @see CategoryOrigin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    CategoryName categoryName;
    Budgeting budgeting; // nullable

    List<Category> subCategories;

    /**
     * Whether the category can be modified by the user.
     * System categories (e.g., "Uncategorized") are not modifiable.
     */
    boolean isModifiable;

    /**
     * Start date of category validity (inclusive).
     * <p>
     * If null, the category is valid from the beginning of time.
     * Transactions with dueDate before validFrom should not use this category
     * (unless it's a historical import where the category was already in use).
     */
    ZonedDateTime validFrom;

    /**
     * End date of category validity (inclusive).
     * <p>
     * If null, the category is valid indefinitely (no end date).
     * When a category is archived, validTo is set to the archive timestamp.
     * Transactions with dueDate after validTo should not use this category.
     */
    ZonedDateTime validTo;

    /**
     * Whether the category is archived.
     * <p>
     * Archived categories:
     * <ul>
     *   <li>Are hidden from category selection in UI</li>
     *   <li>Cannot be used for new transactions</li>
     *   <li>Remain visible in historical transactions that used them</li>
     *   <li>Can be unarchived to restore full functionality</li>
     * </ul>
     */
    boolean archived;

    /**
     * Origin of this category - how it was created.
     * <p>
     * Used to apply different rules:
     * <ul>
     *   <li>SYSTEM categories cannot be deleted or archived</li>
     *   <li>IMPORTED categories were created during bank statement import</li>
     *   <li>USER_CREATED categories have full user control</li>
     * </ul>
     */
    CategoryOrigin origin;

    /**
     * Legacy constructor for backward compatibility.
     * Creates a category with default validity (always valid, not archived, USER_CREATED origin).
     */
    public Category(CategoryName categoryName, Budgeting budgeting, List<Category> subCategories, boolean isModifiable) {
        this.categoryName = categoryName;
        this.budgeting = budgeting;
        this.subCategories = subCategories;
        this.isModifiable = isModifiable;
        this.validFrom = null;  // valid from beginning
        this.validTo = null;    // valid indefinitely
        this.archived = false;
        this.origin = CategoryOrigin.USER_CREATED;
    }

    /**
     * Legacy constructor for backward compatibility.
     */
    public Category(CategoryName categoryName, List<Category> subCategories, boolean isModifiable) {
        this(categoryName, null, subCategories, isModifiable);
    }

    void rename(CategoryName newName) {
        categoryName = newName;
    }

    /**
     * Archives this category, setting validTo to the archive timestamp.
     *
     * @param archiveTimestamp the timestamp when the category is being archived
     * @throws IllegalStateException if category origin is SYSTEM (cannot archive system categories)
     */
    public void archive(ZonedDateTime archiveTimestamp) {
        if (origin == CategoryOrigin.SYSTEM) {
            throw new IllegalStateException("Cannot archive system category: " + categoryName.name());
        }
        this.archived = true;
        this.validTo = archiveTimestamp;
    }

    /**
     * Unarchives this category, clearing the validTo date.
     */
    public void unarchive() {
        this.archived = false;
        this.validTo = null;
    }

    /**
     * Checks if this category is valid (active) at the given date.
     *
     * @param date the date to check validity for
     * @return true if the category is valid at the given date and not archived
     */
    public boolean isValidAt(ZonedDateTime date) {
        if (archived) {
            return false;
        }
        if (validFrom != null && date.isBefore(validFrom)) {
            return false;
        }
        if (validTo != null && date.isAfter(validTo)) {
            return false;
        }
        return true;
    }

    /**
     * Checks if this category can be used for new transactions.
     * A category can be used if it's not archived.
     *
     * @return true if the category can be used for new transactions
     */
    public boolean isActive() {
        return !archived;
    }

    /**
     * Creates the system "Uncategorized" category.
     * This is a default category that cannot be modified or archived.
     *
     * @return a new Uncategorized category with SYSTEM origin
     */
    public static Category createUncategorized() {
        return Category.builder()
                .categoryName(new CategoryName("Uncategorized"))
                .budgeting(null)
                .subCategories(new java.util.LinkedList<>())
                .isModifiable(false)
                .validFrom(null)
                .validTo(null)
                .archived(false)
                .origin(CategoryOrigin.SYSTEM)
                .build();
    }
}

