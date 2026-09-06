package com.autofik.sos.dto.event;

import java.util.List;
import java.util.UUID;

public record BidRejectedEvent(UUID sosId, List<UUID> bidIds) {}
