package com.vestio.imageoptimizer;

import com.vestio.common.event.NotificationEvent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class NotificationEventContractTest {
    @Test
    void roundTripsGenericNotificationWithoutListingId() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var input = getClass().getResourceAsStream("/contracts/notification-event.json")) {
            assertNotNull(input);
            var expected = mapper.readTree(input);
            var event = mapper.treeToValue(expected, NotificationEvent.class);
            assertFalse(event.getNotification().getParams().containsKey("listingId"));
            assertEquals(event.getNotification().getId(), event.getId());
            assertEquals(expected, mapper.valueToTree(event));
        }
    }
}
