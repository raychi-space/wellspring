package space.raychi.wellspring.ai;

public interface TranslationService {
    record Task(String id, String status, String translatedTitle, String errorCode) {}

    String startTitleTranslation(String assistantId, String articleId, String originalTitle, String titleHash);
    Task task(String taskId);
}
