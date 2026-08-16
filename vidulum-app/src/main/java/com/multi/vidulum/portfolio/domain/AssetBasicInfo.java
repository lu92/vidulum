package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.Segment;
import com.multi.vidulum.common.Ticker;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AssetBasicInfo {
    Ticker ticker;
    String fullName;
    Segment segment;
    List<String> tags;

    public static AssetBasicInfo notFound(Ticker ticker) {
        return new AssetBasicInfo(ticker, "Not found", Segment.unknown(), List.of());
    }
}
