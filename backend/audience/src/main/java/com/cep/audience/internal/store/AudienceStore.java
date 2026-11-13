package com.cep.audience.internal.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JDBC access to the audience tables. Every query is scoped by tenant. */
@Repository
public class AudienceStore {
    /** First generated key is AUD10001. */
    static final long FIRST_KEY_VALUE = 10_001;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AudienceStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record AudienceRow(UUID id, UUID tenantId, String key, String name, String status, int currentVersion,
                              UUID createdBy, UUID updatedBy, Instant createdAt, Instant updatedAt) {}

    public record VersionRow(UUID id, UUID audienceId, int version, JsonNode content, JsonNode compiled,
                             UUID createdBy, Instant createdAt) {}

    private static final String AUDIENCE_COLUMNS =
            "id, tenant_id, audience_key, name, status, current_version, created_by, updated_by, created_at, updated_at";

    public void insert(AudienceRow a) {
        jdbc.sql("INSERT INTO audiences (" + AUDIENCE_COLUMNS + ") VALUES "
                        + "(:id, :tenant, :key, :name, :status, :cv, :cb, :ub, :ca, :ua)")
                .param("id", a.id()).param("tenant", a.tenantId()).param("key", a.key()).param("name", a.name())
                .param("status", a.status()).param("cv", a.currentVersion()).param("cb", a.createdBy())
                .param("ub", a.updatedBy()).param("ca", ts(a.createdAt())).param("ua", ts(a.updatedAt())).update();
    }

    public void insertVersion(UUID tenantId, VersionRow v) {
        jdbc.sql("""
                INSERT INTO audience_versions (id, tenant_id, audience_id, version, content, compiled_criteria,
                                               created_by, created_at)
                VALUES (:id, :tenant, :aid, :v, CAST(:content AS jsonb), CAST(:compiled AS jsonb), :cb, :ca)
                """).param("id", v.id()).param("tenant", tenantId).param("aid", v.audienceId())
                .param("v", v.version()).param("content", mapper.writeValueAsString(v.content()))
                .param("compiled", mapper.writeValueAsString(v.compiled())).param("cb", v.createdBy())
                .param("ca", ts(v.createdAt())).update();
    }

    public void updateHeader(AudienceRow a) {
        jdbc.sql("""
                UPDATE audiences SET name = :name, status = :status, current_version = :cv, updated_by = :ub,
                       updated_at = :ua
                 WHERE id = :id AND tenant_id = :tenant
                """).param("name", a.name()).param("status", a.status()).param("cv", a.currentVersion())
                .param("ub", a.updatedBy()).param("ua", ts(a.updatedAt())).param("id", a.id())
                .param("tenant", a.tenantId()).update();
    }

    public Optional<AudienceRow> find(UUID tenantId, String key) {
        return jdbc.sql("SELECT " + AUDIENCE_COLUMNS + " FROM audiences WHERE tenant_id = :tenant AND audience_key = :key")
                .param("tenant", tenantId).param("key", key).query(this::audience).optional();
    }

    /** Locks the audience row until the end of the transaction, serializing concurrent changes. */
    public Optional<AudienceRow> lock(UUID tenantId, String key) {
        return jdbc.sql("SELECT " + AUDIENCE_COLUMNS
                        + " FROM audiences WHERE tenant_id = :tenant AND audience_key = :key FOR UPDATE")
                .param("tenant", tenantId).param("key", key).query(this::audience).optional();
    }

    public boolean exists(UUID tenantId, String key) {
        return jdbc.sql("SELECT count(*) FROM audiences WHERE tenant_id = :tenant AND audience_key = :key")
                .param("tenant", tenantId).param("key", key).query(Long.class).single() > 0;
    }

    public Optional<VersionRow> findVersion(UUID tenantId, UUID audienceId, int version) {
        return jdbc.sql("""
                SELECT id, audience_id, version, content::text AS content, compiled_criteria::text AS compiled,
                       created_by, created_at
                  FROM audience_versions WHERE tenant_id = :tenant AND audience_id = :aid AND version = :v
                """).param("tenant", tenantId).param("aid", audienceId).param("v", version)
                .query(this::version).optional();
    }

    public List<VersionRow> versions(UUID tenantId, UUID audienceId) {
        return jdbc.sql("""
                SELECT id, audience_id, version, content::text AS content, compiled_criteria::text AS compiled,
                       created_by, created_at
                  FROM audience_versions WHERE tenant_id = :tenant AND audience_id = :aid ORDER BY version DESC
                """).param("tenant", tenantId).param("aid", audienceId).query(this::version).list();
    }

    /**
     * @param status exact status, or null for every status except ARCHIVED
     * @param search case-insensitive substring of the key or name, or null
     */
    public List<AudienceRow> page(UUID tenantId, String status, String search, int offset, int limit) {
        return jdbc.sql("SELECT " + AUDIENCE_COLUMNS + " FROM audiences WHERE " + filter(status, search)
                        + " ORDER BY updated_at DESC, id DESC OFFSET :offset LIMIT :limit")
                .params(filterParams(tenantId, status, search)).param("offset", offset).param("limit", limit)
                .query(this::audience).list();
    }

    public long count(UUID tenantId, String status, String search) {
        return jdbc.sql("SELECT count(*) FROM audiences WHERE " + filter(status, search))
                .params(filterParams(tenantId, status, search)).query(Long.class).single();
    }

    private static String filter(String status, String search) {
        String sql = "tenant_id = :tenant AND " + (status == null ? "status <> 'ARCHIVED'" : "status = :status");
        if (search != null) {
            sql += " AND (audience_key ILIKE :q ESCAPE '\\' OR name ILIKE :q ESCAPE '\\')";
        }
        return sql;
    }

    private static java.util.Map<String, Object> filterParams(UUID tenantId, String status, String search) {
        var params = new java.util.HashMap<String, Object>();
        params.put("tenant", tenantId);
        if (status != null) {
            params.put("status", status);
        }
        if (search != null) {
            params.put("q", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return params;
    }

    /** Returns the next generated key number for the tenant (10001, 10002, ...). */
    public long nextKeyValue(UUID tenantId) {
        return jdbc.sql("""
                INSERT INTO audience_key_counters (tenant_id, next_value) VALUES (:tenant, :second)
                ON CONFLICT (tenant_id) DO UPDATE SET next_value = audience_key_counters.next_value + 1
                RETURNING next_value - 1
                """).param("tenant", tenantId).param("second", FIRST_KEY_VALUE + 1).query(Long.class).single();
    }

    private AudienceRow audience(ResultSet rs, int n) throws SQLException {
        return new AudienceRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("audience_key"), rs.getString("name"), rs.getString("status"),
                rs.getInt("current_version"), rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private VersionRow version(ResultSet rs, int n) throws SQLException {
        return new VersionRow(rs.getObject("id", UUID.class), rs.getObject("audience_id", UUID.class),
                rs.getInt("version"), mapper.readTree(rs.getString("content")), mapper.readTree(rs.getString("compiled")),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
