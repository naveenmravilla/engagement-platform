package com.cep.app.ops;

import com.cep.auth.api.CurrentActor;
import com.cep.shared.api.audit.AuditLog;
import com.cep.shared.api.audit.AuditLog.AuditEntry;
import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.jobs.JobAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/jobs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Operations", description = "Dead-letter queue inspection and replay")
class JobAdminController {
    private final JobAdmin jobs;
    private final AuditLog audit;

    JobAdminController(JobAdmin jobs, AuditLog audit) {
        this.jobs = jobs;
        this.audit = audit;
    }

    @GetMapping("/dead")
    @Operation(summary = "List dead-lettered jobs of the caller's tenant")
    List<JobAdmin.DeadJob> dead(@RequestParam(required = false) String queue,
                                @RequestParam(defaultValue = "100") int limit) {
        return jobs.deadJobs(CurrentActor.require().tenantId(), queue, limit);
    }

    @PostMapping("/{id}/replay")
    @Transactional
    @Operation(summary = "Re-queue a dead job with a fresh attempt budget")
    void replay(@PathVariable UUID id) {
        var actor = CurrentActor.require();
        if (!jobs.replay(actor.tenantId(), id)) {
            throw ApiException.notFound("DEAD_JOB_NOT_FOUND", "No dead job with this id");
        }
        audit.record(new AuditEntry(actor.tenantId(), actor.actorType().name(), actor.actorId().toString(), "JOB_REPLAYED",
                "job", id.toString(), null, null));
    }
}
