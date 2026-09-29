package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class TagNameCollation {
    private TagNameCollation() {}

    static void preserveCaseDistinctNames(Connection connection) throws SQLException {
        if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) return;
        // v0.1 tags used Java String equality; MySQL's default collation merges names such as Tech and tech.
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE tags MODIFY name VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL");
        }
    }
}
