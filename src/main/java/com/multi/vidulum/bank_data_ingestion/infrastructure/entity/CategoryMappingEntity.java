package com.multi.vidulum.bank_data_ingestion.infrastructure.entity;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.domain.MappingId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

@Builder
@Getter
@ToString
@Document("category_mappings")
@CompoundIndex(name = "cashflow_bank_category_type_idx",
               def = "{'cashFlowId': 1, 'bankCategoryName': 1, 'categoryType': 1}",
               unique = true)
public class CategoryMappingEntity {

    @Id
    private String mappingId;

    @Indexed
    private String cashFlowId;

    private String bankCategoryName;

    private String targetCategoryName;

    private String parentCategoryName;

    private Type categoryType;

    private MappingAction action;

    private Date createdAt;

    private Date updatedAt;

    public static CategoryMappingEntity fromDomain(CategoryMapping mapping) {
        return CategoryMappingEntity.builder()
                .mappingId(mapping.mappingId().id())
                .cashFlowId(mapping.cashFlowId().id())
                .bankCategoryName(mapping.bankCategoryName())
                .targetCategoryName(mapping.targetCategoryName().name())
                .parentCategoryName(mapping.parentCategoryName() != null ? mapping.parentCategoryName().name() : null)
                .categoryType(mapping.categoryType())
                .action(mapping.action())
                .createdAt(Date.from(mapping.createdAt().toInstant()))
                .updatedAt(Date.from(mapping.updatedAt().toInstant()))
                .build();
    }

    public CategoryMapping toDomain() {
        return new CategoryMapping(
                MappingId.of(mappingId),
                new CashFlowId(cashFlowId),
                bankCategoryName,
                new CategoryName(targetCategoryName),
                parentCategoryName != null ? new CategoryName(parentCategoryName) : null,
                categoryType,
                action,
                ZonedDateTime.ofInstant(createdAt.toInstant(), ZoneOffset.UTC),
                ZonedDateTime.ofInstant(updatedAt.toInstant(), ZoneOffset.UTC)
        );
    }
}
