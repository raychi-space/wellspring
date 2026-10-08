package space.raychi.wellspring.service;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.api.PageResponse;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.TrashItem;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.entity.ArticleEntity;
import space.raychi.wellspring.entity.TrashItemEntity;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.AssetMapper;
import space.raychi.wellspring.mapper.PublicationSummaryMapper;
import space.raychi.wellspring.search.SearchState;

@Service
public class ContentTrashService {
    private final ArticleMapper articles;
    private final ArticleService contents;
    private final ContentHistoryService history;
    private final AssetMapper assets;
    private final PublicationSummaryMapper jobs;
    private final SearchState search;
    public ContentTrashService(ArticleMapper articles, ArticleService contents, ContentHistoryService history,
            AssetMapper assets, PublicationSummaryMapper jobs, SearchState search) {
        this.articles=articles; this.contents=contents; this.history=history; this.assets=assets; this.jobs=jobs; this.search=search;
    }
    @Transactional(readOnly=true)
    public PageResponse<TrashItem> list(int page, int pageSize) {
        if (page<1 || pageSize<1 || pageSize>50 || page>Integer.MAX_VALUE/pageSize) throw bad();
        return new PageResponse<>(articles.selectTrash(pageSize,(page-1)*pageSize).stream().map(ContentTrashService::item).toList(),
            page,pageSize,articles.countTrash());
    }
    @Transactional
    public TrashItem trash(String id, VersionInput input) {
        var row=required(id,input,false);
        history.baseline(row);
        articles.trash(id,Instant.now());
        jobs.cancel(id);
        search.capture(id,true);
        history.record(articles.selectById(id,false).orElseThrow(),"TRASH");
        return item(articles.selectTrashItem(id).orElseThrow());
    }
    @Transactional
    public AdminArticle restore(String id, VersionInput input) {
        var row=required(id,input,true);
        history.baseline(row);
        articles.recover(id,Instant.now());
        search.capture(id,false);
        history.record(articles.selectById(id,false).orElseThrow(),"RECOVER");
        return contents.getAdmin(id);
    }
    @Transactional
    public void purge(String id, VersionInput input) {
        required(id,input,true);
        search.capture(id,true);
        jobs.delete(id);
        assets.deleteByArticle(id);
        articles.delete(id);
    }
    private ArticleEntity required(String id, VersionInput input, boolean trashed) {
        if (input==null || input.expectedVersion()==null || input.expectedVersion()<0) throw bad();
        var row=articles.selectById(id,true).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND,"ARTICLE_NOT_FOUND","内容不存在。"));
        if (row.version()!=input.expectedVersion())
            throw new ApiException(HttpStatus.CONFLICT,"ARTICLE_VERSION_CONFLICT","内容已改变，请重新加载后重试。");
        if (row.status().equals("TRASHED")!=trashed)
            throw new ApiException(HttpStatus.NOT_FOUND,"ARTICLE_NOT_FOUND","内容不存在。");
        return row;
    }
    private static TrashItem item(TrashItemEntity row) {
        return new TrashItem(row.id(),row.slug(),row.type().equals("THOUGHT")?"POST":row.type(),row.title(),row.version(),row.trashedAt(),row.updatedAt());
    }
    private static ApiException bad() { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","请提供有效的版本或分页参数。"); }
}
