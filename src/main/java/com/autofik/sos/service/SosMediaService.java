package com.autofik.sos.service;

import com.autofik.sos.dto.response.SosMediaResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
/**
 * Service Interface defining the business use cases for emergency SOS Media management.
 * Handles processing binary files (photos/videos) uploaded during a vehicle breakdown crisis.
 */
public interface SosMediaService {
    
    /**
     * Use Case: Upload an image or video attachment for an ongoing SOS ticket.
     * * Why it uses 'MultipartFile': This is a special Spring class used to receive and read raw
     * binary file uploads over network requests (HTTP POST).
     * * Why it takes both 'userId' and 'mechanicId': To log exactly who is uploading the file.
     * It allows both the stranded driver (to show the problem) and the responding technician
     * (to document completion evidence) to attach media to the same ticket.
     * @return A clean Response DTO containing the public cloud URL link to view the image.
     */
    SosMediaResponse upload(UUID sosId, MultipartFile file, UUID userId, UUID mechanicId);

    /**
     * Use Case: Retrieve all media files linked to a single SOS emergency ticket.
     * * Application: Used by both drivers and mechanics to view the complete photo gallery
     * associated with an active or historical rescue job.
     */
    List<SosMediaResponse> getBySosRequest(UUID sosId, UUID callerId);
}
