package com.multi.vidulum.bank_data_ingestion.mappings.infrastructure.entity;

import com.multi.vidulum.bank_data_ingestion.mappings.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.MappingId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.cashflow.api.Type;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.*;

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

    /** AI confidence score (0-100) when mapping was created from AI suggestion. Null for manual mappings. */
    private Integer confidence;

    private Date createdAt;

    private Date updatedAt;

    /**
     * Turns every save into a conditional update (task F57).
     * {@code ConfigureCategoryMappingCommandHandler} loads an existing mapping, rebuilds it through
     * {@code CategoryMapping.update} and stores it - so two requests remapping the same bank
     * category used to have the later one win in silence.
     */
    @Version
    private Long version;

    public static CategoryMappingEntity fromDomain(CategoryMapping mapping) {
        return CategoryMappingEntity.builder()
                .mappingId(mapping.mappingId().id())
                .cashFlowId(mapping.cashFlowId().id())
                .bankCategoryName(mapping.bankCategoryName())
                .targetCategoryName(mapping.targetCategoryName().name())
                .parentCategoryName(mapping.parentCategoryName() != null ? mapping.parentCategoryName().name() : null)
                .categoryType(mapping.categoryType())
                .action(mapping.action())
                .confidence(mapping.confidence())
                .createdAt(Date.from(mapping.createdAt().toInstant()))
                .updatedAt(Date.from(mapping.updatedAt().toInstant()))
                .version(mapping.version())
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
                confidence,
                ZonedDateTime.ofInstant(createdAt.toInstant(), ZoneOffset.UTC),
                ZonedDateTime.ofInstant(updatedAt.toInstant(), ZoneOffset.UTC),
                version
        );
    }
}
