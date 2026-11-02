package ai.omnirush.watchspaces.service.ai;

import ai.omnirush.watchspaces.catalog.timeline.InMemoryTimelineCatalog;
import ai.omnirush.watchspaces.catalog.timeline.TimelineCatalog;
import ai.omnirush.watchspaces.catalog.timeline.TimelineEvent;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public final class GroundedQuestionService {
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{Nd}]{2,}");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "about", "an", "and", "are", "at", "can", "did", "do", "does", "earlier",
            "happen", "happened", "how", "i", "in", "is", "it", "me", "of", "on", "please",
            "tell", "that", "the", "this", "to", "was", "what", "when", "where", "who", "why", "you");
    private static final double CONTEXT_BEFORE_SECONDS = 120.0;
    private static final int MAX_RETRIEVED_EVENTS = 3;
    private static final int MAX_QUESTION_CHARACTERS = 500;

    private final TimelineCatalog timelineCatalog;
    private final GroundedAnswerAdapter answerAdapter;

    public GroundedQuestionService(
            TimelineCatalog timelineCatalog,
            GroundedAnswerAdapter answerAdapter) {
        this.timelineCatalog = timelineCatalog;
        this.answerAdapter = answerAdapter;
    }

    public GroundedAnswer answer(String titleId, String question, Double currentPositionSeconds) {
        validateQuestion(question);
        validatePosition(currentPositionSeconds);

        Double fromSeconds = currentPositionSeconds == null
                ? null
                : Math.max(0.0, currentPositionSeconds - CONTEXT_BEFORE_SECONDS);
        Double toSeconds = currentPositionSeconds == null
                ? null
                : currentPositionSeconds;
        List<TimelineEvent> scopedEvents = timelineCatalog.eventsFor(titleId, fromSeconds, toSeconds);
        Set<String> questionTerms = terms(question);

        List<TimelineEvent> matches = scopedEvents.stream()
                .map(event -> new ScoredEvent(event, overlap(questionTerms, terms(event.text()))))
                .filter(scoredEvent -> scoredEvent.overlap() > 0)
                .sorted(Comparator.comparingInt(ScoredEvent::overlap).reversed()
                        .thenComparingDouble(scoredEvent -> distanceFromPosition(
                                scoredEvent.event(), currentPositionSeconds))
                        .thenComparingDouble(scoredEvent -> scoredEvent.event().timestampSeconds())
                        .thenComparing(scoredEvent -> scoredEvent.event().eventId()))
                .limit(MAX_RETRIEVED_EVENTS)
                .map(ScoredEvent::event)
                .toList();

        if (matches.isEmpty()) {
            return GroundedAnswer.insufficientContext();
        }
        return answerAdapter.generate(question.trim(), matches);
    }

    private static int overlap(Set<String> questionTerms, Set<String> eventTerms) {
        int count = 0;
        for (String questionTerm : questionTerms) {
            if (eventTerms.contains(questionTerm)) {
                count++;
            }
        }
        return count;
    }

    private static double distanceFromPosition(TimelineEvent event, Double currentPositionSeconds) {
        return currentPositionSeconds == null
                ? event.timestampSeconds()
                : Math.abs(event.timestampSeconds() - currentPositionSeconds);
    }

    private static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = stem(matcher.group());
            if (!STOP_WORDS.contains(term) && term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static String stem(String term) {
        if (term.length() > 5 && term.endsWith("ing")) {
            return term.substring(0, term.length() - 3);
        }
        if (term.length() > 4 && term.endsWith("ed")) {
            return term.substring(0, term.length() - 2);
        }
        if (term.length() > 4 && term.endsWith("s")) {
            return term.substring(0, term.length() - 1);
        }
        return term;
    }

    private static void validateQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (question.length() > MAX_QUESTION_CHARACTERS) {
            throw new IllegalArgumentException("question must be at most 500 characters");
        }
    }

    private static void validatePosition(Double currentPositionSeconds) {
        if (currentPositionSeconds != null
                && (!Double.isFinite(currentPositionSeconds)
                || currentPositionSeconds < 0
                || currentPositionSeconds > InMemoryTimelineCatalog.MAX_TIMESTAMP_SECONDS)) {
            throw new IllegalArgumentException("currentPositionSeconds must be between 0 and 86400 seconds");
        }
    }

    private record ScoredEvent(TimelineEvent event, int overlap) {
    }
}
