package com.cep.auth.internal.web;

import com.cep.auth.api.CurrentActor;
import com.cep.auth.internal.domain.Application;
import com.cep.auth.internal.domain.User;
import com.cep.auth.internal.service.ApplicationService;
import com.cep.auth.internal.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration", description = "Integration applications (API keys) and dashboard users")
class AdminController {
    private final ApplicationService applications;
    private final UserService users;

    AdminController(ApplicationService applications, UserService users) {
        this.applications = applications;
        this.users = users;
    }

    record CreateApplicationRequest(@NotBlank @Size(max = 200) String name, Set<String> scopes,
                                    @Min(1) @Max(100_000) Integer rateLimitPerMinute) {}

    record ApplicationView(UUID id, String name, String apiKeyPrefix, Set<String> scopes, int rateLimitPerMinute,
                           String status, Instant createdAt, Instant lastUsedAt) {
        static ApplicationView of(Application a) {
            return new ApplicationView(a.getId(), a.getName(), a.getApiKeyPrefix(), a.getScopes(), a.getRateLimitPerMin(),
                    a.getStatus(), a.getCreatedAt(), a.getLastUsedAt());
        }
    }

    /** The raw key is returned exactly once, at creation. */
    record CreatedApplicationView(ApplicationView application, String apiKey) {}

    record CreateUserRequest(@NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 200) String password,
                             @Size(max = 200) String displayName, @NotEmpty Set<String> roles) {}

    record UserView(UUID id, String email, String displayName, Set<String> roles, String status, Instant createdAt,
                    Instant lastLoginAt) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getDisplayName(), u.getRoles(), u.getStatus(),
                    u.getCreatedAt(), u.getLastLoginAt());
        }
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an integration application and issue its API key (shown once)")
    CreatedApplicationView createApplication(@Valid @RequestBody CreateApplicationRequest req) {
        var created = applications.create(CurrentActor.require(), req.name(), req.scopes(), req.rateLimitPerMinute());
        return new CreatedApplicationView(ApplicationView.of(created.application()), created.rawApiKey());
    }

    @GetMapping("/applications")
    @Operation(summary = "List integration applications of the caller's tenant")
    List<ApplicationView> listApplications() {
        return applications.list(CurrentActor.require().tenantId()).stream().map(ApplicationView::of).toList();
    }

    @PostMapping("/applications/{id}/revoke")
    @Operation(summary = "Revoke an application's API key")
    ApplicationView revoke(@PathVariable UUID id) {
        return ApplicationView.of(applications.revoke(CurrentActor.require(), id));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a dashboard user (roles: ADMIN, MARKETER, VIEWER)")
    UserView createUser(@Valid @RequestBody CreateUserRequest req) {
        return UserView.of(users.create(CurrentActor.require(), req.email(), req.password(), req.displayName(), req.roles()));
    }

    @GetMapping("/users")
    @Operation(summary = "List dashboard users of the caller's tenant")
    List<UserView> listUsers() {
        return users.list(CurrentActor.require().tenantId()).stream().map(UserView::of).toList();
    }
}
