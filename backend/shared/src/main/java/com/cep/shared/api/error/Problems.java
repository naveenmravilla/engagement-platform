package com.cep.shared.api.error;

import com.cep.shared.api.error.ApiException.FieldIssue;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the platform's uniform problem+json body: {type,title,status,detail,code,errors[],requestId}. */
public final class Problems {
    private Problems() {}

    public static ProblemDetail problem(HttpStatusCode status, String code, String detail, List<FieldIssue> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:cep:problem:" + code.toLowerCase().replace('_', '-')));
        pd.setProperty("code", code);
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
        return pd;
    }
}
