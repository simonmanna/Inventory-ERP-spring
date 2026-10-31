package com.inventoryerp.kernel.health;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Failures of scheduled jobs and external providers, per tenant-loop step: these jobs catch errors so one organization
 * cannot stop the others, which keeps them out of the HTTP metrics. Prometheus: {@code erp_job_failures_total{task}} and
 * {@code erp_provider_failures_total{provider}}. The label is {@code task} because Prometheus reserves {@code job}.
 */
@Component
public class JobMetrics {

    private final ObjectProvider<MeterRegistry> meters;

    public JobMetrics(ObjectProvider<MeterRegistry> meters) {
        this.meters = meters;
    }

    public void jobFailed(String task) {
        MeterRegistry r = meters.getIfAvailable();
        if (r != null) {
            r.counter("erp.job.failures", "task", task).increment();
        }
    }

    public void providerFailed(String provider) {
        MeterRegistry r = meters.getIfAvailable();
        if (r != null) {
            r.counter("erp.provider.failures", "provider", provider).increment();
        }
    }
}
