package com.vestio.imageoptimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

public class SqsLambdaHandler implements RequestHandler<SQSEvent, Void> {

    @Override
    public Void handleRequest(SQSEvent event, Context context) {

        context.getLogger().log("Received event: " + event);
        context.getLogger().log("Received context: " + context);
        context.getLogger().log("Received environment variables: " + System.getenv());
        context.getLogger().log("Received system properties: " + System.getProperties());
        context.getLogger().log("Received command line arguments: " + System.getenv("AWS_LAMBDA_FUNCTION_NAME"));

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