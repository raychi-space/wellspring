package space.raychi.wellspring.mapper;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.AssetEntity;

@Repository
public class AssetMapper {
    private final JdbcTemplate db;
    public AssetMapper(JdbcTemplate db) { this.db = db; }

    public boolean belongsToArticle(String id, String articleId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM assets WHERE id=? AND article_id=?", Integer.class, id, articleId);
        return count != null && count > 0;
    }

    public void replacePublishedReferences(String articleId, Set<String> assetIds) {
        db.update("DELETE FROM published_assets WHERE article_id=?", articleId);
        for (String id : assetIds) db.update("INSERT INTO published_assets (article_id,asset_id) VALUES (?,?)", articleId, id);
    }

    public void deleteByArticle(String articleId) {
        db.update("DELETE FROM published_assets WHERE article_id=?", articleId);
        db.update("DELETE FROM assets WHERE article_id=?", articleId);
    }

    public void insert(AssetEntity value) {
        db.update("""
            INSERT INTO assets (id,article_id,storage_key,media_type,byte_size,width_px,height_px,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, value.id(), value.articleId(), value.key(), value.mediaType(), value.byteSize(), value.width(), value.height(),
                Timestamp.from(value.createdAt()));
    }

    public Optional<AssetEntity> selectById(String id) {
        return db.query("SELECT * FROM assets WHERE id=?",
                (rs, n) -> new AssetEntity(rs.getString("id"), rs.getString("article_id"), rs.getString("storage_key"),
                        rs.getString("media_type"), rs.getLong("byte_size"), rs.getInt("width_px"), rs.getInt("height_px"),
                        rs.getTimestamp("created_at").toInstant()), id).stream().findFirst();
    }

    public boolean isPublishedReference(String id, String articleId) {
        Integer count = db.queryForObject("""
            SELECT COUNT(*) FROM published_assets pa JOIN articles a ON a.id=pa.article_id
            WHERE pa.asset_id=? AND pa.article_id=? AND a.status='PUBLISHED'
            """, Integer.class, id, articleId);
        return count != null && count > 0;
    }
}
