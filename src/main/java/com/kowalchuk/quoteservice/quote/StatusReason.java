package com.kowalchuk.quoteservice.quote;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Why a quote was referred or declined.
 */
@Embeddable
public record StatusReason(@Column(name = "code") String code, @Column(name = "message") String message) {
}
