package com.multi.vidulum.pnl.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PnlId {
    String id;

    public static PnlId of(String id) {
        return new PnlId(id);
    }
}