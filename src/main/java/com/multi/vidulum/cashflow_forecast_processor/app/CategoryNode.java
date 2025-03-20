package com.multi.vidulum.cashflow_forecast_processor.app;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.multi.vidulum.cashflow.domain.CategoryName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CategoryNode(
        @JsonIgnore
        CategoryNode parentCategoryNode,
        CategoryName categoryName,
        List<CategoryNode> nodes
) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CategoryNode that = (CategoryNode) o;
        return Objects.equals(nodes.stream().map(CategoryNode::categoryName).toList(), that.nodes.stream().map(CategoryNode::categoryName).toList()) && Objects.equals(categoryName, that.categoryName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(categoryName, nodes);
    }

    @Override
    public String toString() {
        return "CategoryNode{" +
                "parentCategoryNode=" + Optional.ofNullable(parentCategoryNode).map(CategoryNode::categoryName).map(CategoryName::name).orElse(null) +
                ", categoryName=" + categoryName +
                ", nodes=[" + nodes.stream().map(CategoryNode::categoryName).map(CategoryName::name).toList() +
                "]}";
    }
}
