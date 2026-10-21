package com.inventoryerp.hr.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * Typed HR bodies ({@code dto/hr-access.dto.ts}, {@code dto/hr-lifecycle.dto.ts}); every other HR route takes an
 * untyped body that Nest never validates.
 */
final class HrDtos {

    private HrDtos() {}

    static final String STR = "must be a string";
    static final String BOOL = "must be a boolean value";
    static final String UUID_RE = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    static final String UUID_MSG = "must be a UUID";
    static final String ISO_DATE =
            "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";
    static final String MIN3 = "must be longer than or equal to 3 characters";
    static final String MAX500 = "must be shorter than or equal to 500 characters";

    /** {@code LinkUserDto}. */
    public record LinkUserRequest(@NotNull(message = UUID_MSG) @Pattern(regexp = UUID_RE, message = UUID_MSG)
            String userId) {}

    /** {@code ProvisionUserDto} — constraints mirror {@code CreateUserDto}. */
    public record ProvisionUserRequest(
            @NotNull(message = "must be an email") @Email(message = "must be an email") String email,
            @NotNull(message = STR)
            @Size(min = 10, message = "password must be at least 10 characters")
            @Size(max = 128, message = "must be shorter than or equal to 128 characters") String password,
            @NotNull(message = STR)
            @Size(min = 1, message = "must be longer than or equal to 1 characters")
            @Size(max = 64, message = "must be shorter than or equal to 64 characters") String firstName,
            @Size(max = 64, message = "must be shorter than or equal to 64 characters") String lastName,
            @NotNull(message = "must be an array")
            @UniqueElements(message = "All roleIds's elements must be unique") List<String> roleIds,
            Boolean isActive) {}

    /** {@code TerminateEmployeeDto}. */
    public record TerminateEmployeeRequest(
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String terminationDate,
            @NotNull(message = STR) @Size(min = 3, message = MIN3) @Size(max = 500, message = MAX500) String reason,
            @NotNull(message = BOOL) Boolean disableAccount,
            Boolean resigned) {}

    /** {@code SuspendEmployeeDto}. */
    public record SuspendEmployeeRequest(
            @NotNull(message = STR) @Size(min = 3, message = MIN3) @Size(max = 500, message = MAX500) String reason,
            @NotNull(message = BOOL) Boolean disableAccount) {}

    /** {@code ReactivateEmployeeDto}. */
    public record ReactivateEmployeeRequest(
            @Size(min = 3, message = MIN3) @Size(max = 500, message = MAX500) String reason,
            Boolean enableAccount,
            Boolean toProbation) {}

    /** {@code TransferEmployeeDto}. */
    public record TransferEmployeeRequest(
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String toBranchId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String toDepartmentId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String toPositionId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String effectiveDate,
            @NotNull(message = STR) @Size(min = 3, message = MIN3) @Size(max = 500, message = MAX500) String reason) {}

    /** {@code ConfirmEmployeeDto}. */
    public record ConfirmEmployeeRequest(
            @Size(min = 3, message = MIN3) @Size(max = 500, message = MAX500) String reason) {}

    /** {@code UpdateAccessDto}. */
    public record UpdateAccessRequest(List<String> roleIds, Boolean isActive) {}
}
