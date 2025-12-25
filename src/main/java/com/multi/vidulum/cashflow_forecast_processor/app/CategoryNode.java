package com.multi.vidulum.cashflow_forecast_processor.app;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.multi.vidulum.cashflow.domain.CategoryName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Getter
@NoArgsConstructor
public class CategoryNode {
    @JsonIgnore
    private CategoryNode parentCategoryNode;
    private CategoryName categoryName;
    private List<CategoryNode> nodes;
    @Setter
    private Budgeting budgeting;

    public CategoryNode(CategoryNode parentCategoryNode, CategoryName categoryName, List<CategoryNode> nodes) {
        this(parentCategoryNode, categoryName, nodes, null);
    }

    public CategoryNode(CategoryNode parentCategoryNode, CategoryName categoryName, List<CategoryNode> nodes, Budgeting budgeting) {
        this.parentCategoryNode = parentCategoryNode;
        this.categoryName = categoryName;
        this.nodes = nodes;
        this.budgeting = budgeting;
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
