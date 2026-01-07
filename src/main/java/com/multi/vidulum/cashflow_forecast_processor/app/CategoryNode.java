package com.multi.vidulum.cashflow_forecast_processor.app;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a category node in the forecast statement's category structure.
 * Contains archiving metadata for UI filtering (active vs archived categories).
 */
@Getter
@NoArgsConstructor
public class CategoryNode {
    @JsonIgnore
    private CategoryNode parentCategoryNode;
    private CategoryName categoryName;
    private List<CategoryNode> nodes;
    @Setter
    private Budgeting budgeting;

    /** Whether this category is archived (hidden from new transaction creation) */
    @Setter
    private boolean archived;

    /** Start date of validity (null = valid from the beginning) */
    @Setter
    private ZonedDateTime validFrom;

    /** End date of validity (set when archived) */
    @Setter
    private ZonedDateTime validTo;

    /** Origin of this category (SYSTEM, IMPORTED, USER_CREATED) */
    @Setter
    private CategoryOrigin origin;

    public CategoryNode(CategoryNode parentCategoryNode, CategoryName categoryName, List<CategoryNode> nodes) {
        this(parentCategoryNode, categoryName, nodes, null);
    }

    public CategoryNode(CategoryNode parentCategoryNode, CategoryName categoryName, List<CategoryNode> nodes, Budgeting budgeting) {
        this(parentCategoryNode, categoryName, nodes, budgeting, false, null, null, CategoryOrigin.USER_CREATED);
    }

    public CategoryNode(CategoryNode parentCategoryNode, CategoryName categoryName, List<CategoryNode> nodes,
                        Budgeting budgeting, boolean archived, ZonedDateTime validFrom, ZonedDateTime validTo, CategoryOrigin origin) {
        this.parentCategoryNode = parentCategoryNode;
        this.categoryName = categoryName;
        this.nodes = nodes;
        this.budgeting = budgeting;
        this.archived = archived;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.origin = origin;
    }

    /**
     * Archive this category, marking it as hidden for new transactions.
     */
    public void archive(ZonedDateTime archiveTimestamp) {
        this.archived = true;
        this.validTo = archiveTimestamp;
    }

    /**
     * Unarchive this category, making it available for new transactions again.
     */
    public void unarchive() {
        this.archived = false;
        this.validTo = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CategoryNode that = (CategoryNode) o;
        return Objects.equals(nodes.stream().map(CategoryNode::getCategoryName).toList(), that.nodes.stream().map(CategoryNode::getCategoryName).toList())
                && Objects.equals(categoryName, that.categoryName)
                && Objects.equals(budgeting, that.budgeting);
    }

    @Override
    public int hashCode() {
        return Objects.hash(categoryName, nodes, budgeting);
    }

    @Override
    public String toString() {
        return "CategoryNode{" +
                "parentCategoryNode=" + Optional.ofNullable(parentCategoryNode).map(CategoryNode::getCategoryName).map(CategoryName::name).orElse(null) +
                ", categoryName=" + categoryName +
                ", nodes=[" + nodes.stream().map(CategoryNode::getCategoryName).map(CategoryName::name).toList() +
                "], budgeting=" + budgeting +
                "}";
    }
}
