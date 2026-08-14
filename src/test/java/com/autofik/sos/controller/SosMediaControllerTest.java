package com.autofik.sos.controller;

import com.autofik.sos.config.SecurityConfig;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.SosMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SosMediaController.class)
@Import({SecurityConfig.class, GatewayIdentityFilter.class})
class SosMediaControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SosMediaService sosMediaService;

    private final UUID sosId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID mechanicId = UUID.randomUUID();

    private final MockMultipartFile file =
            new MockMultipartFile("file", "damage.jpg", "image/jpeg", new byte[]{1, 2, 3});

    private SosMediaResponse response() {
        return new SosMediaResponse(UUID.randomUUID(), MediaType.PHOTO, "image/jpeg", 3L,
                "https://minio.example.com/signed", null);
    }

    // Which of the two id slots the upload lands in is decided by the role on the principal, not
    // by anything the caller sends.
    @Test
    void upload_asDriver_recordsTheUploaderAsUser() throws Exception {
        when(sosMediaService.upload(eq(sosId), any(), eq(userId), eq(null))).thenReturn(response());

        mvc.perform(multipart("/api/v1/sos/{sosId}/media", sosId).file(file)
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://minio.example.com/signed"));

        verify(sosMediaService).upload(eq(sosId), any(), eq(userId), eq(null));
    }

    @Test
    void upload_asMechanic_recordsTheUploaderAsMechanic() throws Exception {
        when(sosMediaService.upload(eq(sosId), any(), eq(null), eq(mechanicId))).thenReturn(response());

        mvc.perform(multipart("/api/v1/sos/{sosId}/media", sosId).file(file)
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId))
                .andExpect(status().isCreated());

        verify(sosMediaService).upload(eq(sosId), any(), eq(null), eq(mechanicId));
    }

    @Test
    void upload_unsupportedType_returns400() throws Exception {
        when(sosMediaService.upload(eq(sosId), any(), eq(userId), eq(null)))
                .thenThrow(new IllegalArgumentException("Unsupported content type: video/mp4"));

        mvc.perform(multipart("/api/v1/sos/{sosId}/media", sosId).file(file)
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isBadRequest());
    }

    // Anyone holding a sosId could otherwise push images into a stranger's incident.
    @Test
    void getBySos_callerNotPartOfTheRequest_returns403() throws Exception {
        when(sosMediaService.getBySosRequest(sosId, userId))
                .thenThrow(new AccessDeniedException("You are not part of this SOS request"));

        mvc.perform(get("/api/v1/sos/{sosId}/media", sosId)
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isForbidden());
    }

    @Test
    void getBySos_returnsPresignedUrls() throws Exception {
        when(sosMediaService.getBySosRequest(sosId, userId)).thenReturn(List.of(response()));

        mvc.perform(get("/api/v1/sos/{sosId}/media", sosId)
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value("https://minio.example.com/signed"));
    }
}
