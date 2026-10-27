package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.JournalEntryService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /journal-entries} (port of {@code journal-entry.controller.ts}): maker-checker manual entries. */
@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

    /** {@code ManualJournalLineDto}. */
    public record ManualLine(
            @NotNull(message = "must be a string") String accountId,
            BigDecimal debit,
            BigDecimal credit,
            String partnerId,
            String description) {}

    /** {@code CreateJournalEntryDto}. */
    public record CreateJournalEntryRequest(
            @NotNull(message = "must be a string") String journalCode,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String date,
            String description,
            String branchId,
            String costCenterId,
            @NotNull(message = "must be an array")
            @ArrayMinSize(2) List<@Valid ManualLine> lines) {}

    /** {@code ReverseJournalEntryDto}. */
    public record ReverseJournalEntryRequest(
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String date,
            String description) {}

    private final JournalEntryService entries;

    public JournalEntryController(JournalEntryService entries) {
        this.entries = entries;
    }

    @GetMapping
    @RequirePermissions(Permissions.JOURNAL_ENTRY__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        var q = QueryParams.page(query);
        return entries.list(q.page(), q.pageSize(), q.search());
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.JOURNAL_ENTRY__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return entries.findOne(id);
    }

    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.JOURNAL_ENTRY__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateJournalEntryRequest dto) {
        List<PostingRequest.Line> lines = dto.lines().stream()
                .map(l -> new PostingRequest.Line(l.accountId(), l.debit(), l.credit(), l.partnerId(), l.description(),
                        null, null, null))
                .toList();
        return entries.createManual(dto.journalCode(), EntityPatch.toInstant(dto.date()), dto.description(),
                dto.branchId(), dto.costCenterId(), lines);
    }

    @PostMapping("/{id}/post")
    @Idempotent
    @RequirePermissions(Permissions.JOURNAL_ENTRY__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return entries.post(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.JOURNAL_ENTRY__CREATE)
    public void discard(@PathVariable String id) {
        entries.discard(id);
    }

    @PostMapping("/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.JOURNAL_ENTRY__REVERSE)
    public Map<String, Object> reverse(@PathVariable String id,
            @Valid @RequestBody(required = false) ReverseJournalEntryRequest dto) {
        return entries.reverse(id, dto == null || dto.date() == null ? null : EntityPatch.toInstant(dto.date()),
                dto == null ? null : dto.description());
    }
}
