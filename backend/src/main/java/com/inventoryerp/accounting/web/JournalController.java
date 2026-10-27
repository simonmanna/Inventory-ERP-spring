package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.JournalService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /journals} (port of {@code journal.controller.ts}). */
@RestController
@RequestMapping("/journals")
public class JournalController {

    /** {@code CreateJournalDto}. */
    public record CreateJournalRequest(
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String code,
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String name,
            @NotNull(message = "journalType must be one of the following values: general, sales, purchase, cash, bank,"
                    + " adjustment, opening, closing")
            @OneOf({"general", "sales", "purchase", "cash", "bank", "adjustment", "opening", "closing"}) String journalType,
            String defaultDebitAccountId,
            String defaultCreditAccountId,
            String sequencePrefix,
            Boolean isActive) {}

    /** {@code UpdateJournalDto}. */
    public record UpdateJournalRequest(
            String code,
            String name,
            @OneOf({"general", "sales", "purchase", "cash", "bank", "adjustment", "opening", "closing"}) String journalType,
            String defaultDebitAccountId,
            String defaultCreditAccountId,
            String sequencePrefix,
            Boolean isActive) {}

    private final JournalService journals;

    public JournalController(JournalService journals) {
        this.journals = journals;
    }

    @GetMapping
    @RequirePermissions(Permissions.JOURNAL__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return journals.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.JOURNAL__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return journals.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.JOURNAL__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateJournalRequest dto) {
        return journals.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.JOURNAL__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateJournalRequest dto) {
        return journals.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.JOURNAL__DELETE)
    public void remove(@PathVariable String id) {
        journals.remove(id);
    }
}
