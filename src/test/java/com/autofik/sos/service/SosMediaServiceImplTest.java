package com.autofik.sos.service;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.SosMediaServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SosMediaServiceImplTest {

    @Mock SosMediaRepository sosMediaRepository;
    @Mock SosRequestRepository sosRequestRepository;
    @Mock UserRepository userRepository;
    @Mock MechanicRepository mechanicRepository;
    @Mock SosMediaMapper sosMediaMapper;
    @Mock S3Client s3Client;
    @Mock S3Presigner s3Presigner;

    @InjectMocks SosMediaServiceImpl sosMediaService;

    private final UUID sosId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID mechanicId = UUID.randomUUID();

    private SosRequest sosRequest;
    private User user;
    private Mechanic mechanic;
    private MultipartFile file;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(sosMediaService, "bucket", "test-bucket");

        sosRequest = SosRequest.builder().sosId(sosId).build();
        user = User.builder().userId(userId).name("Alice").hashPassword("h").build();
        mechanic = Mechanic.builder().mechanicId(mechanicId).name("Bob").phone("081").email("bob@fix.com").hashPassword("h").build();

        file = mock(MultipartFile.class);
        lenient().when(file.getContentType()).thenReturn("image/jpeg");
        lenient().when(file.getOriginalFilename()).thenReturn("photo.jpg");
        lenient().when(file.getSize()).thenReturn(123L);
        lenient().when(file.getInputStream()).thenReturn(new ByteArrayInputStream("data".getBytes()));

        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        lenient().when(presigned.url()).thenReturn(new URL("https://minio.example.com/test-bucket/some-key?signed=1"));
        lenient().when(s3Presigner.presignGetObject(any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class)))
                .thenReturn(presigned);
    }

    @Test
    void upload_userUpload_savesMediaWithInferredTypeAndReturnsPresignedUrl() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
        SosMediaResponse mapped = new SosMediaResponse(UUID.randomUUID(), MediaType.PHOTO, "image/jpeg", 123L, null, null);
        when(sosMediaMapper.toResponse(any(SosMedia.class))).thenReturn(mapped);

        SosMediaResponse result = sosMediaService.upload(sosId, file, userId, null);

        assertThat(result.url()).contains("minio.example.com");
        assertThat(result.mediaType()).isEqualTo(MediaType.PHOTO);

        ArgumentCaptor<SosMedia> captor = ArgumentCaptor.forClass(SosMedia.class);
        verify(sosMediaRepository).save(captor.capture());
        SosMedia saved = captor.getValue();
        assertThat(saved.getMediaType()).isEqualTo(MediaType.PHOTO);
        assertThat(saved.getUploadedByUser()).isEqualTo(user);
        assertThat(saved.getUploadedByMechanic()).isNull();
        assertThat(saved.getObjectKey()).startsWith("sos/" + sosId + "/").endsWith(".jpg");

        verify(s3Client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void upload_mechanicUpload_setsUploadedByMechanic() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sosMediaMapper.toResponse(any(SosMedia.class)))
                .thenReturn(new SosMediaResponse(UUID.randomUUID(), MediaType.PHOTO, "image/jpeg", 123L, null, null));

        sosMediaService.upload(sosId, file, null, mechanicId);

        ArgumentCaptor<SosMedia> captor = ArgumentCaptor.forClass(SosMedia.class);
        verify(sosMediaRepository).save(captor.capture());
        assertThat(captor.getValue().getUploadedByMechanic()).isEqualTo(mechanic);
        assertThat(captor.getValue().getUploadedByUser()).isNull();
    }

    @Test
    void upload_neitherUserNorMechanicProvided_throwsIllegalStateException() {
        assertThatThrownBy(() -> sosMediaService.upload(sosId, file, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one");

        verifyNoInteractions(sosRequestRepository, sosMediaRepository, s3Client);
    }

    @Test
    void upload_bothUserAndMechanicProvided_throwsIllegalStateException() {
        assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, mechanicId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one");

        verifyNoInteractions(sosRequestRepository, sosMediaRepository, s3Client);
    }

    @Test
    void upload_sosNotFound_throwsEntityNotFoundException() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(sosId.toString());
    }

    @Test
    void upload_unsupportedContentType_throwsIllegalStateException() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
        when(file.getContentType()).thenReturn("application/pdf");

        assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported content type");

        verifyNoInteractions(s3Client, sosMediaRepository);
    }

    @Test
    void upload_videoContentType_inferredAsVideo() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(file.getContentType()).thenReturn("video/mp4");
        when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sosMediaMapper.toResponse(any(SosMedia.class)))
                .thenReturn(new SosMediaResponse(UUID.randomUUID(), MediaType.VIDEO, "video/mp4", 123L, null, null));

        sosMediaService.upload(sosId, file, userId, null);

        ArgumentCaptor<SosMedia> captor = ArgumentCaptor.forClass(SosMedia.class);
        verify(sosMediaRepository).save(captor.capture());
        assertThat(captor.getValue().getMediaType()).isEqualTo(MediaType.VIDEO);
    }

    @Test
    void getBySosRequest_returnsListWithPresignedUrls() {
        SosMedia media = SosMedia.builder().mediaId(UUID.randomUUID()).objectKey("sos/" + sosId + "/x.jpg")
                .mediaType(MediaType.PHOTO).contentType("image/jpeg").sizeBytes(10L).build();
        when(sosMediaRepository.findBySosRequestSosId(sosId)).thenReturn(List.of(media));
        when(sosMediaMapper.toResponse(media))
                .thenReturn(new SosMediaResponse(media.getMediaId(), MediaType.PHOTO, "image/jpeg", 10L, null, null));

        List<SosMediaResponse> result = sosMediaService.getBySosRequest(sosId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).url()).contains("minio.example.com");
    }
}
