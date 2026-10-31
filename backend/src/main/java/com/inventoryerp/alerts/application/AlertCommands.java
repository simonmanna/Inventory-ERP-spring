package com.inventoryerp.alerts.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Inputs of the alerts API. */
public final class AlertCommands {

    private AlertCommands() {}

    /** {@code PUT /alerts/rules/:type}: the whole rule, as the form shows it. A null threshold means the default. */
    public record RuleInput(
            @NotNull Boolean enabled,
            @DecimalMin("0") BigDecimal threshold,
            @NotNull @Pattern(regexp = "daily|weekly|monthly") String repeatEvery,
            @NotNull @Size(max = 3) List<@NotNull String> channels,
            @Size(max = 120) String recipientPermission,
            @Size(max = 100) List<@NotNull String> recipientUserIds) {}

    /** {@code POST /alerts/run}: a scan now, of every type or of one. */
    public record RunInput(@Size(max = 60) String alertType) {}

    /** Filters of the alert list and its CSV. */
    public record EventQuery(String alertType, String severity, String status, String from, String to, Integer page,
            Integer pageSize) {}
}
