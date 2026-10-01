package space.raychi.wellspring.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SearchClient {
    public static class Failure extends RuntimeException {
        public final int status;
        public Failure(int status) {super("Search operation failed");this.status=status;}
    }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final String base,namespace,token;
    public final boolean enabled;
    public SearchClient(ObjectMapper json,@Value("${raychi.search.url:http://127.0.0.1:8091}") String base,
        @Value("${raychi.search.namespace:raychi-public}") String namespace,@Value("${raychi.search.token:}") String token,
        @Value("${raychi.search.enabled:false}") boolean enabled) {
        this.json=json;this.base=base.replaceAll("/$","");this.namespace=namespace;this.token=token;this.enabled=enabled;
        URI uri=URI.create(base);
        if(!namespace.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}") || !Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalStateException("Invalid search configuration");
    }
    private JsonNode request(String method,String suffix,Object body,boolean maintenance) {
        if(!enabled)throw new Failure(0);
        if(token.length()<16)throw new Failure(401);
        try {
            var builder=HttpRequest.newBuilder(URI.create(base+"/v1/namespaces/"+namespace+suffix)).timeout(Duration.ofSeconds(maintenance?120:3)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new Failure(response.statusCode());
            return json.readTree(response.body());
        } catch(Failure ex){throw ex;} catch(InterruptedException ex){Thread.currentThread().interrupt();throw new Failure(0);} catch(Exception ex){throw new Failure(0);}
    }
    public void sync(SearchState.Desired r) {
        JsonNode response;
        if(r.action().equals("DELETE"))response=request("DELETE","/documents/"+r.id()+"?version="+r.version(),null,false);
        else {var body=new LinkedHashMap<>(r.document());body.put("version",r.version());response=request("PUT","/documents/"+r.id(),body,false);}
        if(response==null || !Set.of("applied","unchanged").contains(response.path("outcome").asText()) || response.path("version").asLong()!=r.version())throw new Failure(409);
    }
    public JsonNode search(String q,String type,int offset,int limit) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("q",q);if(type!=null)body.put("type",type);body.put("offset",offset);body.put("limit",limit);
        return request("POST","/search",body,false);
    }
    public void replace(List<Map<String,Object>> entries) {
        var result=request("POST","/index/replace",Map.of("entries",entries),true);
        if(result==null || result.path("replaced").asInt(-1)!=entries.size())throw new Failure(0);
    }
}
