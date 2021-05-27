package com.multi.vidulum.quotation.app;

import lombok.Builder;
import lombok.Data;

import java.util.List;

public class QuotationDto {

    @Data
    @Builder
    public static class AssetBasicInfoJson {
        String ticker;
        String fullName;
        String segment;
        List<String> tags;
    }
}
