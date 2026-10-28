package com.autofik.sos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.dto.response.MechanicSkillResponse;
import com.autofik.sos.dto.response.MechanicSummaryResponse;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryEntry;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.security.CallerRole;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.service.MechanicService;
import com.autofik.sos.service.SosMediaService;
import com.autofik.sos.service.SosService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** The controllers only hand the caller's id to the service; this pins which id goes where. */
class ControllerDelegationTest {

  private final UUID sosId = UUID.randomUUID();
  private final UUID callerId = UUID.randomUUID();
  private final CurrentUser mechanic = new CurrentUser(callerId, CallerRole.MECHANIC, null, null);
  private final CurrentUser driver = new CurrentUser(callerId, CallerRole.USER, null, null);

  @Test
  void sosController_passesTheCallerIdToEachMechanicAction() {
    SosService service = mock(SosService.class);
    SosController controller = new SosController(service);
    SosRequestResponse response = mock(SosRequestResponse.class);
    SosStatusHistoryResponse history = mock(SosStatusHistoryResponse.class);
    List<SosStatusHistoryEntry> entries = List.of();
    when(service.getHistory(sosId, callerId)).thenReturn(entries);
    when(service.proposeArrival(sosId, callerId)).thenReturn(history);
    when(service.reportCustomerNoShow(sosId, callerId)).thenReturn(response);
    when(service.releaseByMechanic(sosId, callerId)).thenReturn(response);
    when(service.confirmStillComing(sosId, callerId)).thenReturn(response);

    assertThat(controller.getHistory(sosId, driver)).isSameAs(entries);
    assertThat(controller.proposeArrival(sosId, mechanic)).isSameAs(history);
    assertThat(controller.reportCustomerNoShow(sosId, mechanic)).isSameAs(response);
    assertThat(controller.releaseByMechanic(sosId, mechanic)).isSameAs(response);
    assertThat(controller.confirmStillComing(sosId, mechanic)).isSameAs(response);
  }

  @Test
  void mechanicController_readsAreDelegated() {
    MechanicService service = mock(MechanicService.class);
    MechanicController controller = new MechanicController(service);
    MechanicSummaryResponse summary = mock(MechanicSummaryResponse.class);
    List<MechanicSkillResponse> skills = List.of();
    when(service.getById(callerId)).thenReturn(summary);
    when(service.getSkills(callerId)).thenReturn(skills);

    assertThat(controller.getById(callerId)).isSameAs(summary);
    assertThat(controller.getSkills(callerId)).isSameAs(skills);
  }

  @Test
  void mediaUpload_recordsWhichSideUploaded() {
    SosMediaService service = mock(SosMediaService.class);
    SosMediaController controller = new SosMediaController(service);
    MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1});
    SosMediaResponse asMechanic = mock(SosMediaResponse.class);
    SosMediaResponse asDriver = mock(SosMediaResponse.class);
    when(service.upload(sosId, file, null, callerId)).thenReturn(asMechanic);
    when(service.upload(sosId, file, callerId, null)).thenReturn(asDriver);

    assertThat(controller.upload(sosId, file, mechanic)).isSameAs(asMechanic);
    assertThat(controller.upload(sosId, file, driver)).isSameAs(asDriver);
    verify(service).upload(sosId, file, null, callerId);
  }

  @Test
  void sosController_listsAllOrByStatus() {
    SosService service = mock(SosService.class);
    SosController controller = new SosController(service);
    var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
    var all = new org.springframework.data.domain.PageImpl<SosRequestResponse>(List.of());
    var some = new org.springframework.data.domain.PageImpl<SosRequestResponse>(List.of());
    when(service.getByUser(callerId, pageable)).thenReturn(all);
    when(service.getByUserAndStatus(
            callerId, com.autofik.sos.domain.enums.SosStatus.PENDING, pageable))
        .thenReturn(some);

    assertThat(controller.getAll(null, driver, pageable)).isSameAs(all);
    assertThat(controller.getAll(com.autofik.sos.domain.enums.SosStatus.PENDING, driver, pageable))
        .isSameAs(some);
  }
}
