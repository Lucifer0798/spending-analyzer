package com.spendinganalyzer.dto;

import java.util.List;

/**
 * @param applicable false when "all accounts" spans more than one currency -- a merchant ranking
 *                   summed across currencies would compare unlike amounts, so it isn't attempted
 * @param merchantCount how many distinct merchants had spend in range, before {@code limit} applied
 */
public record TopMerchantsResponse(
        boolean applicable,
        String currency,
        int merchantCount,
        List<MerchantTotal> merchants
) {
    public static final TopMerchantsResponse NOT_APPLICABLE = new TopMerchantsResponse(false, null, 0, List.of());
}
