package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.Ticker;
import lombok.Value;

import java.util.List;

@Value
public class AssetBasicInfo {
    Ticker ticker;
    String fullName;
    List<String> tags;
}
