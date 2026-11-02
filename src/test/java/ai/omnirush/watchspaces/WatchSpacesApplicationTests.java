package ai.omnirush.watchspaces;

import ai.omnirush.watchspaces.catalog.timeline.InMemoryTimelineCatalog;
import ai.omnirush.watchspaces.catalog.timeline.TimelineCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "security.jwt.secret=test-secret-that-is-long-enough-for-hmac")
@ActiveProfiles("test")
class WatchSpacesApplicationTests {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TimelineCatalog timelineCatalog;

    @Test
    void testProfileSelectsExactlyOneInMemoryTimelineCatalog() {
        assertThat(applicationContext.getBeansOfType(TimelineCatalog.class)).hasSize(1);
        assertThat(timelineCatalog).isInstanceOf(InMemoryTimelineCatalog.class);
    }
}
