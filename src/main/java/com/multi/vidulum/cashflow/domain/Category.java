package com.multi.vidulum.cashflow.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    CategoryName categoryName;
    Budgeting budgeting; //nullable
    List<Category> subCategories;
    boolean isModifiable;

    public Category(CategoryName categoryName, List<Category> subCategories, boolean isModifiable) {
        this(categoryName, null, subCategories, isModifiable);
    }

    void rename(CategoryName newName) {
        categoryName = newName;
    }
}
