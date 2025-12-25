package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.CategoryName;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Builder
@Getter
@ToString
public class CategoryEntity {

    private String categoryName;
    private BudgetingEntity budgeting;
    private List<CategoryEntity> subCategories;
    private boolean isModifiable;

    public static CategoryEntity fromDomain(Category category) {
        if (category == null) {
            return null;
        }

        List<CategoryEntity> subCategoryEntities = category.getSubCategories() != null
                ? category.getSubCategories().stream()
                    .map(CategoryEntity::fromDomain)
                    .collect(Collectors.toList())
                : new LinkedList<>();

        return CategoryEntity.builder()
                .categoryName(category.getCategoryName().name())
                .budgeting(BudgetingEntity.fromDomain(category.getBudgeting()))
                .subCategories(subCategoryEntities)
                .isModifiable(category.isModifiable())
                .build();
    }

    public Category toDomain() {
        List<Category> subCategoryDomains = subCategories != null
                ? subCategories.stream()
                    .map(CategoryEntity::toDomain)
                    .collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<>();

        return new Category(
                new CategoryName(categoryName),
                budgeting != null ? budgeting.toDomain() : null,
                subCategoryDomains,
                isModifiable
        );
    }

    public static List<CategoryEntity> fromDomainList(List<Category> categories) {
        if (categories == null) {
            return new LinkedList<>();
        }
        return categories.stream()
                .map(CategoryEntity::fromDomain)
                .collect(Collectors.toList());
    }

    public static List<Category> toDomainList(List<CategoryEntity> entities) {
        if (entities == null) {
            return new LinkedList<>();
        }
        return entities.stream()
                .map(CategoryEntity::toDomain)
                .collect(Collectors.toCollection(LinkedList::new));
    }
}
