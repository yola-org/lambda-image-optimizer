package com.vestio.imageoptimizer;

import com.vestio.common.entity.Notification;

/** Internal event; the dispatcher publishes it only after its transaction commits. */
public record NotificationCreated(Notification notification) {
}
