package space.raychi.wellspring.tools;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.List;
import java.util.TimeZone;
import org.flywaydb.core.Flyway;

/** Offline, bounded migration command; never starts Spring, workers or HTTP listeners. */
public final class DatabaseTransfer {
    // Foreign-key parents first. No kernel databases, files or Flyway history are copied.
    private static final List<String> TABLES = List.of("admin_account", "categories", "tags", "site_settings",
            "articles", "assets", "published_assets", "public_content_tags", "content_revisions",
            "publication_summaries", "asset_deletion_queue", "search_sync_state", "reading_settings",
            "reading_sources", "reading_articles", "analytics_settings");
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    private record Column(String name, int type) {}
    private record Fingerprint(long rows, String sha256) {}

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !List.of("--copy", "--verify").contains(args[0]))
                throw new IllegalArgumentException("Use --copy for a new empty PG database or --verify");
            run(args[0].equals("--copy"));
        } catch (Exception error) {
            // JDBC errors may include row values or connection credentials. Keep them out of logs.
            System.err.println("Database transfer failed (" + error.getClass().getSimpleName()
                    + "); transaction rolled back. Inspect privately; no source data was changed.");
            System.exit(1);
        }
    }

    private static String required(java.util.Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    public static void run(boolean copy) throws Exception {
        run(copy, System.getenv());
    }

    public static void run(boolean copy, java.util.Map<String, String> environment) throws Exception {
        String sourceUrl = required(environment, "RAYCHI_MIGRATION_SOURCE_URL"), targetUrl = required(environment, "RAYCHI_MIGRATION_TARGET_URL");
        if (!sourceUrl.startsWith("jdbc:mysql:") || !targetUrl.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("Only MySQL to PostgreSQL transfer is supported");
        String targetName = targetUrl.substring(targetUrl.lastIndexOf('/') + 1).split("\\?", 2)[0];
        if (!targetName.matches("[a-zA-Z0-9_]+") || !targetName.equals(required(environment, "RAYCHI_MIGRATION_TARGET_CONFIRM")))
            throw new IllegalArgumentException("Explicit target database name confirmation required");
        try (var source = DriverManager.getConnection(sourceUrl, required(environment, "RAYCHI_MIGRATION_SOURCE_USER"),
                        required(environment, "RAYCHI_MIGRATION_SOURCE_PASSWORD"));
             var target = DriverManager.getConnection(targetUrl, required(environment, "RAYCHI_MIGRATION_TARGET_USER"),
                        required(environment, "RAYCHI_MIGRATION_TARGET_PASSWORD"))) {
            source.createStatement().execute("SET time_zone='+00:00'");
            source.setReadOnly(true);
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            source.setAutoCommit(false);
            target.createStatement().execute("SET TIME ZONE 'UTC'");
            assertTables(source, true);
            try (var row = source.createStatement().executeQuery(
                    "SELECT COUNT(*),MAX(CAST(version AS UNSIGNED)),SUM(success=0) FROM flyway_schema_history")) {
                if (!row.next() || row.getInt(1) != 14 || row.getInt(2) != 14 || row.getInt(3) != 0)
                    throw new IllegalStateException("Source must have all 14 successful MySQL migrations");
            }
            if (copy) {
                try (var rows = target.createStatement().executeQuery(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_type='BASE TABLE'")) {
                    rows.next();
                    if (rows.getLong(1) != 0) throw new IllegalStateException("Copy requires a new empty target schema");
                }
                Flyway.configure().dataSource(targetUrl, required(environment, "RAYCHI_MIGRATION_TARGET_USER"),
                        required(environment, "RAYCHI_MIGRATION_TARGET_PASSWORD")).locations("classpath:db/postgresql").load().migrate();
            }
            assertTables(target, false);
            target.setAutoCommit(false);
            target.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                if (copy) target.createStatement().execute("TRUNCATE TABLE "
                        + String.join(",", TABLES.stream().map(t -> quote(t, false)).toList()));
                for (String table : TABLES) {
                    List<Column> columns = columns(source, table, true);
                    if (!columns.stream().map(Column::name).toList().equals(
                            columns(target, table, false).stream().map(Column::name).toList()))
                        throw new IllegalStateException("Column mismatch in " + table);
                    if (copy) copy(source, target, table, columns);
                    Fingerprint original = fingerprint(source, table, columns, true);
                    Fingerprint migrated = fingerprint(target, table, columns, false);
                    if (!original.equals(migrated)) throw new IllegalStateException("Data mismatch in " + table);
                    System.out.println("VERIFIED " + table + " rows=" + original.rows() + " sha256=" + original.sha256());
                }
                if (copy) target.commit(); else target.rollback();
                source.rollback();
                System.out.println(copy ? "PASS copied and verified all 16 tables" : "PASS verified all 16 tables");
            } catch (Exception error) {
                target.rollback();
                throw error;
            }
        }
    }

    private static void assertTables(Connection db, boolean mysql) throws Exception {
        var names = new ArrayList<String>();
        try (var rows = db.createStatement().executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema="
                + (mysql ? "DATABASE()" : "current_schema()") + " AND table_type='BASE TABLE'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        var expected = new ArrayList<>(TABLES); expected.add("flyway_schema_history");
        if (!names.stream().sorted().toList().equals(expected.stream().sorted().toList()))
            throw new IllegalStateException("Unexpected tables; do not silently omit data");
    }

    private static List<Column> columns(Connection db, String table, boolean mysql) throws Exception {
        var result = new ArrayList<Column>();
        try (var rows = db.createStatement().executeQuery("SELECT * FROM " + quote(table, mysql) + " WHERE 1=0")) {
            var metadata = rows.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                int type = metadata.getColumnType(i);
                if (type == Types.TIMESTAMP_WITH_TIMEZONE) type = Types.TIMESTAMP;
                result.add(new Column(metadata.getColumnName(i), type));
            }
        }
        return result.stream().sorted(java.util.Comparator.comparing(Column::name)).toList();
    }

    private static String quote(String identifier, boolean mysql) {
        if (!identifier.matches("[a-z_]+")) throw new IllegalArgumentException("Unexpected identifier");
        return mysql ? "`" + identifier + "`" : "\"" + identifier + "\"";
    }

    private static String select(String table, List<Column> columns, boolean mysql) {
        return "SELECT " + String.join(",", columns.stream().map(c -> quote(c.name(), mysql)).toList())
                + " FROM " + quote(table, mysql);
    }

    private static Object value(ResultSet rows, int index, Column column) throws Exception {
        Object result = switch (column.type()) {
            case Types.BIT, Types.BOOLEAN, Types.TINYINT -> rows.getBoolean(index);
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT -> rows.getLong(index);
            case Types.TIMESTAMP -> rows.getTimestamp(index, UTC);
            default -> rows.getString(index);
        };
        return rows.wasNull() ? null : result;
    }

    private static void copy(Connection source, Connection target, String table, List<Column> columns) throws Exception {
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        String insert = "INSERT INTO " + quote(table, false) + " ("
                + String.join(",", columns.stream().map(c -> quote(c.name(), false)).toList()) + ") VALUES (" + placeholders + ")";
        try (var query = source.createStatement(); var rows = query.executeQuery(select(table, columns, true));
             var statement = target.prepareStatement(insert)) {
            int batch = 0;
            while (rows.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    Object data = value(rows, i + 1, columns.get(i));
                    if (data instanceof java.sql.Timestamp timestamp) statement.setTimestamp(i + 1, timestamp, UTC);
                    else statement.setObject(i + 1, data);
                }
                statement.addBatch();
                if (++batch % 100 == 0) statement.executeBatch();
            }
            if (batch % 100 != 0) statement.executeBatch();
        }
    }

    private static Fingerprint fingerprint(Connection db, String table, List<Column> columns, boolean mysql) throws Exception {
        var hashes = new ArrayList<byte[]>();
        try (var query = db.createStatement(); var rows = query.executeQuery(select(table, columns, mysql))) {
            while (rows.next()) {
                // Bound metadata memory; this site transfer is deliberately not a general bulk migration engine.
                if (hashes.size() >= 1_000_000) throw new IllegalStateException("Table exceeds transfer verification budget");
                var bytes = new ByteArrayOutputStream();
                try (var output = new DataOutputStream(bytes)) {
                    for (int i = 0; i < columns.size(); i++) {
                        Object data = value(rows, i + 1, columns.get(i));
                        if (data == null) output.writeInt(-1);
                        else {
                            String text = data instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().toString() : data.toString();
                            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
                            output.writeInt(encoded.length); output.write(encoded);
                        }
                    }
                }
                hashes.add(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            }
        }
        hashes.sort(Arrays::compareUnsigned);
        var digest = MessageDigest.getInstance("SHA-256");
        for (byte[] hash : hashes) digest.update(hash);
        return new Fingerprint(hashes.size(), HexFormat.of().formatHex(digest.digest()));
    }
}
