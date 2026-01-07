package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.CategoryMappingEntity;
import com.multi.vidulum.cashflow.domain.Type;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryMappingMongoRepository extends MongoRepository<CategoryMappingEntity, String> {

    Optional<CategoryMappingEntity> findByMappingId(String mappingId);

    Optional<CategoryMappingEntity> findByCashFlowIdAndBankCategoryNameAndCategoryType(
            String cashFlowId,
            String bankCategoryName,
            Type categoryType
    );

    List<CategoryMappingEntity> findByCashFlowId(String cashFlowId);

    void deleteByMappingId(String mappingId);

    long deleteByCashFlowId(String cashFlowId);
}
