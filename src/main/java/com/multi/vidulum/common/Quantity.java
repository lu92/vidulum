package com.multi.vidulum.common;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Quantity {
    private double qty;
    private String unit;


    public static Quantity of(double qty) {
        return new Quantity(qty, "Number");
    }

    public static Quantity of(double qty, String unit) {
        return new Quantity(qty, unit);
    }

    public static Quantity zero() {
        return new Quantity(0, "Number");
    }

    public static Quantity zero(String unit) {
        return new Quantity(0, unit);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(qty + other.getQty(), unit);
    }

    public Quantity minus(Quantity other) {
        return new Quantity(qty - other.qty, unit);
    }

    public Quantity copy() {
        return new Quantity(qty, unit);
    }

    public boolean isZero() {
        return qty == 0;
    }

    public boolean isPositive() {
        return qty > 0;
    }
}
