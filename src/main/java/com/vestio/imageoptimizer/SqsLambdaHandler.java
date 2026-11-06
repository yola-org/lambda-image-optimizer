package com.vestio.imageoptimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

public class SqsLambdaHandler implements RequestHandler<SQSEvent, Void> {

    @Override
    public Void handleRequest(SQSEvent event, Context context) {

        if (event == null || event.getRecords() == null) {
            return null;
        }

        context.getLogger().log(
                "Received " + event.getRecords().size() + " SQS messages"
        );

        for (SQSEvent.SQSMessage message : event.getRecords()) {

            String messageId = message.getMessageId();
            String body = message.getBody();

            context.getLogger().log("Message ID: " + messageId);
            context.getLogger().log("Message body: " + body);

            // TODO: Process message
        }

        return null;
    }
}