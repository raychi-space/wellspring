package space.raychi.wellspring.reading;

import static space.raychi.wellspring.reading.ReadingManagementModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import space.raychi.wellspring.api.ApiException;

@Service
public class ReadingManagementService {
    private final AiHotClient client;
    private final ObjectMapper json;
    public ReadingManagementService(AiHotClient client, ObjectMapper json) { this.client=client; this.json=json; }
    public Dashboard dashboard(Map<String,String> q) {
        keys(q, Set.of("status","kind","page"));
        option(q,"status",Set.of("all","failed","running","ok","skipped","unknown"));
        option(q,"kind",Set.of("all","schedule","collection","model")); page(q);
        return read("/dashboard"+query(q),Dashboard.class);
    }
    public Sources sources(Map<String,String> q) {
        keys(q,Set.of("q","enabled","health","page")); page(q);
        if(q.getOrDefault("q","").length()>100) throw invalid();
        option(q,"enabled",Set.of("true","false")); option(q,"health",Set.of("ok","degraded","failing","paused","unknown"));
        return read("/sources"+query(q),Sources.class);
    }
    public Source source(String id) { return read("/sources/"+sourceId(id),Source.class); }
    public Source create(SourceCreate value) { feed(value.feedUrl()); return write("POST","/sources",value,Source.class); }
    public Source update(String id, SourceUpdate value) { feed(value.feedUrl()); return write("PUT","/sources/"+sourceId(id),value,Source.class); }
    public Check check(String id) { return write("POST","/sources/"+sourceId(id)+"/check",json.createObjectNode(),Check.class); }
    public Collection collect(String id) { return write("POST","/sources/"+sourceId(id)+"/collect",json.createObjectNode(),Collection.class); }
    public Models models() { return read("/models",Models.class); }
    public Models switchModel(String capability, JsonNode value) {
        if(!value.isObject() || value.size()!=1 || !value.has("model") || (!value.get("model").isNull()
                && (!value.get("model").isTextual() || !value.get("model").asText().matches("[A-Za-z0-9_.-]{1,100}")))) throw invalid();
        return write("PUT","/models/"+key(capability),value,Models.class);
    }
    public Models budget(String service, BudgetInput value) { return write("PUT","/budgets/"+key(service),value,Models.class); }

    private <T>T read(String path,Class<T> type) { return dto(client.manage("GET",path,null),type); }
    private <T>T write(String method,String path,Object value,Class<T> type) { return dto(client.manage(method,path,json.valueToTree(value)),type); }
    private <T>T dto(JsonNode value,Class<T> type) {
        try {
            if (!value.isObject()) throw new IllegalStateException();
            T result=json.readerFor(type).with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).readValue(value);
            if (result instanceof Dashboard d && (d.counts()==null||d.processes()==null||d.sources()==null||d.tasks()==null||d.trend()==null||d.trend().size()!=7||d.budgets()==null)) throw new IllegalStateException();
            if (result instanceof Models m && (m.choices()==null||m.capabilities()==null||m.budgets()==null||m.summary()==null)) throw new IllegalStateException();
            if (result instanceof Sources s && s.items()==null) throw new IllegalStateException();
            return result;
        }
        catch(Exception ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"READING_MANAGEMENT_UNAVAILABLE","拾阅管理数据暂时不可用。"); }
    }
    private static String sourceId(String id) { if(!id.matches("[a-z0-9][a-z0-9-]{2,79}")) throw invalid(); return id; }
    private static String key(String value) { if(!value.matches("[A-Za-z][A-Za-z0-9_-]{0,99}")) throw invalid(); return value; }
    private static void feed(String value) {
        try { URI u=URI.create(value); if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null) throw invalid(); }
        catch(IllegalArgumentException ex) { throw invalid(); }
    }
    private static void keys(Map<String,String> q,Set<String> keys) { if(!keys.containsAll(q.keySet())) throw invalid(); }
    private static void option(Map<String,String> q,String name,Set<String> allowed) { if(q.containsKey(name)&&!allowed.contains(q.get(name))) throw invalid(); }
    private static void page(Map<String,String> q) {
        try { if(q.containsKey("page")&&(!q.get("page").matches("[1-9][0-9]{0,5}")||Integer.parseInt(q.get("page"))>100000)) throw invalid(); }
        catch(NumberFormatException ex) { throw invalid(); }
    }
    private static String query(Map<String,String> q) { return q.isEmpty()?"":"?"+q.entrySet().stream().map(e->e.getKey()+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","请检查拾阅查询或配置。"); }
}
