package com.autofik.sos.service;

import com.autofik.sos.dto.response.SosMediaResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface SosMediaService {
    SosMediaResponse upload(UUID sosId, MultipartFile file, UUID userId, UUID mechanicId);
    List<SosMediaResponse> getBySosRequest(UUID sosId);
}
