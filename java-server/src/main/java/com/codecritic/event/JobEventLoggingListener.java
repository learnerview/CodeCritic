package com.codecritic.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Observer for CodeCriticJobEvent — currently logs job lifecycle; can be
 * extended with metrics or notifications without touching the publisher.
 */
@Component
public class JobEventLoggingListener {

    private static final Logger log = LoggerFactory.getLogger(JobEventLoggingListener.class);

    @EventListener
    public void onJobEvent(CodeCriticJobEvent event) {
        log.info("Job event type={} jobId={} status={} detail={} at={}",
                event.getType(), event.getJobId(), event.getStatus(), event.getDetail(), event.getOccurredAt());
    }
}
