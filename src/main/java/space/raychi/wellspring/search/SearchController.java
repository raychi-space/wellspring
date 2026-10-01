package space.raychi.wellspring.search;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import space.raychi.wellspring.api.ApiException;

@RestController
public class SearchController {
    private final SearchClient client;
    private final SearchState state;
    private final SearchWorker worker;
    private final JdbcTemplate db;
    public record Hit(String id,long version,String type,String title,String snippet,double score,String href) {}
    public record Result(List<Hit> hits,Integer nextOffset) {}
    public SearchController(SearchClient client,SearchState state,SearchWorker worker,JdbcTemplate db){this.client=client;this.state=state;this.worker=worker;this.db=db;}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","请输入 2～200 个字符的关键词，并使用有效的筛选和分页参数。");}
    private static ApiException unavailable(){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SEARCH_UNAVAILABLE","搜索暂时不可用，请稍后重试。");}
    @GetMapping("/api/v1/public/search")
    public ResponseEntity<Result> search(@RequestParam org.springframework.util.MultiValueMap<String,String> input) {
        if(input.values().stream().anyMatch(values->values.size()!=1))throw invalid();
        Map<String,String> params=input.toSingleValueMap();
        if(!Set.of("q","type","offset","limit").containsAll(params.keySet()))throw invalid();
        String q=params.getOrDefault("q","").strip();String type=params.get("type");int offset,limit;
        try{offset=Integer.parseInt(params.getOrDefault("offset","0"));limit=Integer.parseInt(params.getOrDefault("limit","20"));}catch(NumberFormatException ex){throw invalid();}
        if(q.codePointCount(0,q.length())<2||q.length()>200||offset<0||offset>200000||limit<1||limit>50||(type!=null&&!Set.of("article","post").contains(type))||q.matches(".*(?<![\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}](?![\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]).*"))throw invalid();
        JsonNode result;
        try{result=client.search(q,type,offset,limit);}catch(SearchClient.Failure ex){if(ex.status==422)throw invalid();throw unavailable();}
        if(result==null||!result.path("hits").isArray()||result.path("hits").size()>limit)throw unavailable();
        List<JsonNode> candidates=new ArrayList<>();result.path("hits").forEach(candidates::add);
        var ids=candidates.stream().map(h->h.path("id").asText()).filter(id->id.matches("content:[0-9a-f-]{36}")).distinct().toList();
        Map<String,Map<String,Object>> visible=new HashMap<>();
        if(!ids.isEmpty()) {
            String placeholders=ids.stream().map(id->"?").collect(Collectors.joining(","));
            for(var row:db.queryForList("SELECT a.id,a.slug,a.content_type,a.public_title,s.version FROM articles a JOIN search_sync_state s ON s.document_id=CONCAT('content:',a.id) WHERE a.status='PUBLISHED' AND s.desired_action='UPSERT' AND s.document_id IN ("+placeholders+")",ids.toArray()))visible.put("content:"+row.get("id"),row);
        }
        List<Hit> hits=new ArrayList<>();
        for(var candidate:candidates) {
            String id=candidate.path("id").asText();var current=visible.get(id);if(current==null||((Number)current.get("version")).longValue()!=candidate.path("version").asLong())continue;
            String kind="ARTICLE".equals(current.get("content_type"))?"article":"post";if(!kind.equals(candidate.path("type").asText())||(type!=null&&!type.equals(kind)))continue;
            String title=(String)current.get("public_title");if(title==null||title.isBlank())title=candidate.path("title").asText();
            String href=(kind.equals("article")?"/writing/":"/posts/")+current.get("slug");
            hits.add(new Hit(id,((Number)current.get("version")).longValue(),kind,title,candidate.path("snippet").asText(),candidate.path("score").asDouble(),href));
        }
        JsonNode next=result.get("nextOffset");Integer nextOffset=next==null||next.isNull()?null:next.asInt();
        if(nextOffset!=null&&(nextOffset<=offset||nextOffset>200000))throw unavailable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Result(hits,nextOffset));
    }
    @GetMapping("/api/v1/admin/search/status") public Map<String,Object> status(){return state.diagnostics();}
    @GetMapping("/api/v1/admin/search/export") public ResponseEntity<Map<String,Object>> export(){state.backfill();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("entries",state.entries(state.snapshot())));}
    @PostMapping("/api/v1/admin/search/retry") public Map<String,Integer> retry(){return Map.of("requeued",state.requeue());}
    @PostMapping("/api/v1/admin/search/restore") public Map<String,Object> restore(){try{return worker.restore();}catch(SearchClient.Failure ex){throw unavailable();}}
}
