package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Quantity {
    private double qty;
    private String unit;


    public static Quantity of(double qty) {
        return new Quantity(qty, "Number");
    }

    public static Quantity of(double qty, String unit) {
        return new Quantity(qty, unit);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(qty + other.getQty(), unit);
    }

    public Quantity minus(Quantity other) {
        return new Quantity(qty - other.qty, unit);
    }
}
