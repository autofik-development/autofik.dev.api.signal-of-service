package com.autofik.sos.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Configures the AWS S3 client and presigner beans for media file storage. endpointOverride allows
 * pointing to a local S3-compatible service (e.g., MinIO in dev) instead of the real AWS endpoint —
 * swap to a real AWS bucket for production by changing .env only.
 */
@Configuration
public class S3Config {

  @Value("${aws.s3.endpoint}")
  private String endpoint;

  @Value("${aws.s3.access-key}")
  private String accessKey;

  @Value("${aws.s3.secret-key}")
  private String secretKey;

  @Value("${aws.s3.region}")
  private String region;

  private StaticCredentialsProvider credentials() {
    return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
  }

  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(credentials())
        // forcePathStyle required for MinIO/LocalStack — real AWS uses virtual-hosted style by
        // default
        .forcePathStyle(true)
        .build();
  }

  @Bean
  public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(credentials())
        // pathStyleAccessEnabled must match forcePathStyle on S3Client so presigned URLs
        // resolve to the same endpoint format the client uses
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
