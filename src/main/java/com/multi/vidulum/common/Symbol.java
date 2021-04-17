package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Symbol {
    private Ticker origin;
    private Ticker destination;

    public static Symbol of(String id) {
        List<String> tickers = Arrays.asList(id.split("/"));
        return new Symbol(Ticker.of(tickers.get(0)), Ticker.of(tickers.get(1)));
    }

    public static Symbol of(Ticker origin, Ticker destination) {
        return new Symbol(origin, destination);
    }

    public String getId() {
        return String.format("%s%s", origin.getId(), destination.getId());
    }
}