package space.raychi.wellspring.mapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileAvatarMapper {
    private final JdbcTemplate db;
    public ProfileAvatarMapper(JdbcTemplate db) { this.db = db; }
    public void lock() {
        db.queryForObject("SELECT version FROM site_settings WHERE id=1 FOR UPDATE", Long.class);
    }
    public void save(String id, byte[] bytes) {
        if (db.update("UPDATE profile_avatar SET image_id=?,image_bytes=? WHERE id=1", id, bytes) == 0)
            db.update("INSERT INTO profile_avatar(id,image_id,image_bytes) VALUES (1,?,?)", id, bytes);
    }
    public byte[] read(String id) {
        return db.query("SELECT image_bytes FROM profile_avatar WHERE id=1 AND image_id=?",
                (rs, n) -> rs.getBytes(1), id).stream().findFirst().orElse(null);
    }
}
