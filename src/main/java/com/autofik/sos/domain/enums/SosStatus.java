package com.autofik.sos.domain.enums;

import java.util.Map;
import java.util.Set;

public enum SosStatus {
    PENDING,
    ACCEPTED,
    ON_THE_WAY,
    ARRIVED,
    COMPLETED,
    CANCELLED,
    EXPIRED;

    // State-machine guard: only transitions listed here are allowed. COMPLETED, CANCELLED and
    // EXPIRED are terminal — they have no outgoing transitions.
    //
    // Lives on the enum rather than inside a service because more than one service advances an SOS:
    // BidServiceImpl moves PENDING → ACCEPTED when a bid is accepted, SosServiceImpl handles the
    // rest. A table private to one of them is a table the other silently bypasses.
    private static final Map<SosStatus, Set<SosStatus>> TRANSITIONS = Map.of(
            PENDING,    Set.of(ACCEPTED, CANCELLED, EXPIRED),
            ACCEPTED,   Set.of(ON_THE_WAY, CANCELLED),
            ON_THE_WAY, Set.of(ARRIVED, CANCELLED),
            ARRIVED,    Set.of(COMPLETED, CANCELLED)
    );

    // The states in which a customer's ticket is still unresolved — used to stop a second
    // concurrent SOS per user, and to decide whether a job is live.
    private static final Set<SosStatus> ACTIVE = Set.of(PENDING, ACCEPTED, ON_THE_WAY, ARRIVED);

    // A mechanic has been assigned and the job has not ended: the window in which a driver may
    // watch their mechanic approach, and the only states an ETA countdown means anything in.
    private static final Set<SosStatus> ASSIGNED = Set.of(ACCEPTED, ON_THE_WAY, ARRIVED);

    public boolean canTransitionTo(SosStatus to) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(to);
    }

    /** True while the request is unresolved, whether or not a mechanic has been assigned yet. */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /** True once a mechanic is on the job and before it ends — narrower than {@link #isActive()}. */
    public boolean isAssigned() {
        return ASSIGNED.contains(this);
    }

    /** No outgoing transitions: the request has reached its final state. */
    public boolean isTerminal() {
        return !TRANSITIONS.containsKey(this);
    }
}
