package com.vestio.imageoptimizer;

import com.vestio.common.event.NotificationCreated;
import com.vestio.common.publisher.AppSyncEventsPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {
    private final AppSyncEventsPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreated event) {
        try {
            // Wait for delivery before the Lambda invocation can finish.
            publisher.dispatchNotificationEvent(event.notification()).join();
        } catch (Exception exception) {
            log.warn("AppSync publication failed for notification {}; the inbox entry is saved",
                    event.notification().getId(), exception);
        }
    }
}
