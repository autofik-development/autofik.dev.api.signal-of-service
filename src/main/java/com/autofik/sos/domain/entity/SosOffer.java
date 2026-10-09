package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Write-only log of who was offered which ticket and whether they ever bid on it (G12). One row per
 * ticket and mechanic, so a rebroadcast does not count twice. An offer with a null {@code bidAt} on
 * a ticket that is no longer open is an ignored offer. Rows are written by native upserts in {@code
 * SosOfferRepository}; plain ids, no relations, because nothing joins through this table.
 */
@Entity
@Table(
    name = "sos_offer",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_sos_offer_sos_mechanic",
            columnNames = {"sos_id", "mechanic_id"}),
    indexes = @Index(name = "idx_sos_offer_mechanic", columnList = "mechanic_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SosOffer {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "sos_id", nullable = false)
  private UUID sosId;

  @Column(name = "mechanic_id", nullable = false)
  private UUID mechanicId;

  @Column(name = "offered_at", nullable = false)
  private LocalDateTime offeredAt;

  // First time this mechanic bid on the ticket; kept on a re-bid.
  @Column(name = "bid_at")
  private LocalDateTime bidAt;
}
