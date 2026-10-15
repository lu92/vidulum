package com.multi.vidulum.quotation.app;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class QuotationDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetBasicInfoJson {
        String ticker;
        String fullName;
        String segment;
        List<String> tags;
    }
}
