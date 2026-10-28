package com.inventoryerp.communication.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the delivery queue every {@code COMM_DISPATCH_POLL_MS} (250 ms, Nest's self-scheduling loop). Off with
 * {@code app.jobs.enabled=false} (tests drive {@link MessageDispatchWorker#tick()} directly).
 */
@Component
@ConditionalOnExpression("${app.modules.communication:false} and ${app.jobs.enabled:true}")
public class MessageDispatchScheduler {

    private final MessageDispatchWorker worker;

    public MessageDispatchScheduler(MessageDispatchWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${COMM_DISPATCH_POLL_MS:250}", initialDelayString = "${COMM_DISPATCH_POLL_MS:250}")
    public void poll() {
        worker.drain();
    }
}
