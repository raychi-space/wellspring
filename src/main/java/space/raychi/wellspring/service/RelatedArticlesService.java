package space.raychi.wellspring.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.RelatedArticle;
import space.raychi.wellspring.mapper.RelatedArticlesMapper;

@Service
public class RelatedArticlesService {
    private final RelatedArticlesMapper related;
    public RelatedArticlesService(RelatedArticlesMapper related) { this.related = related; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RelatedArticle> articles(String slug, int limit) {
        if (limit < 1 || limit > 6)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "相关文章数量须为 1～6。");
        var target = related.target(slug).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "内容不存在。"));
        String category = target.category() == null || "未分类".equals(target.category()) ? "" : target.category();
        return related.candidates(target.id(), category, limit).stream().map(row ->
                new RelatedArticle(row.id(), row.slug(), row.title(), row.summary(), row.category(), row.publishedAt())).toList();
    }
}
