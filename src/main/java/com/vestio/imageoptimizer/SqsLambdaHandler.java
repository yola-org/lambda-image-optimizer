package com.vestio.imageoptimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SQS handler. Configure the event source mapping with ReportBatchItemFailures. */
public final class SqsLambdaHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger LOGGER = Logger.getLogger(SqsLambdaHandler.class.getName());
    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            LOGGER.warning("Received an SQS event without records");
            return new SQSBatchResponse(List.of());
        }
        if (event.getRecords().isEmpty()) {
            return new SQSBatchResponse(List.of());
        }

        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        LOGGER.info("Processing " + event.getRecords().size() + " SQS record(s)");
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            String messageId = message.getMessageId();
            String listingId = "null";
            try {
                OptimizationRequest request = Application.MAPPER.readValue(message.getBody(), OptimizationRequest.class);
                listingId = request.listingId();
                Application.SERVICE.process(request);
                LOGGER.info("Optimized listing " + listingId + " from SQS message " + messageId);
            } catch (Exception exception) {
                // Log the full stack trace once at the message boundary; the message is retried via ReportBatchItemFailures.
                LOGGER.log(Level.SEVERE, "Image optimization failed for listing " + listingId
                        + " (SQS message " + messageId + ")", exception);
                failures.add(new SQSBatchResponse.BatchItemFailure(messageId));
            }
        }
        if (!failures.isEmpty()) {
            LOGGER.warning(failures.size() + " of " + event.getRecords().size() + " SQS message(s) failed");
        } else {
            LOGGER.info("Successfully processed all " + event.getRecords().size() + " SQS record(s)");
        }
        return new SQSBatchResponse(failures);
    }

    private static final class Application {
        private static final ConfigurableApplicationContext CONTEXT = ImageOptimizerApplication.start();
        private static final ObjectMapper MAPPER = CONTEXT.getBean(ObjectMapper.class);
        private static final OptimizationService SERVICE = CONTEXT.getBean(OptimizationService.class);
    }
}
