package com.autofik.sos.service;

import java.util.Map;
import java.util.UUID;

public interface OneSignalService {
    void send(UUID recipientId, String title, String body, Map<String, String> data);
}
