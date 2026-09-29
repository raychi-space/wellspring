package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V4__case_sensitive_tag_names extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        TagNameCollation.preserveCaseDistinctNames(context.getConnection());
    }
}
