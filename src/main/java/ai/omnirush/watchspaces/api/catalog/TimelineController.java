package ai.omnirush.watchspaces.api.catalog;

import ai.omnirush.watchspaces.catalog.timeline.TimelineCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/titles")
public class TimelineController {
    private final TimelineCatalog timelineCatalog;

    public TimelineController(TimelineCatalog timelineCatalog) {
        this.timelineCatalog = timelineCatalog;
    }

    @GetMapping("/{titleId}/timeline")
    public TimelineResponse timeline(
            @PathVariable("titleId") String titleId,
            @RequestParam(name = "from", required = false) Double fromSeconds,
            @RequestParam(name = "to", required = false) Double toSeconds) {
        return TimelineResponse.from(
                titleId,
                timelineCatalog.eventsFor(titleId, fromSeconds, toSeconds));
    }
}
