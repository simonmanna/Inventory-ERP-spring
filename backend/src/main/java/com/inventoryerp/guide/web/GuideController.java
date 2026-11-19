package com.inventoryerp.guide.web;

import com.inventoryerp.guide.application.GuideProgressService;
import com.inventoryerp.guide.application.GuideProgressService.Progress;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /guide/progress}: the signed-in user's own progress through the in-app guided tours (ADR-S079). Open to every
 * signed-in user (it is their own learning data); each tour decides in the client which permissions it needs.
 */
@RestController
@RequestMapping("/guide/progress")
public class GuideController {

    /** Where the user is in a tour. */
    public record SaveProgressRequest(
            @NotNull(message = "tourVersion must be an integer number")
            @Min(value = 1, message = "tourVersion must not be less than 1")
            @Max(value = 10000, message = "tourVersion must not be greater than 10000") Integer tourVersion,
            @NotNull(message = "status must be one of the following values: in_progress, completed, dismissed")
            @OneOf(value = {"in_progress", "completed", "dismissed"}) String status,
            @NotNull(message = "currentStep must be an integer number")
            @Min(value = 0, message = "currentStep must not be less than 0")
            @Max(value = 500, message = "currentStep must not be greater than 500") Integer currentStep) {}

    private final GuideProgressService progress;

    public GuideController(GuideProgressService progress) {
        this.progress = progress;
    }

    @GetMapping
    public List<Progress> list(AuthUser user) {
        return progress.list(user);
    }

    @PutMapping("/{tourKey}")
    public Progress save(AuthUser user, @PathVariable String tourKey, @Valid @RequestBody SaveProgressRequest body) {
        return progress.save(user, tourKey, body.tourVersion(), body.status(), body.currentStep());
    }

    @DeleteMapping("/{tourKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(AuthUser user, @PathVariable String tourKey) {
        progress.reset(user, tourKey);
    }
}
