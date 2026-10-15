package space.raychi.wellspring.reading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReadingSourceParserTest {
    private final ReadingSourceParser parser = new ReadingSourceParser();

    @Test
    void parsesOnlyListingMetadataAndKeepsStableOrder() {
        String html = """
                <article><a href="/engineering/new-agent"><img src="https://img.invalid/x.png">
                <h3>Building a reliable agent</h3><time datetime="2026-10-12">Oct 12, 2026</time></a></article>
                <article><a href="/engineering/older"><h3>Tool design in practice</h3>
                <time datetime="2026-09-30">Sep 30, 2026</time></a></article>
                <a href="/research/not-engineering"><h3>Ignored research</h3><time datetime="2026-10-14"></time></a>
                <a href="https://malicious.example/engineering/lookalike"><h3>Ignored external link</h3>
                <time datetime="2026-10-13"></time></a>
                """;
        var items = parser.parse("ANTHROPIC_ENGINEERING", "https://www.anthropic.com/engineering", html,
                Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(items).extracting(ReadingModels.ParsedArticle::title)
                .containsExactly("Building a reliable agent", "Tool design in practice");
        assertThat(items.getFirst().url()).isEqualTo("https://www.anthropic.com/engineering/new-agent");
    }

    @Test
    void resolvesOpenAiCardsWithoutReadingArticleBodies() {
        String html = """
                <main><article><a href="/blog/agent-tools">
                <img src="/images/agent-tools.webp" alt="Agent tools that work">
                <div>Oct 14</div><div class="font-medium">Agent tools that work</div>
                <p>This summary is deliberately ignored.</p></a></article>
                <article><a href="/blog/january"><img alt="January article"><div>Jan 11</div></a></article>
                <article><a href="/blog/previous-year"><img alt="Previous year article"><div>Dec 30</div></a></article>
                </main>
                """;
        var items = parser.parse("OPENAI_DEVELOPERS", "https://developers.openai.com/blog", html,
                Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(items).hasSize(3);
        assertThat(items.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(items.getFirst().title()).isEqualTo("Agent tools that work");
        assertThat(items.getLast().publishedAt()).isEqualTo(Instant.parse("2025-12-30T00:00:00Z"));
    }
}
