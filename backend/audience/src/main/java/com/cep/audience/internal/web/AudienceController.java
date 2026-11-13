package com.cep.audience.internal.web;

import com.cep.audience.api.AudienceFacade.AudienceStatus;
import com.cep.audience.api.AudienceFacade.Evaluation;
import com.cep.audience.api.AudienceSubjectResolver;
import com.cep.audience.internal.service.AudienceService;
import com.cep.audience.internal.service.AudienceService.AudienceDetails;
import com.cep.audience.internal.service.AudienceService.AudienceListItem;
import com.cep.audience.internal.service.AudienceService.Page;
import com.cep.audience.internal.service.AudienceService.Validation;
import com.cep.audience.internal.service.AudienceService.VersionItem;
import com.cep.auth.api.CepPrincipal;
import com.cep.auth.api.CurrentActor;
import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.idempotency.IdempotencyService;
import com.cep.shared.api.util.Hashing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/audiences")
@Validated
@Tag(name = "Audiences", description = "Audience definitions, versions, activation and membership evaluation")
class AudienceController {
    private static final String IDEMPOTENCY_SCOPE = "audience.create";

    private final AudienceService audiences;
    private final IdempotencyService idempotency;
    private final ObjectProvider<AudienceSubjectResolver> subjects;
    private final ObjectMapper mapper;

    AudienceController(AudienceService audiences, IdempotencyService idempotency,
                       ObjectProvider<AudienceSubjectResolver> subjects, ObjectMapper mapper) {
        this.audiences = audiences;
        this.idempotency = idempotency;
        this.subjects = subjects;
        this.mapper = mapper;
    }

    record CreateRequest(@Size(max = 64) String audienceId, @Size(max = 200) String name, @NotNull JsonNode content) {}

    record UpdateRequest(@Size(max = 200) String name, JsonNode content) {}

    record ValidateRequest(@NotNull JsonNode content) {}

    record EvaluateRequest(@Size(max = 128) String customerId, JsonNode context, @Min(1) Integer version) {}

    @PostMapping
    @PreAuthorize("hasRole('MARKETER')")
    @Operation(summary = "Create an audience (version 1, DRAFT). Accepts an Idempotency-Key header")
    ResponseEntity<AudienceDetails> create(@Valid @RequestBody CreateRequest req,
                                           @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 200) String idemKey) {
        CepPrincipal actor = CurrentActor.require();
        AudienceDetails created;
        if (idemKey == null) {
            created = audiences.create(actor, req.audienceId(), req.name(), req.content());
        } else {
            created = idempotency.execute(actor.tenantId(), IDEMPOTENCY_SCOPE, idemKey,
                    Hashing.sha256Hex(mapper.writeValueAsString(req)), AudienceDetails.class,
                    () -> audiences.create(actor, req.audienceId(), req.name(), req.content())).value();
        }
        return ResponseEntity.created(URI.create("/api/v1/audiences/" + created.audienceId()))
                .eTag(etag(created.currentVersion())).body(created);
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    @Operation(summary = "List audiences, newest change first. ARCHIVED ones only with status=ARCHIVED")
    Page<AudienceListItem> list(@RequestParam(required = false) AudienceStatus status,
                                @RequestParam(required = false) @Size(max = 200) String q,
                                @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return audiences.list(CurrentActor.require().tenantId(), status, q, page, size);
    }

    @GetMapping("/{audienceId}")
    @PreAuthorize("hasRole('VIEWER')")
    @Operation(summary = "Get an audience with its current version, or with ?version=N")
    ResponseEntity<AudienceDetails> get(@PathVariable String audienceId,
                                        @RequestParam(required = false) @Min(1) Integer version) {
        AudienceDetails d = audiences.get(CurrentActor.require().tenantId(), audienceId, version);
        return ResponseEntity.ok().eTag(etag(d.currentVersion())).body(d);
    }

    @GetMapping("/{audienceId}/versions")
    @PreAuthorize("hasRole('VIEWER')")
    @Operation(summary = "Version history, newest first")
    List<VersionItem> versions(@PathVariable String audienceId) {
        return audiences.versions(CurrentActor.require().tenantId(), audienceId);
    }

    @PutMapping("/{audienceId}")
    @PreAuthorize("hasRole('MARKETER')")
    @Operation(summary = "Save new content as the next version (If-Match: \"<currentVersion>\" recommended)")
    ResponseEntity<AudienceDetails> update(@PathVariable String audienceId, @Valid @RequestBody UpdateRequest req,
                                           @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        AudienceDetails d = audiences.update(CurrentActor.require(), audienceId, req.name(), req.content(),
                parseIfMatch(ifMatch));
        return ResponseEntity.ok().eTag(etag(d.currentVersion())).body(d);
    }

    @PostMapping("/{audienceId}/activate")
    @PreAuthorize("hasRole('MARKETER')")
    @Operation(summary = "Make the audience ACTIVE")
    AudienceDetails activate(@PathVariable String audienceId) {
        return audiences.activate(CurrentActor.require(), audienceId);
    }

    @PostMapping("/{audienceId}/deactivate")
    @PreAuthorize("hasRole('MARKETER')")
    @Operation(summary = "Make the audience INACTIVE. Refused while an ACTIVE journey uses it")
    AudienceDetails deactivate(@PathVariable String audienceId) {
        return audiences.deactivate(CurrentActor.require(), audienceId);
    }

    @DeleteMapping("/{audienceId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Archive (soft delete). Refused while an ACTIVE journey uses it")
    AudienceDetails archive(@PathVariable String audienceId) {
        return audiences.archive(CurrentActor.require(), audienceId);
    }

    @PostMapping("/validate")
    @PreAuthorize("hasRole('VIEWER')")
    @Operation(summary = "Check audience content and show the compiled rule tree, without saving")
    Validation validate(@Valid @RequestBody ValidateRequest req) {
        return audiences.validate(req.content());
    }

    @PostMapping("/{audienceId}/evaluate")
    @PreAuthorize("hasRole('VIEWER') or hasAuthority('SCOPE_audiences:evaluate')")
    @Operation(summary = "Is a customer a member? Pass either customerId or an inline context")
    Evaluation evaluate(@PathVariable String audienceId, @Valid @RequestBody EvaluateRequest req) {
        CepPrincipal actor = CurrentActor.require();
        if ((req.customerId() == null) == (req.context() == null)) {
            throw ApiException.badRequest("EVALUATION_SUBJECT_REQUIRED", "Provide exactly one of customerId or context");
        }
        JsonNode context;
        if (req.context() != null) {
            if (!req.context().isObject()) {
                throw ApiException.badRequest("INVALID_CONTEXT", "context must be a JSON object");
            }
            context = req.context();
        } else {
            AudienceSubjectResolver resolver = subjects.getIfAvailable();
            if (resolver == null) {
                throw ApiException.unprocessable("CUSTOMER_RESOLUTION_UNAVAILABLE",
                        "Evaluating by customerId needs customer profiles, which are not available yet; pass context",
                        List.of());
            }
            Map<String, Object> resolved = resolver.resolve(actor.tenantId(), req.customerId())
                    .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
            context = mapper.valueToTree(resolved);
        }
        Evaluation result = audiences.evaluate(actor.tenantId(), audienceId, req.version(), context);
        if (actor.actorType() == CepPrincipal.ActorType.APPLICATION && result.status() != AudienceStatus.ACTIVE) {
            throw ApiException.conflict("AUDIENCE_NOT_ACTIVE", "Audience " + audienceId + " is " + result.status());
        }
        return result;
    }

    private static String etag(int version) {
        return "\"" + version + "\"";
    }

    private static Integer parseIfMatch(String header) {
        if (header == null || header.isBlank() || header.strip().equals("*")) {
            return null;
        }
        String v = header.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH", "If-Match must be \"<version>\"");
        }
    }
}
