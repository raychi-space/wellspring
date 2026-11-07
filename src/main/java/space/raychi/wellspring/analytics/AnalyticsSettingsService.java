package space.raychi.wellspring.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.AnalyticsSettingsDto.Settings;
import space.raychi.wellspring.mapper.AnalyticsSettingsMapper;

@Service
public class AnalyticsSettingsService {
    private final AnalyticsSettingsMapper settings;
    private final AnalyticsClient client;

    public AnalyticsSettingsService(AnalyticsSettingsMapper settings, AnalyticsClient client) {
        this.settings = settings; this.client = client;
    }

    @Transactional(readOnly = true)
    public Settings read() {
        var value = settings.select();
        return new Settings(value.enabled(), client.enabled, value.version());
    }

    public boolean collectionEnabled() { return client.enabled && settings.select().enabled(); }

    @Transactional
    public Settings save(JsonNode input) {
        if (input == null || !input.isObject() || !input.path("enabled").isBoolean()
                || !input.path("version").isIntegralNumber() || !input.path("version").canConvertToLong()
                || input.path("version").asLong() < 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "统计设置有无效字段。");
        var names = input.fieldNames();
        while (names.hasNext()) if (!Set.of("enabled", "version").contains(names.next()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "统计设置有无效字段。");
        boolean enabled = input.path("enabled").asBoolean();
        if (enabled && !client.enabled)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_NOT_CONFIGURED", "访问统计尚未配置，暂时无法开启。");
        if (settings.update(enabled, input.path("version").asLong(), Instant.now()) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "ANALYTICS_SETTINGS_VERSION_CONFLICT", "统计设置已被更新，请刷新后重试。");
        return read();
    }
}
