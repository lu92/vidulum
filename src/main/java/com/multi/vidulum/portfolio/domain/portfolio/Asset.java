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
    private Price avgPurchasePrice;
    private Quantity quantity;
    private Quantity locked;
    private Quantity free;
    private List<String> tags;

    @Override
    public Money getValue() {
        return avgPurchasePrice.multiply(quantity);
    }

    public void lock(Quantity quantity) {
        locked = locked.plus(quantity);
        free = free.minus(quantity);
    }

    public void unlock(Quantity quantity) {
        locked = locked.minus(quantity);
        free = free.plus(quantity);
    }
}
