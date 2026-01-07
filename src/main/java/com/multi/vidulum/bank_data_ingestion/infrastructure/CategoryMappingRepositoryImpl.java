package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMappingRepository;
import com.multi.vidulum.bank_data_ingestion.domain.MappingId;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.CategoryMappingEntity;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@AllArgsConstructor
public class CategoryMappingRepositoryImpl implements CategoryMappingRepository {

    private final CategoryMappingMongoRepository mongoRepository;

    @Override
    public CategoryMapping save(CategoryMapping mapping) {
        CategoryMappingEntity entity = CategoryMappingEntity.fromDomain(mapping);
        CategoryMappingEntity saved = mongoRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<CategoryMapping> findById(MappingId mappingId) {
        return mongoRepository.findByMappingId(mappingId.id())
                .map(CategoryMappingEntity::toDomain);
    }

    @Override
    public Optional<CategoryMapping> findByCashFlowIdAndBankCategoryNameAndCategoryType(
            CashFlowId cashFlowId,
            String bankCategoryName,
            Type categoryType
    ) {
        return mongoRepository.findByCashFlowIdAndBankCategoryNameAndCategoryType(
                cashFlowId.id(),
                bankCategoryName,
                categoryType
        ).map(CategoryMappingEntity::toDomain);
    }

    @Override
    public List<CategoryMapping> findByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.findByCashFlowId(cashFlowId.id())
                .stream()
                .map(CategoryMappingEntity::toDomain)
                .toList();
    }

    @Override
    public void deleteById(MappingId mappingId) {
        mongoRepository.deleteByMappingId(mappingId.id());
    }

    @Override
    public long deleteByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.deleteByCashFlowId(cashFlowId.id());
    }
}
