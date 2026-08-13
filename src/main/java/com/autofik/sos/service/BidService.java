package com.autofik.sos.service;

import com.autofik.sos.domain.enums.BidSortFilter;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;
/**
 * Service Interface defining the business use cases for handling Mechanic Bids on SOS requests.
 * Acts as the boundary contract between the presentation layer (Controllers) and business logic.
 */
public interface BidService {

    /**
     * Use case: A machanic submits a price bid to help a strandard driver.
     * @param request Contain input data like price, mechanicId, sosId and location travel estimation
     */
    BidSosResponse create(CreateBidRequest request);

    /**
     * Use case: A driver views all bids send by different mechanics for their live sos
     * @param sosId identify the emergency request
     * @param sortBy A filter to bids (e.g, cheapest price or high_rate)
     * @return a list of available bids.
     */
    /**
     * The driver who raised the SOS sees every bid on it. A mechanic sees only their own —
     * the full list is a competitor's price sheet.
     */
    List<BidSosResponse> getBySosRequest(UUID sosId, BidSortFilter sortBy, UUID callerId);

    /**
     * Use Case: A mechanic view their own history of bids.
     * @param mechanicId identify specified mechanic
     * @return A list of bids this specified mechanic has submitted over time
     */
    Page<BidSosResponse> getByMechanic(UUID mechanicId, Pageable pageable);

    /**
     * Use case: A driver acc or rejects a mechanic's bid
     * @param bidId identify the target bid
     * @param request contains the new status state
     * @return update bid details.
     */
    BidSosResponse updateStatus(UUID bidId, UpdateBidStatusRequest request);

    /**
     * Use Case: A mechanic changes their mind and withdraws their bid before it's accepted.
     * @param bidId identify the target bid that drop
     * @param mechanicId identify the mechanic can cancel their own bids
     * @return the canceled bid state
     */
    BidSosResponse cancel(UUID bidId, UUID mechanicId);
}
