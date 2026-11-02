package ai.omnirush.watchspaces.service.ai;

import ai.omnirush.watchspaces.catalog.timeline.TimelineEvent;

import java.util.List;

/**
 * Future model integrations can implement this boundary. The shipped implementation is
 * deterministic and uses only the supplied timeline context; it does not call an LLM.
 */
public interface GroundedAnswerAdapter {

    GroundedAnswer generate(String question, List<TimelineEvent> retrievedEvents);
}
