package com.autofik.sos.config;

import java.math.BigDecimal;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that knows whether bids carry a price. Off until money features release: a price
 * sent by a client is dropped, and a stored one (bid placed while the flag was on) is never shown.
 */
@Component
public class BidPricing {

  private final boolean enabled;

  public BidPricing(@Value("${sos.features.bid-price-enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  /** What a bid may show or publish: the price when the flag is on, otherwise nothing. */
  @Named("visiblePrice")
  public BigDecimal visible(BigDecimal price) {
    return enabled ? price : null;
  }
}
