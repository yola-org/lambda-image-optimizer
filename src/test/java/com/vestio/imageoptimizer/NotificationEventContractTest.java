package com.vestio.imageoptimizer;

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
        var mapper = JsonMapper.builder().build();
        try (var input = getClass().getResourceAsStream("/contracts/notification-event.json")) {
            assertNotNull(input);
            var expected = mapper.readTree(input);
            var event = mapper.treeToValue(expected, Event.class);
            assertEquals(EventType.NOTIFICATION, event.type());
            var notification = mapper.convertValue(event.payload(), Notification.class);
            assertNull(notification.getListingId());
            assertEquals(NotificationType.OFFER_CREATED, notification.getType());
            assertEquals(notification.getId(), event.id());
            assertEquals(expected, mapper.valueToTree(event));
        }
    }
}
