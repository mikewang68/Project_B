package com.bproject.ehm.platform.persistence.opengauss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * openGauss上的聚合存储基线。
 *
 * 当前Demo以JSON文本保存完整领域聚合，避免数据库切换侵入领域层和Web接口；聚合类型和主键
 * 独立建索引，后续可以按模块渐进拆成规范化业务表，而不影响现有Repository端口。
 */
@Repository
@Profile("server")
public class OpenGaussJsonStore {
    private String scope(){
        var worker=com.bproject.ehm.simulation.SimulationRunScope.current();
        if(worker!=null){
            if(com.bproject.ehm.platform.security.EhmCentralScope.current()!=null||!project().equals(worker.projectId()))
                throw new IllegalStateException("SIMULATOR_SCOPE_CONFLICT");
            return " AND o.subsystem_code='EHMS' "+com.bproject.iam.context.ScopeSql.build(
                com.bproject.iam.context.ScopeSql.Mode.SCOPED,List.of(worker),com.bproject.iam.context.ScopeSql.Columns.ORDER).my_info();
        }
        return com.bproject.ehm.platform.security.EhmCentralScope.readPredicate();
    }
    private com.bproject.iam.context.ScopeTriple tuple(){
        var worker=com.bproject.ehm.simulation.SimulationRunScope.current();
        if(worker!=null){
            if(com.bproject.ehm.platform.security.EhmCentralScope.current()!=null||!project().equals(worker.projectId()))
                throw new IllegalStateException("SIMULATOR_SCOPE_CONFLICT");
            return worker;
        }
        return com.bproject.ehm.platform.security.EhmCentralScope.writeTuple(project());
    }
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String qualifiedTable;
    private final String schema;
    private final boolean autoDdl;
    @Value("${ehm.project.default-id:UNASSIGNED}") private String defaultProject="UNASSIGNED";
    private String project(){return com.bproject.ehm.platform.security.ProjectContext.require(defaultProject);}

    public OpenGaussJsonStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${ehm.persistence.schema:ehm}") String schema,
            @Value("${ehm.persistence.table:aggregate_store}") String table,
            @Value("${ehm.persistence.auto-ddl:false}") boolean autoDdl
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.schema = safeIdentifier(schema, "schema");
        this.qualifiedTable = this.schema + "." + safeIdentifier(table, "table");
        this.autoDdl = autoDdl;
    }

    @PostConstruct
    void initialize() {
        if (!autoDdl) return;
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    project_id VARCHAR(64) NOT NULL,
                    region_id VARCHAR(64) NOT NULL DEFAULT 'UNASSIGNED',
                    subsystem_code VARCHAR(64) NOT NULL DEFAULT 'EHMS',
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(192) NOT NULL,
                    payload TEXT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (project_id, region_id, subsystem_code, aggregate_type, aggregate_id)
                )
                """.formatted(qualifiedTable));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ehm_aggregate_updated ON "
                + qualifiedTable + " (project_id, aggregate_type, updated_at DESC)");
    }

    public <T> Optional<T> find(String type, String id, Class<T> valueType) {
        List<String> rows = jdbc.query(
                "SELECT payload FROM " + qualifiedTable + " o WHERE project_id = ? AND aggregate_type = ? AND aggregate_id = ?"+scope(),
                (result, rowNum) -> result.getString(1), project(), type, id);
        if(rows.size()>1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Select a single region to resolve this aggregate ID");
        return rows.stream().findFirst().map(value -> decode(value, valueType));
    }

    public <T> List<T> findAll(String type, Class<T> valueType) {
        return jdbc.query(
                "SELECT payload FROM " + qualifiedTable + " o WHERE project_id = ? AND aggregate_type = ?"+scope()+" ORDER BY updated_at DESC",
                (result, rowNum) -> decode(result.getString(1), valueType), project(), type);
    }

    private volatile Boolean h2Dialect;
    private boolean isH2(){
        if(h2Dialect==null)h2Dialect=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
            connection->connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2"));
        return Boolean.TRUE.equals(h2Dialect);
    }
    private record TimedPayload(Instant at,String id,String payload){}
    /** Server-side scoped filtering, chronological ordering and LIMIT; never hydrate the full history. */
    public <T> List<T> recent(String type,String field,String value,String timeField,Instant from,Instant to,int limit,Class<T> valueType){
        if(field!=null&&(!java.util.Set.of("assetCode","pointCode").contains(field)||value==null)
            ||!java.util.Set.of("generatedAt","sourceTimestamp","occurredAt").contains(timeField)
            ||limit<1||limit>10000||from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException("Invalid bounded history query");
        String base=" FROM "+qualifiedTable+" o WHERE project_id=? AND aggregate_type=?"+scope();
        if(isH2()){
            // Test-database dialect: bounded heap, identical exact-field/time semantics. No production fallback.
            var order=java.util.Comparator.comparing(TimedPayload::at,java.util.Comparator.nullsFirst(java.util.Comparator.<Instant>naturalOrder())).thenComparing(TimedPayload::id);
            var selected=new java.util.PriorityQueue<TimedPayload>(order);
            jdbc.query("SELECT aggregate_id,payload"+base,(org.springframework.jdbc.core.RowCallbackHandler)row->{
                String payload=row.getString(2);com.fasterxml.jackson.databind.JsonNode json;
                try{json=objectMapper.readTree(payload);}catch(JsonProcessingException ex){throw new IllegalStateException("Invalid persisted history",ex);}
                if(field!=null&&!value.equals(json.path(field).asText()))return;
                var timeNode=json.path(timeField);String raw=timeNode.asText("");Instant at;
                if(timeNode.isNumber()){
                    var seconds=timeNode.decimalValue();long whole=seconds.longValue();at=Instant.ofEpochSecond(whole,seconds.subtract(java.math.BigDecimal.valueOf(whole)).movePointRight(9).longValue());
                }else at=raw.isBlank()?null:Instant.parse(raw);
                if(from!=null&&(at==null||at.isBefore(from))||to!=null&&(at==null||at.isAfter(to)))return;
                var candidate=new TimedPayload(at,row.getString(1),payload);selected.add(candidate);if(selected.size()>limit)selected.poll();
            },project(),type);
            return selected.stream().sorted(order.reversed()).map(row->decode(row.payload(),valueType)).toList();
        }
        String rawTime="json_extract_path_text(CAST(o.payload AS json),'"+timeField+"')";
        String time="(CASE WHEN "+rawTime+" ~ '^-?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$' THEN to_timestamp(CAST("+rawTime+" AS DOUBLE PRECISION)) ELSE CAST(NULLIF("+rawTime+",'') AS TIMESTAMP WITH TIME ZONE) END)";
        var params=new java.util.ArrayList<Object>();params.add(project());params.add(type);
        if(field!=null){base+=" AND json_extract_path_text(CAST(o.payload AS json),'"+field+"')=?";params.add(value);}
        if(from!=null){base+=" AND "+time+">=?";params.add(Timestamp.from(from));}
        if(to!=null){base+=" AND "+time+"<=?";params.add(Timestamp.from(to));}
        params.add(limit);
        return jdbc.query("SELECT payload"+base+" ORDER BY "+time+" DESC NULLS LAST,aggregate_id DESC LIMIT ?",
            (row,n)->decode(row.getString(1),valueType),params.toArray());
    }

    /** Restrict synthetic history reads to one point; timestamps are still filtered in the adapter. */
    public <T> List<T> findByIdPrefix(String type,String prefix,Class<T> valueType){
        String escaped=prefix.replace("!","!!").replace("%","!%").replace("_","!_");
        return jdbc.query("SELECT payload FROM "+qualifiedTable+" o WHERE project_id=? AND aggregate_type=? AND aggregate_id LIKE ? ESCAPE '!'"+scope(),
                (r,n)->decode(r.getString(1),valueType),project(),type,escaped+"%");
    }

    public <T> T save(String type, String id, T value) {
        var tuple=tuple();
        String payload = encode(value);
        Timestamp updatedAt = Timestamp.from(Instant.now());
        int updated = jdbc.update(
                "UPDATE " + qualifiedTable + " SET payload = ?, updated_at = ? "
                        + "WHERE project_id = ? AND aggregate_type = ? AND aggregate_id = ? AND region_id = ? AND subsystem_code = ?",
                payload, updatedAt, project(), type, id,tuple.regionId(),tuple.subsystemCode());
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO " + qualifiedTable
                                + " (project_id, aggregate_type, aggregate_id, payload, updated_at,region_id,subsystem_code) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        project(), type, id, payload, updatedAt,tuple.regionId(),tuple.subsystemCode());
            } catch (DuplicateKeyException concurrentInsert) {
                jdbc.update(
                        "UPDATE " + qualifiedTable + " SET payload = ?, updated_at = ? "
                                + "WHERE project_id = ? AND aggregate_type = ? AND aggregate_id = ? AND region_id = ? AND subsystem_code = ?",
                        payload, updatedAt, project(), type, id,tuple.regionId(),tuple.subsystemCode());
            }
        }
        return value;
    }

    public long count(String type) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTable + " o WHERE project_id = ? AND aggregate_type = ?"+scope(), Long.class, project(), type);
        return value == null ? 0 : value;
    }

    /** Compare-and-swap for new versioned aggregates; no last-write-wins on concurrent dossier edits. */
    public <T> T saveVersioned(String type,String id,T value,long expectedVersion) {
        var tuple=tuple();String project=project();
        var rows=jdbc.query("SELECT payload FROM "+qualifiedTable+" WHERE project_id=? AND region_id=? AND subsystem_code=? AND aggregate_type=? AND aggregate_id=?",
                (r,n)->r.getString(1),project,tuple.regionId(),tuple.subsystemCode(),type,id);
        String payload=encode(value);Timestamp now=Timestamp.from(Instant.now());
        if(rows.isEmpty()) {
            if(expectedVersion!=0)throw new com.bproject.ehm.shared.error.DomainConflictException("设备资料已变更，请刷新后重试");
            try {jdbc.update("INSERT INTO "+qualifiedTable+" (project_id,region_id,subsystem_code,aggregate_type,aggregate_id,payload,updated_at) VALUES (?,?,?,?,?,?,?)",
                    project,tuple.regionId(),tuple.subsystemCode(),type,id,payload,now);}
            catch(DuplicateKeyException ex){throw new com.bproject.ehm.shared.error.DomainConflictException("设备资料已被其他人保存，请刷新后重试");}
        } else {
            try {if(objectMapper.readTree(rows.get(0)).path("version").asLong()!=expectedVersion)
                throw new com.bproject.ehm.shared.error.DomainConflictException("设备资料版本冲突，请刷新后重试");}
            catch(JsonProcessingException ex){throw new IllegalStateException("资料版本读取失败",ex);}
            int changed=jdbc.update("UPDATE "+qualifiedTable+" SET payload=?,updated_at=? WHERE project_id=? AND region_id=? AND subsystem_code=? AND aggregate_type=? AND aggregate_id=? AND payload=?",
                    payload,now,project,tuple.regionId(),tuple.subsystemCode(),type,id,rows.get(0));
            if(changed!=1)throw new com.bproject.ehm.shared.error.DomainConflictException("设备资料同时发生修改，请刷新后重试");
        }
        return value;
    }

    /** Aggregate-only pulse, subject to exactly the same trusted scope as every business read. */
    public List<java.util.Map<String,Object>> activity(){
        return jdbc.query("SELECT aggregate_type,COUNT(*) AS total,MAX(updated_at) AS last_at FROM "+qualifiedTable
            +" o WHERE project_id=? AND aggregate_type IN ('asset','sample-telemetry','device-snapshot','alarm','work-order','health-assessment','user-task','shift-handover')"
            +scope()+" GROUP BY aggregate_type",(r,n)->java.util.Map.of("type",r.getString(1),"total",r.getLong(2),
                "lastChangedAt",r.getTimestamp(3).toInstant().toString()),project());
    }

    /** Infrastructure-only append. Read-only users still generate logs, never business writes. */
    com.bproject.ehm.platform.audit.domain.model.AuditRecord appendAudit(com.bproject.ehm.platform.audit.domain.model.AuditRecord value){
        var context=com.bproject.ehm.platform.security.EhmCentralScope.current();String region="UNASSIGNED";
        if(context!=null&&context.scopeMode()==com.bproject.iam.context.ScopeSql.Mode.SCOPED&&context.activeScope().size()==1){
            var selected=context.activeScope().get(0);
            if(!selected.projectId().equals(project())||!selected.subsystemCode().equals("EHMS"))throw new IllegalStateException("Audit scope mismatch");
            region=selected.regionId();
        }
        jdbc.update("INSERT INTO "+qualifiedTable+" (project_id,region_id,subsystem_code,aggregate_type,aggregate_id,payload,updated_at) VALUES (?,?,?,?,?,?,?)",
            project(),region,"EHMS","audit",value.auditId(),encode(value),Timestamp.from(Instant.now()));
        return value;
    }

    /** Insert-only helper for opt-in sample bundles: never resets an existing business aggregate. */
    public boolean insertIfAbsent(String type, String id, Object value) {
        var tuple=tuple();
        if (find(type, id, Object.class).isPresent()) return false;
        try {
            jdbc.update("INSERT INTO " + qualifiedTable
                    + " (project_id, aggregate_type, aggregate_id, payload, updated_at,region_id,subsystem_code) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    project(), type, id, encode(value), Timestamp.from(Instant.now()),tuple.regionId(),tuple.subsystemCode());
            return true;
        } catch (DuplicateKeyException race) {
            throw new IllegalStateException("Concurrent sample import; retry after the current import finishes");
        }
    }

    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }

    /** Exact write tuple only; existing business row is the cross-JVM serialization key. */
    public void lockAggregate(String type,String id) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Aggregate lock requires a transaction");
        var selected=tuple();
        jdbc.queryForList("SELECT aggregate_id FROM "+qualifiedTable
            +" WHERE project_id=? AND region_id=? AND subsystem_code=? AND aggregate_type=? AND aggregate_id=? FOR UPDATE",
            project(),selected.regionId(),selected.subsystemCode(),type,id);
    }

    public com.bproject.iam.context.ScopeTriple readTuple(String type,String id) {
        var rows=jdbc.query("SELECT region_id,subsystem_code FROM "+qualifiedTable
            +" o WHERE project_id=? AND aggregate_type=? AND aggregate_id=?"+scope(),
            (r,n)->new com.bproject.iam.context.ScopeTriple(project(),r.getString(1),r.getString(2)),project(),type,id);
        if(rows.size()!=1)throw new com.bproject.ehm.shared.error.DomainConflictException("请选择唯一设备数据作用域");
        return rows.get(0);
    }

    /** Caller must hold a transaction. Serializes a persisted simulation controller across JVMs. */
    public void lockSimulationControl() {
        var tuple=tuple();
        jdbc.queryForObject("SELECT aggregate_id FROM " + qualifiedTable
                + " WHERE project_id=? AND region_id=? AND subsystem_code=?"
                + " AND aggregate_type='simulation-control' AND aggregate_id='control' FOR UPDATE",
                String.class, project(),tuple.regionId(),tuple.subsystemCode());
    }

    /** Retention applies ONLY to this simulator's raw samples; never business/audit records. */
    public int pruneSimulationSamples(Instant before) {
        if(!project().startsWith("SIM_"))throw new IllegalStateException("Not a simulation project");
        var tuple=tuple();
        return jdbc.update("DELETE FROM " + qualifiedTable
                + " WHERE project_id=? AND region_id=? AND subsystem_code=? AND aggregate_type='sample-telemetry'"
                + " AND aggregate_id LIKE 'SAMPLE-LIVE-%' AND updated_at<?",
                project(),tuple.regionId(),tuple.subsystemCode(),Timestamp.from(before));
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("openGauss聚合序列化失败", exception);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("openGauss聚合反序列化失败：" + type.getSimpleName(), exception);
        }
    }

    private static String safeIdentifier(String value, String label) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法openGauss " + label + "标识符");
        }
        return value;
    }
}
