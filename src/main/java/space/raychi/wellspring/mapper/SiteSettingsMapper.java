package space.raychi.wellspring.mapper;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.SiteSettingsEntity;

@Repository
public class SiteSettingsMapper {
    private final JdbcTemplate db;
    public SiteSettingsMapper(JdbcTemplate db) { this.db = db; }

    public SiteSettingsEntity select() {
        return db.queryForObject("SELECT value_json,version,updated_at FROM site_settings WHERE id=1",
                (rs, n) -> new SiteSettingsEntity(rs.getLong("version"), rs.getString("value_json"),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    public int update(SiteSettingsEntity value) {
        return db.update("UPDATE site_settings SET value_json=?, version=version+1, updated_at=? WHERE id=1 AND version=?",
                value.valueJson(), Timestamp.from(value.updatedAt()), value.version());
    }
}
