package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.Objects;

@Data
@Builder
@NoArgsConstructor(force = true)
@AllArgsConstructor
public final class Budgeting {
    private final Money budget;
    private final ZonedDateTime created;
    private final ZonedDateTime lastUpdated;

//    public Budgeting(Money budget, ZonedDateTime created, ZonedDateTime lastUpdated) {
//        this.budget = budget;
//        this.created = created;
//        this.lastUpdated = lastUpdated;
//    }

    public Money budget() {
        return budget;
    }

    public ZonedDateTime created() {
        return created;
    }

    public ZonedDateTime lastUpdated() {
        return lastUpdated;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (Budgeting) obj;
        return Objects.equals(this.budget, that.budget) &&
                Objects.equals(this.created, that.created) &&
                Objects.equals(this.lastUpdated, that.lastUpdated);
    }

    @Override
    public int hashCode() {
        return Objects.hash(budget, created, lastUpdated);
    }

    @Override
    public String toString() {
        return "Budgeting[" +
                "budget=" + budget + ", " +
                "created=" + created + ", " +
                "lastUpdated=" + lastUpdated + ']';
    }


}
