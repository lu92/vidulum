package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Asset implements Valuable {
    private Ticker ticker;
    private SubName subName;
    private Price avgPurchasePrice;
    private Quantity quantity;
    private Quantity locked;
    private Quantity free;

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
