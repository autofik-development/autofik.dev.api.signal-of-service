package com.autofik.sos.service;

import com.autofik.sos.domain.enums.BidSortFilter;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;

import java.util.List;
import java.util.UUID;

public interface BidService {
    BidSosResponse create(CreateBidRequest request);
    List<BidSosResponse> getBySosRequest(UUID sosId, BidSortFilter sortBy);
    List<BidSosResponse> getByMechanic(UUID mechanicId);
    BidSosResponse updateStatus(UUID bidId, UpdateBidStatusRequest request);
    BidSosResponse cancel(UUID bidId, UUID mechanicId);
    List<BidSosResponse> getAll();
}
