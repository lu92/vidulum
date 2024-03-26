package com.multi.vidulum.cashflow.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class Category {
    CategoryId categoryId;
    CategoryName categoryName;
    List<Category> subCategories;
    boolean isModifiable;

    void rename(CategoryName newName) {
        categoryName = newName;
    }
}
