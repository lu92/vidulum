package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
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
    private Date validFrom;
    private Date validTo;
    private boolean archived;
    private String origin;

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
                .validFrom(category.getValidFrom() != null ? Date.from(category.getValidFrom().toInstant()) : null)
                .validTo(category.getValidTo() != null ? Date.from(category.getValidTo().toInstant()) : null)
                .archived(category.isArchived())
                .origin(category.getOrigin() != null ? category.getOrigin().name() : CategoryOrigin.USER_CREATED.name())
                .build();
    }

    public Category toDomain() {
        List<Category> subCategoryDomains = subCategories != null
                ? subCategories.stream()
                    .map(CategoryEntity::toDomain)
                    .collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<>();

        return Category.builder()
                .categoryName(new CategoryName(categoryName))
                .budgeting(budgeting != null ? budgeting.toDomain() : null)
                .subCategories(subCategoryDomains)
                .isModifiable(isModifiable)
                .validFrom(validFrom != null ? ZonedDateTime.ofInstant(validFrom.toInstant(), ZoneOffset.UTC) : null)
                .validTo(validTo != null ? ZonedDateTime.ofInstant(validTo.toInstant(), ZoneOffset.UTC) : null)
                .archived(archived)
                .origin(origin != null ? CategoryOrigin.valueOf(origin) : CategoryOrigin.USER_CREATED)
                .build();
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
