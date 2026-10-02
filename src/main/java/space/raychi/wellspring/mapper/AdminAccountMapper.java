package space.raychi.wellspring.mapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.AdminAccount;

@Repository
public class AdminAccountMapper {
    private final JdbcTemplate db;
    public AdminAccountMapper(JdbcTemplate db) { this.db = db; }

    public AdminAccount select() {
        return db.query("SELECT username,password_hash,credential_version FROM admin_account WHERE id=1",
                (rs, n) -> new AdminAccount(rs.getString("username"), rs.getString("password_hash"),
                        rs.getLong("credential_version"))).stream().findFirst().orElse(null);
    }

    public void initialize(String username, String hash) {
        try {
            db.update("INSERT INTO admin_account (id,username,password_hash,credential_version) VALUES (1,?,?,1)", username, hash);
        } catch (DuplicateKeyException alreadyInitialized) {
            // Another application instance initialized the singleton account first.
        }
    }

    public boolean hasVersion(String username, long version) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT COUNT(*)=1 FROM admin_account WHERE id=1 AND username=? AND credential_version=?",
                Boolean.class, username, version));
    }

    public int changePassword(String username, long version, String hash) {
        return db.update("UPDATE admin_account SET password_hash=?, credential_version=credential_version+1 WHERE id=1 AND username=? AND credential_version=?",
                hash, username, version);
    }
}
