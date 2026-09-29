package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V3__backfill_legacy_tags extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> TAGS = new TypeReference<>() {};

    @Override
    public void migrate(Context context) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = context.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM tags")) {
            while (rows.next()) names.add(rows.getString(1));
        }

        Set<String> missing = new LinkedHashSet<>();
        try (Statement statement = context.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT draft_tags, public_tags FROM articles")) {
            while (rows.next()) {
                collect(missing, rows.getString(1));
                collect(missing, rows.getString(2));
            }
        }
        missing.removeAll(names);

        try (PreparedStatement insert = context.getConnection().prepareStatement(
                "INSERT INTO tags (name, created_at) VALUES (?, ?)")) {
            Timestamp now = Timestamp.from(Instant.now());
            for (String name : missing) {
                insert.setString(1, name);
                insert.setTimestamp(2, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void collect(Set<String> names, String stored) throws Exception {
        if (stored == null || stored.isBlank()) return;
        for (String name : JSON.readValue(stored, TAGS)) {
            if (name != null && !name.isBlank()) names.add(name);
        }
    }
}
