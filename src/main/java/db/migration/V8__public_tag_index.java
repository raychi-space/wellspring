package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V8__public_tag_index extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public_content_tags (article_id VARCHAR(36) NOT NULL, name VARCHAR(40) NOT NULL, PRIMARY KEY(article_id,name), FOREIGN KEY(article_id) REFERENCES articles(id) ON DELETE CASCADE)");
            if ("MySQL".equals(connection.getMetaData().getDatabaseProductName()))
                statement.execute("ALTER TABLE public_content_tags MODIFY name VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL");
            statement.execute("CREATE INDEX idx_public_content_tag ON public_content_tags(name,article_id)");
        }
        var json = new ObjectMapper();
        try (var query = connection.createStatement();
             var rows = query.executeQuery("SELECT id,public_tags FROM articles WHERE public_tags IS NOT NULL");
             var insert = connection.prepareStatement("INSERT INTO public_content_tags(article_id,name) VALUES (?,?)")) {
            while (rows.next()) {
                List<String> tags = json.readValue(rows.getString(2), new TypeReference<List<String>>() {});
                for (String tag : new LinkedHashSet<>(tags)) {
                    insert.setString(1, rows.getString(1)); insert.setString(2, tag); insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }
}
