package space.raychi.wellspring.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchState {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private static final Parser MARKDOWN = Parser.builder().build();
    public record Desired(String id, long version, String action, Map<String,Object> document, int attempts) {}
    public SearchState(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }

    public static String plain(String markdown) {
        StringBuilder result=new StringBuilder();
        Node root=MARKDOWN.parse(markdown == null ? "" : markdown);
        root.accept(new AbstractVisitor() {
            public void visit(Text node) { result.append(node.getLiteral()); }
            public void visit(Code node) { result.append(node.getLiteral()); }
            public void visit(FencedCodeBlock node) { result.append(node.getLiteral()).append('\n'); }
            public void visit(IndentedCodeBlock node) { result.append(node.getLiteral()).append('\n'); }
            public void visit(SoftLineBreak node) { result.append(' '); }
            public void visit(HardLineBreak node) { result.append('\n'); }
            public void visit(Paragraph node) { visitChildren(node); result.append('\n'); }
            public void visit(Heading node) { visitChildren(node); result.append('\n'); }
            public void visit(Image node) { /* Do not index destinations or image markup. */ }
            public void visit(HtmlInline node) { }
            public void visit(HtmlBlock node) { }
        });
        return result.toString().strip();
    }
    private String encode(Object value) { try {return json.writeValueAsString(value);} catch(Exception ex){throw new IllegalStateException("Cannot encode search projection",ex);} }
    private Map<String,Object> decode(String value) { try {return json.readValue(value,new TypeReference<>(){});} catch(Exception ex){throw new IllegalStateException("Invalid search projection",ex);} }

    // Caller must hold the articles row lock in the same content transaction.
    public void capture(String contentId, boolean deleted) {
        String id="content:"+contentId;
        List<Map<String,Object>> rows=deleted?List.of():db.queryForList("SELECT status,content_type,public_title,public_body FROM articles WHERE id=?",contentId);
        Map<String,Object> doc=null;
        if(!rows.isEmpty() && "PUBLISHED".equals(rows.getFirst().get("status"))) {
            var row=rows.getFirst();String body=plain((String)row.get("public_body"));
            String type="ARTICLE".equals(row.get("content_type"))?"article":"post";
            String title=(String)row.get("public_title");
            if(title==null || title.isBlank()) title=body.isBlank()?"帖子":body.substring(0,Math.min(80,body.length()));
            doc=Map.of("title",title,"body",body,"type",type,"metadata",Map.of());
        }
        List<Long> versions=db.query("SELECT version FROM search_sync_state WHERE document_id=? FOR UPDATE",(rs,n)->rs.getLong(1),id);
        long version=versions.isEmpty()?1:Math.addExact(versions.getFirst(),1);
        if(version>9_007_199_254_740_991L)throw new IllegalStateException("Search version exhausted");
        String action=doc==null?"DELETE":"UPSERT", payload=doc==null?null:encode(doc);Timestamp now=Timestamp.from(Instant.now());
        if(versions.isEmpty())db.update("INSERT INTO search_sync_state(document_id,version,desired_action,payload_json,status,attempts,next_retry_at,last_error,updated_at) VALUES (?,?,?,?,'PENDING',0,?,NULL,?)",id,version,action,payload,now,now);
        else db.update("UPDATE search_sync_state SET version=?,desired_action=?,payload_json=?,status='PENDING',attempts=0,next_retry_at=?,last_error=NULL,updated_at=? WHERE document_id=?",version,action,payload,now,now,id);
    }
    @Transactional
    public void backfill() {
        // Lock in stable order, matching the publish lock order (content, then desired state).
        for(String id:db.query("SELECT id FROM articles ORDER BY id FOR UPDATE",(rs,n)->rs.getString(1))) {
            if(db.queryForObject("SELECT COUNT(*) FROM search_sync_state WHERE document_id=?",Long.class,"content:"+id)==0)capture(id,false);
        }
    }
    private Desired map(java.sql.ResultSet rs,int row)throws java.sql.SQLException {
        String payload=rs.getString("payload_json");return new Desired(rs.getString("document_id"),rs.getLong("version"),rs.getString("desired_action"),payload==null?null:decode(payload),rs.getInt("attempts"));
    }
    public List<Desired> due() {return db.query("SELECT * FROM search_sync_state WHERE status IN ('PENDING','RETRY') AND next_retry_at<=? ORDER BY next_retry_at,document_id LIMIT 100",this::map,Timestamp.from(Instant.now()));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Desired> snapshot() {return db.query("SELECT * FROM search_sync_state ORDER BY document_id",this::map);}
    public List<Map<String,Object>> entries(List<Desired> rows) {
        return rows.stream().map(r->{Map<String,Object> e=new LinkedHashMap<>();e.put("id",r.id());e.put("version",r.version());e.put("action",r.action());if(r.document()!=null)e.put("document",r.document());return e;}).toList();
    }
    public void ack(Desired r) {db.update("UPDATE search_sync_state SET status='SYNCED',last_error=NULL WHERE document_id=? AND version=?",r.id(),r.version());}
    public void fail(Desired r,int status) {
        boolean retry=status==0 || status==429 || status>=500;
        int attempts=Math.min(r.attempts()+1,1_000_000);long seconds=Math.min(60,1L<<Math.min(attempts,6));
        db.update("UPDATE search_sync_state SET status=?,attempts=?,next_retry_at=?,last_error=? WHERE document_id=? AND version=?",retry?"RETRY":"ERROR",attempts,Timestamp.from(Instant.now().plusSeconds(seconds)),status==0?"SEARCH_TRANSPORT_FAILURE":"SEARCH_HTTP_"+status,r.id(),r.version());
    }
    public Map<String,Object> diagnostics() {
        var rows=db.queryForList("SELECT status,COUNT(*) AS count,MIN(updated_at) AS oldest FROM search_sync_state GROUP BY status");
        return Map.of("states",rows,"interfaceVersion","v1");
    }
    public int requeue() {return db.update("UPDATE search_sync_state SET status='PENDING',attempts=0,next_retry_at=?,last_error=NULL WHERE status IN ('ERROR','RETRY')",Timestamp.from(Instant.now()));}
}
