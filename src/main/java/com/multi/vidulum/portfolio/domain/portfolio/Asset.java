package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.Valuable;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Asset implements Valuable {
    private Ticker ticker;
    private String fullName;
    private Money avgPurchasePrice;
    private Quantity quantity;
    private List<String> tags;

    @Override
    public Money getValue() {
        return avgPurchasePrice.multiply(quantity.getQty());
    }
}
