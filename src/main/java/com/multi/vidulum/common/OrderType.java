package com.multi.vidulum.common;

public enum OrderType {
    // MARKET price is not supported until vidulum will be integrated with any exchange
    // for MARKET - user will just execute directly trade instead od making order

    LIMIT, STOP_LIMIT, OCO
}
