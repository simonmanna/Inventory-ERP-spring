package com.inventoryerp.helpdesk.web;

import com.inventoryerp.helpdesk.application.HelpdeskTicketService;
import com.inventoryerp.helpdesk.application.HelpdeskTicketService.LetterInput;
import com.inventoryerp.helpdesk.application.HelpdeskTicketService.Scan;
import com.inventoryerp.helpdesk.application.HelpdeskTicketService.TicketFilter;
import com.inventoryerp.helpdesk.application.HelpdeskTicketService.TicketInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.multipart.MultipartFile;

/** {@code /helpdesk/tickets|letters|dashboard} (ADR-S045): tickets, thread, stages and the correspondence register. */
@RestController
@RequestMapping("/helpdesk")
public class HelpdeskTicketController {

    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}.*$";
    static final String DATE_MSG = " must be a valid ISO 8601 date string";

    public record TicketRequest(
            String teamId,
            String typeId,
            String priority,
            String channel,
            String direction,
            String partnerId,
            @Size(max = 200, message = "contactName must be at most 200 characters") String contactName,
            @Size(max = 200, message = "contactEmail must be at most 200 characters") String contactEmail,
            @Size(max = 60, message = "contactPhone must be at most 60 characters") String contactPhone,
            @Size(max = 300, message = "subject must be at most 300 characters") String subject,
            @Size(max = 20000, message = "description must be at most 20000 characters") String description,
            String assigneeId,
            List<String> tags,
            String relatedResType,
            String relatedResId,
            @Size(max = 120, message = "externalRef must be at most 120 characters") String externalRef,
            @Size(max = 120, message = "ourRef must be at most 120 characters") String ourRef,
            @Pattern(regexp = ISO_DATE, message = "letterDate" + DATE_MSG) String letterDate,
            String receivedAt,
            String confidentiality,
            @Pattern(regexp = ISO_DATE, message = "replyDueOn" + DATE_MSG) String replyDueOn,
            Integer version) {

        TicketInput toInput() {
            return new TicketInput(blank(teamId), blank(typeId), blank(priority), blank(channel), blank(direction),
                    blank(partnerId), contactName, blank(contactEmail), contactPhone, subject, description,
                    blank(assigneeId), tags, blank(relatedResType), blank(relatedResId), externalRef, ourRef,
                    date(letterDate, "letterDate"), instant(receivedAt, "receivedAt"), blank(confidentiality),
                    date(replyDueOn, "replyDueOn"), version);
        }
    }

    public record AssignRequest(String assigneeId) {}

    public record StageRequest(@NotBlank(message = "stageId should not be empty") String stageId,
            @Size(max = 4000, message = "resolution must be at most 4000 characters") String resolution) {}

    public record CloseRequest(@Size(max = 4000, message = "resolution must be at most 4000 characters")
            String resolution) {}

    public record MessageRequest(
            @NotBlank(message = "kind should not be empty") String kind,
            @NotBlank(message = "body should not be empty")
            @Size(max = 20000, message = "body must be at most 20000 characters") String body,
            Boolean sendEmail) {}

    public record LetterRequest(
            String templateId,
            @Size(max = 300, message = "subject must be at most 300 characters") String subject,
            @Size(max = 20000, message = "body must be at most 20000 characters") String body,
            String closingLine,
            String signatureName,
            String signatureTitle) {

        LetterInput toInput() {
            return new LetterInput(blank(templateId), blank(subject), body, closingLine, signatureName, signatureTitle);
        }
    }

    public record OutgoingRequest(@Valid TicketRequest ticket, @Valid LetterRequest letter) {}

    public record ReplyLetterRequest(@Valid LetterRequest letter, String assigneeId) {}

    public record DispatchRequest(
            @NotBlank(message = "method should not be empty") String method,
            @Size(max = 120, message = "trackingNumber must be at most 120 characters") String trackingNumber,
            String sentAt) {}

    private final HelpdeskTicketService tickets;

    public HelpdeskTicketController(HelpdeskTicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/tickets")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String teamId,
            @RequestParam(required = false) String stageId, @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String mine, @RequestParam(required = false) String unassigned,
            @RequestParam(required = false) String priority, @RequestParam(required = false) String channel,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String state,
            @RequestParam(required = false) String q, @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String relatedResType, @RequestParam(required = false) String relatedResId,
            @RequestParam(required = false) String page, @RequestParam(required = false) String pageSize) {
        return tickets.list(new TicketFilter(blank(teamId), blank(stageId), blank(assigneeId), "true".equals(mine),
                "true".equals(unassigned), blank(priority), blank(channel), blank(direction), blank(state), q,
                blank(partnerId), blank(relatedResType), blank(relatedResId), false, null, false,
                integer(page, 1), integer(pageSize, 50)));
    }

    @PostMapping("/tickets")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody TicketRequest r) {
        return tickets.create(r.toInput());
    }

    @GetMapping("/tickets/{id}")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public Map<String, Object> get(@PathVariable String id) {
        return tickets.get(id);
    }

    @PatchMapping("/tickets/{id}")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TicketRequest r) {
        return tickets.update(id, r.toInput());
    }

    @DeleteMapping("/tickets/{id}")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    public Map<String, Object> delete(@PathVariable String id) {
        return tickets.delete(id);
    }

    @PostMapping("/tickets/{id}/assign")
    @RequirePermissions(Permissions.HELPDESK__TICKET_ASSIGN)
    public Map<String, Object> assign(@PathVariable String id, @Valid @RequestBody AssignRequest r) {
        return tickets.assign(id, blank(r.assigneeId()));
    }

    @PostMapping("/tickets/{id}/stage")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    public Map<String, Object> stage(@PathVariable String id, @Valid @RequestBody StageRequest r) {
        return tickets.moveStage(id, r.stageId(), blank(r.resolution()));
    }

    @PostMapping("/tickets/{id}/close")
    @RequirePermissions(Permissions.HELPDESK__TICKET_CLOSE)
    public Map<String, Object> close(@PathVariable String id, @Valid @RequestBody(required = false) CloseRequest r) {
        return tickets.close(id, r == null ? null : blank(r.resolution()));
    }

    @PostMapping("/tickets/{id}/reopen")
    @RequirePermissions(Permissions.HELPDESK__TICKET_CLOSE)
    public Map<String, Object> reopen(@PathVariable String id) {
        return tickets.reopen(id);
    }

    @GetMapping("/tickets/{id}/messages")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public List<Map<String, Object>> messages(@PathVariable String id) {
        return tickets.messages(id);
    }

    @PostMapping("/tickets/{id}/messages")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addMessage(@PathVariable String id, @Valid @RequestBody MessageRequest r) {
        return tickets.addMessage(id, r.kind(), r.body(), Boolean.TRUE.equals(r.sendEmail()));
    }

    // ---- correspondence ----

    @GetMapping("/letters")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public PageResult<Map<String, Object>> letters(@RequestParam(required = false) String direction,
            @RequestParam(required = false) String year, @RequestParam(required = false) String q,
            @RequestParam(required = false) String state, @RequestParam(required = false) String awaitingReply,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        Integer y = year == null || year.isBlank() ? null : integer(year, 0);
        return tickets.list(new TicketFilter(null, null, null, false, false, null, null, blank(direction), blank(state),
                q, blank(partnerId), null, null, true, y, "true".equals(awaitingReply), integer(page, 1),
                integer(pageSize, 50)));
    }

    @PostMapping("/letters/incoming")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> incoming(@RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(required = false) String teamId, @RequestParam(required = false) String typeId,
            @RequestParam(required = false) String priority, @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String contactName, @RequestParam(required = false) String contactEmail,
            @RequestParam(required = false) String contactPhone, @RequestParam(required = false) String subject,
            @RequestParam(required = false) String description, @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String relatedResType, @RequestParam(required = false) String relatedResId,
            @RequestParam(required = false) String externalRef, @RequestParam(required = false) String letterDate,
            @RequestParam(required = false) String receivedAt, @RequestParam(required = false) String confidentiality,
            @RequestParam(required = false) String replyDueOn) {
        List<Scan> scans = new ArrayList<>();
        if (file != null) {
            for (MultipartFile f : file) {
                if (f != null && !f.isEmpty()) {
                    try {
                        scans.add(new Scan(f.getOriginalFilename(), f.getContentType(), f.getBytes()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        return tickets.registerIncoming(new TicketInput(blank(teamId), blank(typeId), blank(priority), "letter",
                "inbound", blank(partnerId), blank(contactName), blank(contactEmail), blank(contactPhone), subject,
                description, blank(assigneeId), null, blank(relatedResType), blank(relatedResId), blank(externalRef),
                null, date(letterDate, "letterDate"), instant(receivedAt, "receivedAt"), blank(confidentiality),
                date(replyDueOn, "replyDueOn"), null), scans);
    }

    @PostMapping("/letters/outgoing")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> outgoing(@Valid @RequestBody OutgoingRequest r) {
        if (r.ticket() == null) {
            throw ApiException.badRequest("ticket should not be empty");
        }
        return tickets.createOutgoing(r.ticket().toInput(), r.letter() == null
                ? new LetterInput(null, null, null, null, null, null) : r.letter().toInput());
    }

    @PostMapping("/tickets/{id}/reply-letter")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> replyLetter(@PathVariable String id, @Valid @RequestBody ReplyLetterRequest r) {
        return tickets.replyWithLetter(id, r.letter() == null ? new LetterInput(null, null, null, null, null, null)
                : r.letter().toInput(), blank(r.assigneeId()));
    }

    @PostMapping("/tickets/{id}/render-letter")
    @RequirePermissions(Permissions.HELPDESK__TICKET_WRITE)
    public Map<String, Object> render(@PathVariable String id, @Valid @RequestBody LetterRequest r) {
        return tickets.render(id, r.toInput(), null);
    }

    @PostMapping("/tickets/{id}/dispatch")
    @RequirePermissions(Permissions.HELPDESK__LETTER_DISPATCH)
    public Map<String, Object> dispatch(@PathVariable String id, @Valid @RequestBody DispatchRequest r) {
        return tickets.dispatch(id, r.method(), blank(r.trackingNumber()), instant(r.sentAt(), "sentAt"));
    }

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.HELPDESK__REPORT_READ)
    public Map<String, Object> dashboard() {
        return tickets.dashboard();
    }

    // ---- parsing ----

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static LocalDate date(String v, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + DATE_MSG);
        }
    }

    static Instant instant(String v, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return v.length() <= 10 ? LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                    : Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + DATE_MSG);
        }
    }

    static int integer(String v, int dflt) {
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("page must be an integer number");
        }
    }
}
