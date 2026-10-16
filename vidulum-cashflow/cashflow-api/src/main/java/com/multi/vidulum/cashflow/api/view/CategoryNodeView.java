package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.common.CategoryName;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One category in the skeleton, with whatever hangs below it.
 *
 * <p>Four fields out of the nine the aggregate's {@code Category} carries. What is left out -
 * budgeting, modifiability, validity dates, origin - is not structure; the shape that
 * {@code bank_data_ingestion} built for itself keeps exactly these, and its HTTP mapper discards
 * the rest off the wire.
 *
 * @param parentName   {@code null} at the root. Redundant against the tree, and deliberately kept
 *                     because the flat lookups in categorisation are written against it - so the
 *                     constructor below checks that it agrees with the tree, since a redundancy
 *                     nobody validates is a field that will eventually lie.
 * @param archived     an archived category still appears here: it cannot take new transactions but
 *                     historical ones point at it, so an importer has to know it exists.
 */
public record CategoryNodeView(
        CategoryName name,
        CategoryName parentName,
        boolean archived,
        List<CategoryNodeView> subCategories) {

    public CategoryNodeView {
        Objects.requireNonNull(name, "name is required");
        subCategories = subCategories == null ? List.of() : List.copyOf(subCategories);
        for (CategoryNodeView child : subCategories) {
            if (!name.equals(child.parentName())) {
                throw new IllegalArgumentException(
                        "Category [%s] lists [%s] as a child, but that child's parent is [%s]"
                                .formatted(name.name(), child.name().name(),
                                        child.parentName() == null ? "none" : child.parentName().name()));
            }
        }
    }

    /** This node and everything under it, depth first. */
    public Stream<CategoryNodeView> flatten() {
        return Stream.concat(Stream.of(this), subCategories.stream().flatMap(CategoryNodeView::flatten));
    }

    public boolean isRoot() {
        return parentName == null;
    }
}
