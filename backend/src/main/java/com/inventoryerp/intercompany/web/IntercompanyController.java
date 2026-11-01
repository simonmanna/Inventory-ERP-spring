package com.inventoryerp.intercompany.web;

import com.inventoryerp.intercompany.application.CompanyGroupService;
import com.inventoryerp.intercompany.application.IntercompanyConsolidationService;
import com.inventoryerp.intercompany.application.IntercompanyJournalService;
import com.inventoryerp.intercompany.application.IntercompanyJournalService.JournalInput;
import com.inventoryerp.intercompany.application.IntercompanyMirrorService;
import com.inventoryerp.intercompany.application.IntercompanyPartnerService;
import com.inventoryerp.kernel.auth.AuthService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /intercompany}: company groups, company switching, intercompany partners and settings, mirrored documents,
 * intercompany journals, reconciliation and consolidation with eliminations (roadmap 2.7, ADR-S028, extension).
 */
@RestController
@RequestMapping("/intercompany")
public class IntercompanyController {

    private static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    public record GroupRequest(
            @NotBlank(message = "name should not be empty") @Size(max = 120, message = "name must be at most 120"
                    + " characters") String name) {}

    public record AddMemberRequest(
            @NotBlank(message = "organizationCode should not be empty") String organizationCode,
            @DecimalMin(value = "0.0001", message = "ownershipPercent must be greater than 0")
            @DecimalMax(value = "100", message = "ownershipPercent must not be greater than 100")
            BigDecimal ownershipPercent) {}

    public record UpdateMemberRequest(
            @NotNull(message = "ownershipPercent must be a number")
            @DecimalMin(value = "0.0001", message = "ownershipPercent must be greater than 0")
            @DecimalMax(value = "100", message = "ownershipPercent must not be greater than 100")
            BigDecimal ownershipPercent) {}

    public record LinkPartnerRequest(String partnerId) {}

    public record SettingsRequest(@OneOf({"off", "draft", "post"}) String mirrorMode, String billExpenseAccountId) {}

    public record JournalRequest(
            @NotBlank(message = "targetOrganizationId should not be empty") String targetOrganizationId,
            @NotBlank(message = "date should not be empty") @Pattern(regexp = DATE, message = "date must be a valid"
                    + " ISO 8601 date string") String date,
            @NotBlank(message = "description should not be empty") @Size(max = 500, message = "description must be"
                    + " at most 500 characters") String description,
            @NotNull(message = "amount must be a positive number")
            @DecimalMin(value = "0.01", message = "amount must be a positive number") BigDecimal amount,
            @NotBlank(message = "sourceAccountId should not be empty") String sourceAccountId,
            String targetAccountId,
            String targetAccountCode) {}

    private final CompanyGroupService groups;
    private final IntercompanyPartnerService partners;
    private final IntercompanyMirrorService mirror;
    private final IntercompanyJournalService journals;
    private final IntercompanyConsolidationService consolidation;

    public IntercompanyController(CompanyGroupService groups, IntercompanyPartnerService partners,
            IntercompanyMirrorService mirror, IntercompanyJournalService journals,
            IntercompanyConsolidationService consolidation) {
        this.groups = groups;
        this.partners = partners;
        this.mirror = mirror;
        this.journals = journals;
        this.consolidation = consolidation;
    }

    // ---- group ----------------------------------------------------------------------------------------------------

    @GetMapping("/group")
    @RequirePermissions(Permissions.INTERCOMPANY__READ)
    public Map<String, Object> group() {
        return groups.current();
    }

    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> createGroup(@Valid @RequestBody GroupRequest dto) {
        return groups.create(dto.name());
    }

    @PatchMapping("/group")
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> renameGroup(@Valid @RequestBody GroupRequest dto) {
        return groups.rename(dto.name());
    }

    @DeleteMapping("/group")
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> deleteGroup() {
        return groups.delete();
    }

    @PostMapping("/group/members")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> addMember(@Valid @RequestBody AddMemberRequest dto) {
        return groups.addMember(dto.organizationCode().trim(), dto.ownershipPercent());
    }

    @PatchMapping("/group/members/{organizationId}")
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> updateMember(@PathVariable String organizationId,
            @Valid @RequestBody UpdateMemberRequest dto) {
        return groups.updateMember(organizationId, dto.ownershipPercent());
    }

    @DeleteMapping("/group/members/{organizationId}")
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> removeMember(@PathVariable String organizationId) {
        return groups.removeMember(organizationId);
    }

    // ---- company switching ----------------------------------------------------------------------------------------

    @GetMapping("/companies")
    @RequirePermissions(Permissions.INTERCOMPANY__SWITCH)
    public List<Map<String, Object>> companies() {
        return groups.companies();
    }

    @PostMapping("/companies/{organizationId}/switch")
    @RequirePermissions(Permissions.INTERCOMPANY__SWITCH)
    public Map<String, Object> switchCompany(@PathVariable String organizationId, HttpServletRequest req) {
        return groups.switchTo(organizationId, new AuthService.RequestInfo(req.getRemoteAddr(),
                req.getHeader("User-Agent"), req.getHeader("X-Device-Label")));
    }

    // ---- partners and settings ------------------------------------------------------------------------------------

    @GetMapping("/partners")
    @RequirePermissions(Permissions.INTERCOMPANY__READ)
    public List<Map<String, Object>> partners() {
        return partners.list();
    }

    @PutMapping("/partners/{counterpartOrganizationId}")
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> linkPartner(@PathVariable String counterpartOrganizationId,
            @Valid @RequestBody LinkPartnerRequest dto) {
        return partners.link(counterpartOrganizationId, dto.partnerId());
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.INTERCOMPANY__READ)
    public Map<String, Object> settings() {
        return partners.getSettings();
    }

    @PutMapping("/settings")
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> putSettings(@Valid @RequestBody SettingsRequest dto) {
        return partners.putSettings(dto.mirrorMode(), dto.billExpenseAccountId());
    }

    // ---- mirrored documents ---------------------------------------------------------------------------------------

    @GetMapping("/transactions")
    @RequirePermissions(Permissions.INTERCOMPANY__READ)
    public PageResult<Map<String, Object>> transactions(@RequestParam Map<String, String> query) {
        PageQuery q = QueryParams.page(query, "status");
        String status = query.get("status");
        if (status != null && !List.of("mirrored", "failed", "skipped").contains(status)) {
            throw QueryParams.validation(List.of("status must be one of the following values: mirrored, failed,"
                    + " skipped"));
        }
        return mirror.list(q, status);
    }

    @PostMapping("/transactions/{id}/retry")
    @RequirePermissions(Permissions.INTERCOMPANY__MANAGE)
    public Map<String, Object> retry(@PathVariable String id) {
        return mirror.retry(id);
    }

    // ---- intercompany journals ------------------------------------------------------------------------------------

    @GetMapping("/journals")
    @RequirePermissions(Permissions.INTERCOMPANY__READ)
    public PageResult<Map<String, Object>> journals(@RequestParam Map<String, String> query) {
        return journals.list(QueryParams.page(query));
    }

    @PostMapping("/journals")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INTERCOMPANY__POST)
    public Map<String, Object> postJournal(@Valid @RequestBody JournalRequest dto) {
        return journals.post(new JournalInput(dto.targetOrganizationId(), dto.date(), dto.description(), dto.amount(),
                dto.sourceAccountId(), dto.targetAccountId(), dto.targetAccountCode()));
    }

    // ---- reports --------------------------------------------------------------------------------------------------

    @GetMapping("/reconciliation")
    @RequirePermissions(Permissions.INTERCOMPANY__READ)
    public Map<String, Object> reconciliation(@RequestParam(required = false) String asOf) {
        return consolidation.reconciliation(asOf);
    }

    @GetMapping("/consolidation")
    @RequirePermissions(Permissions.INTERCOMPANY__READ)
    public Map<String, Object> consolidation(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String statement) {
        return consolidation.consolidation(from, to, statement);
    }
}
