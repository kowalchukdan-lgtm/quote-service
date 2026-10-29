package com.kowalchuk.quoteservice.rating;

import java.math.BigDecimal;

/**
 * The class-of-business values in effect for a risk.
 *
 * @param baseRatePerThousand premium per {@link RatingEngine#RATE_UNIT} of annual revenue
 * @param maxAutoRevenue revenue above which the risk is referred rather than quoted
 */
public record ClassRating(BigDecimal baseRatePerThousand, BigDecimal maxAutoRevenue) {
}
