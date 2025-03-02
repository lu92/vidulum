package com.multi.vidulum.cashflow_forecast_processor.app;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.multi.vidulum.cashflow.domain.CategoryName;

import java.util.List;
import java.util.Optional;

public record CategoryNode(
        @JsonIgnore
        CategoryNode parentCategoryNode,
        CategoryName categoryName,
        List<CategoryNode> nodes
) {

    @Override
    public String toString() {
        return "CategoryNode{" +
                "parentCategoryNode=" + Optional.ofNullable(parentCategoryNode).map(CategoryNode::categoryName).map(CategoryName::name).orElse(null) +
                ", categoryName=" + categoryName +
                ", nodes=[" + nodes.stream().map(CategoryNode::categoryName).map(CategoryName::name).toList() +
                "]}";
    }
}
