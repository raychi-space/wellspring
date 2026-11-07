package space.raychi.wellspring.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.AnalyticsSettingsEntity;

@Repository
public class AnalyticsSettingsMapper {
    private final JdbcTemplate db;
    public AnalyticsSettingsMapper(JdbcTemplate db) { this.db = db; }

    public AnalyticsSettingsEntity select() {
        return db.queryForObject("SELECT collection_enabled,version FROM analytics_settings WHERE id=1",
                (rs, n) -> new AnalyticsSettingsEntity(rs.getBoolean("collection_enabled"), rs.getLong("version")));
    }

    public int update(boolean enabled, long version, Instant now) {
        return db.update("UPDATE analytics_settings SET collection_enabled=?,version=version+1,updated_at=? WHERE id=1 AND version=?",
                enabled, Timestamp.from(now), version);
    }
}
