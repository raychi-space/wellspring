package space.raychi.wellspring.search;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
class SearchScheduling {}

@Component
public class SearchWorker {
    private final SearchState state;
    private final SearchClient client;
    private final ReentrantLock lock=new ReentrantLock();
    private volatile boolean ready;
    public SearchWorker(SearchState state,SearchClient client){this.state=state;this.client=client;}
    @EventListener(ApplicationReadyEvent.class)
    public void initialize(){if(client.enabled){state.backfill();ready=true;}}
    @Scheduled(fixedDelayString="${raychi.search.poll-ms:2000}")
    public void poll() {
        if(!ready||!client.enabled||!lock.tryLock())return;
        try {for(var row:state.due()){try{client.sync(row);state.ack(row);}catch(SearchClient.Failure ex){state.fail(row,ex.status);if(ex.status==0||ex.status==401||ex.status==403||ex.status>=500)break;}}}
        finally{lock.unlock();}
    }
    public Map<String,Object> restore() {
        lock.lock();
        try {state.backfill();var rows=state.snapshot();client.replace(state.entries(rows));rows.forEach(state::ack);return Map.of("restored",rows.size(),"interfaceVersion","v1");}
        finally {lock.unlock();}
    }
}
