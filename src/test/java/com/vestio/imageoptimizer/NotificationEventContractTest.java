package com.vestio.imageoptimizer;

import tools.jackson.databind.JsonNode;

import java.io.InputStream;

import com.vestio.common.event.Event;
import com.vestio.common.event.EventType;

import com.vestio.common.entity.Notification;
import com.vestio.common.entity.enums.NotificationType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class NotificationEventContractTest {
    @Test
    void roundTripsNotificationEnvelopeWithoutListingId() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        try (InputStream input = getClass().getResourceAsStream("/contracts/notification-event.json")) {
            assertNotNull(input);
            JsonNode expected = mapper.readTree(input);
            Event event = mapper.treeToValue(expected, Event.class);
            assertEquals(EventType.NOTIFICATION, event.type());
            Notification notification = mapper.convertValue(event.payload(), Notification.class);
            assertNull(notification.getListingId());
            assertEquals(NotificationType.OFFER_CREATED, notification.getType());
            assertEquals(notification.getId(), event.id());
            assertEquals(expected, mapper.valueToTree(event));
        }
    }
}
