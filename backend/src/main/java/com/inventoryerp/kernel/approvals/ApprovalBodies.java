package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.web.GlobalExceptionHandler;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validation for PATCH bodies where field presence matters ({@code minAmount: null} clears the value,
 * an absent field leaves it): whitelist check + nested step validation.
 */
@Component
public class ApprovalBodies {

    private final JsonMapper mapper;
    private final Validator validator;

    public ApprovalBodies(JsonMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    static void check(Map<String, Object> dto, String... allowed) {
        List<String> errors = new ArrayList<>();
        for (String k : dto.keySet()) {
            if (!List.of(allowed).contains(k)) {
                errors.add("property " + k + " should not exist");
            }
        }
        Object min = dto.get("minAmount");
        if (min != null && !(min instanceof Number)) {
            errors.add("minAmount must be a number conforming to the specified constraints");
        }
        Object rc = dto.get("requiredCount");
        if (rc != null && (!(rc instanceof Integer || rc instanceof Long) || ((Number) rc).longValue() < 1)) {
            errors.add("requiredCount must not be less than 1");
        }
        for (String b : List.of("isActive", "enforceDistinctApprovers")) {
            if (dto.get(b) != null && !(dto.get(b) instanceof Boolean)) {
                errors.add(b + " must be a boolean value");
            }
        }
        for (String s : List.of("name", "entityType", "branchId", "departmentId")) {
            if (dto.get(s) != null && !(dto.get(s) instanceof String)) {
                errors.add(s + " must be a string");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.withBody(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.nestBody(HttpStatus.BAD_REQUEST, errors));
        }
    }

    List<ApprovalAdminService.Step> steps(Object raw) {
        if (!(raw instanceof List<?>)) {
            throw ApiException.withBody(HttpStatus.BAD_REQUEST,
                    GlobalExceptionHandler.nestBody(HttpStatus.BAD_REQUEST, List.of("steps must be an array")));
        }
        List<ApprovalControllers.StepRequest> steps = mapper.convertValue(raw,
                mapper.getTypeFactory().constructCollectionType(List.class, ApprovalControllers.StepRequest.class));
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Set<ConstraintViolation<ApprovalControllers.StepRequest>> v = validator.validate(steps.get(i));
            for (var c : v) {
                errors.add("steps." + i + "." + c.getPropertyPath() + " " + c.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.withBody(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.nestBody(HttpStatus.BAD_REQUEST, errors));
        }
        return steps.stream().map(ApprovalControllers.StepRequest::toStep).toList();
    }
}
