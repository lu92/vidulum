package com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import com.multi.vidulum.cashflow_forecast_processor.app.Budgeting;
import com.multi.vidulum.cashflow_forecast_processor.app.CategoryNode;
import com.multi.vidulum.cashflow_forecast_processor.app.CurrentCategoryStructure;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class CurrentCategoryStructureEntity {

    private List<CategoryNodeEntity> inflowCategoryStructure;
    private List<CategoryNodeEntity> outflowCategoryStructure;
    private Date lastUpdated;

    public static CurrentCategoryStructureEntity fromDomain(CurrentCategoryStructure structure) {
        if (structure == null) {
            return null;
        }

        List<CategoryNodeEntity> inflowNodes = structure.inflowCategoryStructure() != null
                ? structure.inflowCategoryStructure().stream()
                    .map(node -> CategoryNodeEntity.fromDomain(node, null))
                    .collect(Collectors.toList())
                : new LinkedList<>();

        List<CategoryNodeEntity> outflowNodes = structure.outflowCategoryStructure() != null
                ? structure.outflowCategoryStructure().stream()
                    .map(node -> CategoryNodeEntity.fromDomain(node, null))
                    .collect(Collectors.toList())
                : new LinkedList<>();

        return CurrentCategoryStructureEntity.builder()
                .inflowCategoryStructure(inflowNodes)
                .outflowCategoryStructure(outflowNodes)
                .lastUpdated(structure.lastUpdated() != null ? Date.from(structure.lastUpdated().toInstant()) : null)
                .build();
    }

    public CurrentCategoryStructure toDomain() {
        List<CategoryNode> inflowNodes = inflowCategoryStructure != null
                ? inflowCategoryStructure.stream()
                    .map(node -> node.toDomain(null))
                    .collect(Collectors.toList())
                : new LinkedList<>();

        List<CategoryNode> outflowNodes = outflowCategoryStructure != null
                ? outflowCategoryStructure.stream()
                    .map(node -> node.toDomain(null))
                    .collect(Collectors.toList())
                : new LinkedList<>();

        ZonedDateTime lastUpdatedDateTime = lastUpdated != null
                ? ZonedDateTime.ofInstant(lastUpdated.toInstant(), ZoneOffset.UTC)
                : null;

        return new CurrentCategoryStructure(inflowNodes, outflowNodes, lastUpdatedDateTime);
    }

    @Data
    @Builder
    public static class CategoryNodeEntity {
        private String parentCategoryName;
        private String categoryName;
        private List<CategoryNodeEntity> nodes;
        private BudgetingEntity budgeting;
        private boolean archived;
        private Date validFrom;
        private Date validTo;
        private String origin;

        public static CategoryNodeEntity fromDomain(CategoryNode node, String parentName) {
            if (node == null) {
                return null;
            }

            List<CategoryNodeEntity> childNodes = node.getNodes() != null
                    ? node.getNodes().stream()
                        .map(child -> CategoryNodeEntity.fromDomain(child, node.getCategoryName() != null ? node.getCategoryName().name() : null))
                        .collect(Collectors.toList())
                    : new LinkedList<>();

            return CategoryNodeEntity.builder()
                    .parentCategoryName(parentName)
                    .categoryName(node.getCategoryName() != null ? node.getCategoryName().name() : null)
                    .nodes(childNodes)
                    .budgeting(BudgetingEntity.fromDomain(node.getBudgeting()))
                    .archived(node.isArchived())
                    .validFrom(node.getValidFrom() != null ? Date.from(node.getValidFrom().toInstant()) : null)
                    .validTo(node.getValidTo() != null ? Date.from(node.getValidTo().toInstant()) : null)
                    .origin(node.getOrigin() != null ? node.getOrigin().name() : null)
                    .build();
        }

        public CategoryNode toDomain(CategoryNode parentNode) {
            List<CategoryNode> childNodes = new LinkedList<>();

            CategoryNode thisNode = new CategoryNode(
                    parentNode,
                    categoryName != null ? new CategoryName(categoryName) : null,
                    childNodes,
                    budgeting != null ? budgeting.toDomain() : null,
                    archived,
                    validFrom != null ? ZonedDateTime.ofInstant(validFrom.toInstant(), ZoneOffset.UTC) : null,
                    validTo != null ? ZonedDateTime.ofInstant(validTo.toInstant(), ZoneOffset.UTC) : null,
                    origin != null ? CategoryOrigin.valueOf(origin) : CategoryOrigin.USER_CREATED
            );

            if (nodes != null) {
                childNodes.addAll(nodes.stream()
                        .map(child -> child.toDomain(thisNode))
                        .collect(Collectors.toList()));
            }

            return thisNode;
        }

        @Data
        @Builder
        public static class BudgetingEntity {
            private Money budget;
            private Date created;
            private Date lastUpdated;

            public static BudgetingEntity fromDomain(Budgeting budgeting) {
                if (budgeting == null) {
                    return null;
                }
                return BudgetingEntity.builder()
                        .budget(budgeting.budget())
                        .created(budgeting.created() != null ? Date.from(budgeting.created().toInstant()) : null)
                        .lastUpdated(budgeting.lastUpdated() != null ? Date.from(budgeting.lastUpdated().toInstant()) : null)
                        .build();
            }

            public Budgeting toDomain() {
                ZonedDateTime createdDateTime = created != null
                        ? ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC)
                        : null;
                ZonedDateTime lastUpdatedDateTime = lastUpdated != null
                        ? ZonedDateTime.ofInstant(lastUpdated.toInstant(), ZoneOffset.UTC)
                        : null;
                return new Budgeting(budget, createdDateTime, lastUpdatedDateTime);
            }
        }
    }
}
