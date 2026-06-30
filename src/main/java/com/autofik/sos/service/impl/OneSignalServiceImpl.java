package com.autofik.sos.service.impl;

import com.autofik.sos.service.OneSignalService;
import com.onesignal.client.ApiException;
import com.onesignal.client.api.DefaultApi;
import com.onesignal.client.model.LanguageStringMap;
import com.onesignal.client.model.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OneSignalServiceImpl implements OneSignalService {

    private final DefaultApi oneSignalApi;

    @Value("${onesignal.app-id}")
    private String appId;

    @Override
    public void send(UUID recipientId, String title, String body, Map<String, String> data) {
        try {
            Notification notification = new Notification();
            notification.setAppId(appId);
            // external_id matches what the mobile app sets via OneSignal.login(userId)
            notification.setIncludeAliases(Map.of("external_id", List.of(recipientId.toString())));
            notification.setTargetChannel(Notification.TargetChannelEnum.PUSH);
            notification.setHeadings(new LanguageStringMap().en(title));
            notification.setContents(new LanguageStringMap().en(body));
            if (data != null && !data.isEmpty()) {
                notification.setData(data);
            }
            oneSignalApi.createNotification(notification);
        } catch (ApiException e) {
            log.warn("OneSignal push failed recipient={} status={}: {}", recipientId, e.getCode(), e.getResponseBody());
        } catch (Exception e) {
            log.warn("OneSignal push failed recipient={}: {}", recipientId, e.getMessage());
        }
    }
}
