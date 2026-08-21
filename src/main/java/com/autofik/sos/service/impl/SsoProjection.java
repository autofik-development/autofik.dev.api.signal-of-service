package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.dto.external.SsoProfile;
import com.autofik.sos.dto.external.SsoVehicle;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.security.Caller;
import com.autofik.sos.service.SsoClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates the local projection of an SSO identity the first time it touches this service.
 *
 * <p>Identities are born in SSO; the rows here are copies that must carry the same UUID. There is
 * no webhook and no dual write for a client to get half right — a missing row is created on the
 * next request that needs it, which also means the system self-heals after any gap.
 *
 * <p>Every read is scoped to the calling user by SSO itself, so this only ever runs while that
 * caller's own bearer is in hand. Nothing here can be moved to a background job or a retry queue:
 * those have no token.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SsoProjection {

    private final SsoClient sso;
    private final UserRepository userRepository;
    private final MechanicRepository mechanicRepository;
    private final VehicleRepository vehicleRepository;

    @Transactional
    public User user(UUID userId) {
        return userRepository.findById(userId).orElseGet(() -> {
            // ponytail: the internal gateway doesn't forward the caller's bearer yet, so there is
            // nothing to present to SSO's per-user routes. Stub from the gateway-asserted id alone
            // instead of 403ing every SOS creation — this only runs on a cache miss, so the row
            // upgrades to the real profile automatically once a token shows up on a later request
            // for the same user. Remove this branch once the gateway forwards a real bearer.
            if (Caller.token() == null) {
                log.warn("No caller token — projecting SSO user {} unverified (gateway not forwarding tokens yet)", userId);
                return userRepository.save(User.builder()
                        .userId(userId)
                        .name("Unverified user")
                        .phone("unverified:" + userId)
                        .build());
            }
            if (!sso.isVerified("user", userId)) {
                throw new AccessDeniedException("Account is not verified with SSO");
            }
            SsoProfile profile = sso.userProfile();
            requireSameIdentity(profile.id(), userId);
            log.info("Projecting SSO user {} into SOS", userId);
            return userRepository.save(User.builder()
                    .userId(userId)
                    .name(profile.name())
                    .phone(profile.phone())
                    .build());
        });
    }

    /**
     * Called from the cold paths only — availability, skills, bidding. Deliberately not from the
     * location ping, which runs several times a minute per mechanic and would pay an extra SELECT
     * every time to learn what it already knows.
     */
    @Transactional
    public Mechanic mechanic(UUID mechanicId) {
        return mechanicRepository.findById(mechanicId).orElseGet(() -> {
            // ponytail: same gateway gap as user() above — remove once it forwards a real bearer.
            if (Caller.token() == null) {
                log.warn("No caller token — projecting SSO mechanic {} unverified (gateway not forwarding tokens yet)", mechanicId);
                return mechanicRepository.save(Mechanic.builder()
                        .mechanicId(mechanicId)
                        // Placeholders must fit the columns: full_name is varchar(30) and
                        // phone_number varchar(20), so the id cannot be appended here the way
                        // User.phone (unconstrained, and unique) allows.
                        .fullName("Unverified mechanic")
                        .phoneNumber("unverified")
                        .serviceRadiusKm(10)
                        .build());
            }
            if (!sso.isVerified("mechanic", mechanicId)) {
                throw new AccessDeniedException("Mechanic is not verified with SSO");
            }
            SsoProfile profile = sso.mechanicProfile();
            requireSameIdentity(profile.id(), mechanicId);
            log.info("Projecting SSO mechanic {} into SOS", mechanicId);
            return mechanicRepository.save(Mechanic.builder()
                    .mechanicId(mechanicId)
                    .fullName(profile.name())
                    .phoneNumber(profile.phone())
                    .email(profile.email())
                    // Dispatch multiplies this by 1000 inside ST_DWithin, so a null makes the
                    // mechanic silently unmatchable. Same default the create endpoint uses.
                    .serviceRadiusKm(10)
                    .build());
        });
    }

    /**
     * Always refetched rather than read from the local copy: SSO's per-user scoping <em>is</em> the
     * ownership check, and skipping the call on a cache hit would skip the check with it.
     */
    @Transactional
    public Vehicle vehicle(UUID vehicleId, User owner) {
        Vehicle local = vehicleRepository.findById(vehicleId).orElseGet(Vehicle::new);
        local.setId(vehicleId);
        local.setUser(owner);

        // ponytail: same gateway gap as user() above. No bearer means no ownership check and no
        // vehicle detail fetch — the row still gets created so the SOS FK has somewhere to point,
        // details backfill once a real token is forwarded. Remove this branch once it is.
        if (Caller.token() == null) {
            log.warn("No caller token — skipping SSO ownership check for vehicle {} (gateway not forwarding tokens yet)", vehicleId);
            return vehicleRepository.save(local);
        }

        SsoVehicle fromSso = sso.vehicle(vehicleId);   // 403 when the vehicle is not the caller's
        local.setCarBrand(fromSso.carBrand());
        local.setCarModel(fromSso.carModel());
        local.setCarModelYear(fromSso.carModelYear());
        local.setFuelType(fromSso.fuel());
        local.setLicensePlate(fromSso.licensePlate());
        return vehicleRepository.save(local);
    }

    // The profile endpoint answers for whoever holds the bearer. If that is not the identity the
    // gateway asserted, the two credentials disagree and neither can be trusted.
    private void requireSameIdentity(UUID fromProfile, UUID fromGateway) {
        if (fromProfile != null && !fromProfile.equals(fromGateway)) {
            throw new AccessDeniedException("Identity mismatch between gateway header and SSO profile");
        }
    }
}
