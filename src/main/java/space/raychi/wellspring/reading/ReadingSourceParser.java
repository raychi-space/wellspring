package space.raychi.wellspring.reading;

import static space.raychi.wellspring.reading.ReadingModels.ParsedArticle;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class ReadingSourceParser {
    private static final Pattern ENGLISH_DATE = Pattern.compile(
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{1,2})(?:,\\s*(\\d{4}))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH);

    public List<ParsedArticle> parse(String adapter, String listingUrl, String html, Instant fetchedAt) {
        Document doc = Jsoup.parse(html, listingUrl);
        URI listing = safeUri(listingUrl);
        if (listing == null) return List.of();
        DateCursor dates = new DateCursor(fetchedAt);
        String pathPrefix = adapter.equals("ANTHROPIC_ENGINEERING") ? "/engineering/" : "/blog/";
        LinkedHashMap<String, ParsedArticle> found = new LinkedHashMap<>();
        for (Element link : doc.select("a[href]")) {
            URI uri = safeUri(link.absUrl("href"));
            if (uri == null || !"https".equals(uri.getScheme()) || !listing.getHost().equalsIgnoreCase(uri.getHost())
                    || !uri.getPath().startsWith(pathPrefix) || uri.getPath().equals(pathPrefix)) continue;
            if (uri.getPath().startsWith(pathPrefix + "topic/") || uri.getPath().contains("/tag/")) continue;
            Element titleNode = link.selectFirst("h1,h2,h3,h4");
            if (titleNode == null && adapter.equals("OPENAI_DEVELOPERS"))
                titleNode = link.selectFirst("img[alt], [class*=font-medium]");
            String title = clean(titleNode == null ? link.text()
                    : titleNode.tagName().equals("img") ? titleNode.attr("alt") : titleNode.text());
            if (title.length() < 4 || title.length() > 500) continue;
            Instant published = publishedAt(link, fetchedAt, dates);
            if (published == null || published.isAfter(fetchedAt.plus(Duration.ofDays(2)))) continue;
            String canonical = uri.getScheme() + "://" + uri.getHost() + uri.getPath();
            found.putIfAbsent(canonical, new ParsedArticle(canonical, title, published));
        }
        return found.values().stream()
                .sorted(Comparator.comparing(ParsedArticle::publishedAt).reversed().thenComparing(ParsedArticle::url))
                .limit(200).toList();
    }

    private static Instant publishedAt(Element link, Instant fetchedAt, DateCursor dates) {
        Element scope = link;
        for (int depth = 0; scope != null && depth < 6; depth++, scope = scope.parent()) {
            Element time = scope.selectFirst("time[datetime]");
            if (time != null) {
                Instant parsed = isoDate(time.attr("datetime"));
                if (parsed != null) {
                    dates.observe(LocalDate.ofInstant(parsed, ZoneOffset.UTC));
                    return parsed;
                }
            }
            Matcher matcher = ENGLISH_DATE.matcher(scope.ownText() + " " + scope.text());
            if (matcher.find()) {
                try {
                    LocalDate date = LocalDate.parse(matcher.group(1).substring(0, 3) + " " + matcher.group(2) + " "
                            + (matcher.group(3) == null ? dates.year : Integer.parseInt(matcher.group(3))), SHORT_DATE);
                    date = matcher.group(3) == null ? dates.resolve(date, fetchedAt) : dates.observe(date);
                    return date.atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException ignored) { }
            }
        }
        return null;
    }

    private static Instant isoDate(String value) {
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ISO_DATE)
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignoredAgain) { return null; }
        }
    }

    private static URI safeUri(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getScheme() != null && uri.getHost() != null ? uri : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    private static final class DateCursor {
        private int year;
        private LocalDate previous;

        DateCursor(Instant fetchedAt) { year = LocalDate.ofInstant(fetchedAt, ZoneOffset.UTC).getYear(); }

        LocalDate resolve(LocalDate candidate, Instant fetchedAt) {
            LocalDate fetchedDate = LocalDate.ofInstant(fetchedAt, ZoneOffset.UTC);
            if ((previous == null && candidate.isAfter(fetchedDate.plusDays(7)))
                    || (previous != null && candidate.isAfter(previous.plusDays(7))))
                candidate = candidate.minusYears(1);
            return observe(candidate);
        }

        LocalDate observe(LocalDate date) {
            year = date.getYear();
            previous = date;
            return date;
        }
    }

    private static String clean(String value) { return value.replaceAll("\\s+", " ").trim(); }
}
