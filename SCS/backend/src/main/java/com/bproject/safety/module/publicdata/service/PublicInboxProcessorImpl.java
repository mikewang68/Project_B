package com.bproject.safety.module.publicdata.service;

import com.bproject.safety.module.publicdata.repository.PublicInboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PublicInboxProcessorImpl implements PublicInboxProcessor {

    private final PublicInboxRepository inbox;
    private final PublicDeviceEventMapper mapper;

    public PublicInboxProcessorImpl(PublicInboxRepository inbox, PublicDeviceEventMapper mapper) {
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public boolean ensureInbox(String eventId, long committedMs, String wireSha256, JsonNode payload) {
        return inbox.insertIfAbsent(eventId, committedMs, wireSha256, payload);
    }

    @Override
    @Transactional
    public void processMapped(String eventId, JsonNode envelope) {
        String status = inbox.statusOf(eventId);
        if ("PROCESSED".equals(status)) {
            return;
        }
        mapper.map(envelope);
        inbox.markProcessed(eventId);
    }

    @Override
    @Transactional
    public void markFailed(String eventId, String error) {
        inbox.markFailed(eventId, error);
    }
}
