package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Asset implements Valuable {
    private Ticker ticker;
    private String fullName;
    private Segment segment;
    private SubName subName;
    private Money avgPurchasePrice;
    private Quantity quantity;
    private List<String> tags;

    @Override
    public Money getValue() {
        return avgPurchasePrice.multiply(quantity.getQty());
    }
}
