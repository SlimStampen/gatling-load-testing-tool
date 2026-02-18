package ssaas;

import io.gatling.javaapi.core.Session;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SSaaSSimulationBase {

    // CONFIGURATION: Choose fact ID strategy
    // - true: All users practice with the SAME fact IDs (tests caching, predictable)
    // - false: Each user gets UNIQUE fact IDs (tests UUID→numeric mapping service)
    public static final boolean USE_SAME_FACT_IDS_FOR_ALL_USERS = true;

    // UUID validation regex
    public static final String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    // Fixed set of fact content (cue texts and answers)
    // IDs will be either shared or generated per user based on configuration
    protected static final List<FactContent> FACT_CONTENTS = Arrays.asList(
        new FactContent("the book", "le livre"),
        new FactContent("the cat", "le chat"),
        new FactContent("the house", "la maison"),
        new FactContent("the car", "la voiture"),
        new FactContent("the water", "l'eau"),
        new FactContent("the tree", "l'arbre"),
        new FactContent("the dog", "le chien"),
        new FactContent("the sun", "le soleil")
    );

    // Shared fact IDs for V2 (UUID) - used when USE_SAME_FACT_IDS_FOR_ALL_USERS = true
    protected static final List<String> SHARED_FACT_IDS_V2 = Arrays.asList(
        "bfe507f4-cbdd-454b-a507-f4cbddb54b91",
        "7f3e9a2c-1b8d-4e5f-a3c6-9d8e7f6a5b4c",
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
        "c4b5a6d7-e8f9-4a1b-2c3d-4e5f6a7b8c9d",
        "d1e2f3a4-b5c6-4d7e-8f9a-0b1c2d3e4f5a",
        "e6f7a8b9-c0d1-4e2f-3a4b-5c6d7e8f9a0b",
        "f9a0b1c2-d3e4-4f5a-6b7c-8d9e0f1a2b3c",
        "a2b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d"
    );

    // Shared fact IDs for V1 (numeric) - used when USE_SAME_FACT_IDS_FOR_ALL_USERS = true
    protected static final List<String> SHARED_FACT_IDS_V1 = Arrays.asList(
        "1", "2", "3", "4", "5", "6", "7", "8"
    );

    // Counter for generating sequential numeric IDs for V1
    private static int numericIdCounter = 1000;

    public static class FactContent {
        public final String cueText;
        public final String answer;

        public FactContent(String cueText, String answer) {
            this.cueText = cueText;
            this.answer = answer;
        }
    }

    public static class Fact {
        public final String id;
        public final String cueText;
        public final String answer;

        public Fact(String id, String cueText, String answer) {
            this.id = id;
            this.cueText = cueText;
            this.answer = answer;
        }

        public String toJson() {
            return String.format("{\"id\":\"%s\",\"cueTexts\":[\"%s\"],\"answers\":[\"%s\"],\"imageFileIds\":[]}",
                id, cueText, answer);
        }
    }

    public static class Timing {
        public final long startTime;
        public final long reactionTime;
        public final long presentationDuration;
        public final long currentTime;
        public final long sessionTime;

        public Timing(long startTime, long reactionTime, long presentationDuration, long currentTime, long sessionTime) {
            this.startTime = startTime;
            this.reactionTime = reactionTime;
            this.presentationDuration = presentationDuration;
            this.currentTime = currentTime;
            this.sessionTime = sessionTime;
        }
    }

    public static class AnswerSelection {
        public final String givenResponse;
        public final boolean isCorrect;
        public final String alternativesJson;

        public AnswerSelection(String givenResponse, boolean isCorrect, String alternativesJson) {
            this.givenResponse = givenResponse;
            this.isCorrect = isCorrect;
            this.alternativesJson = alternativesJson;
        }
    }

    /**
     * Helper method to build facts list for V2 (UUID-based) based on configuration
     */
    public static List<Fact> buildFactsListV2(boolean useSharedIds) {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < FACT_CONTENTS.size(); i++) {
            FactContent content = FACT_CONTENTS.get(i);
            String factId = useSharedIds ? SHARED_FACT_IDS_V2.get(i) : UUID.randomUUID().toString();
            facts.add(new Fact(factId, content.cueText, content.answer));
        }
        return facts;
    }

    /**
     * Helper method to build facts list for V1 (numeric ID-based) based on configuration
     */
    public static List<Fact> buildFactsListV1(boolean useSharedIds) {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < FACT_CONTENTS.size(); i++) {
            FactContent content = FACT_CONTENTS.get(i);
            String factId;
            if (useSharedIds) {
                factId = SHARED_FACT_IDS_V1.get(i);
            } else {
                // Generate sequential numeric IDs starting from 1000
                synchronized (SSaaSSimulationBase.class) {
                    factId = String.valueOf(numericIdCounter++);
                }
            }
            facts.add(new Fact(factId, content.cueText, content.answer));
        }
        return facts;
    }

    /**
     * Calculate timing for response submission
     */
    public static Timing calculateTiming(Session session) {
        long startTime = session.contains("startTime") ? session.getLong("startTime") : System.currentTimeMillis();
        long reactionTime = ThreadLocalRandom.current().nextLong(800, 3000);
        long presentationDuration = reactionTime + ThreadLocalRandom.current().nextLong(500, 2000);
        long currentTime = System.currentTimeMillis();
        long sessionTime = currentTime - startTime;
        return new Timing(startTime, reactionTime, presentationDuration, currentTime, sessionTime);
    }

    /**
     * Select answer (correct 90% of the time) and generate alternatives
     */
    public static AnswerSelection selectAnswer(Session session) {
        // Get the correct answer for this fact
        String correctAnswer = session.getString("correctAnswer");

        // Get the facts list from session (contains either shared or unique IDs per user)
        @SuppressWarnings("unchecked")
        List<Fact> userFacts = (List<Fact>) session.get("userFacts");

        // Generate alternatives by selecting 3 random wrong answers from other facts
        List<String> wrongAnswers = new ArrayList<>();
        for (Fact fact : userFacts) {
            if (!fact.answer.equals(correctAnswer)) {
                wrongAnswers.add(fact.answer);
            }
        }

        // Shuffle and take first 3 as distractors
        Collections.shuffle(wrongAnswers);
        List<String> alternatives = new ArrayList<>();
        alternatives.add(correctAnswer);
        for (int i = 0; i < Math.min(3, wrongAnswers.size()); i++) {
            alternatives.add(wrongAnswers.get(i));
        }

        // Shuffle alternatives so correct answer isn't always first
        Collections.shuffle(alternatives);

        // Build alternatives JSON array
        StringBuilder alternativesJson = new StringBuilder("[");
        for (int i = 0; i < alternatives.size(); i++) {
            if (i > 0) alternativesJson.append(",");
            boolean isCorrect = alternatives.get(i).equals(correctAnswer);
            alternativesJson.append(String.format("{\"id\":%d,\"text\":\"%s\",\"correct\":%b}",
                i + 1, alternatives.get(i), isCorrect));
        }
        alternativesJson.append("]");

        // Select correct answer 90% of the time
        String selectedAnswer;
        boolean selectedIsCorrect;
        if (ThreadLocalRandom.current().nextDouble() < 0.9) {
            selectedAnswer = correctAnswer;
            selectedIsCorrect = true;
        } else {
            // Pick a random wrong answer
            List<String> wrongs = new ArrayList<>();
            for (String alt : alternatives) {
                if (!alt.equals(correctAnswer)) {
                    wrongs.add(alt);
                }
            }
            selectedAnswer = wrongs.isEmpty() ? correctAnswer : wrongs.get(ThreadLocalRandom.current().nextInt(wrongs.size()));
            selectedIsCorrect = false;
        }

        return new AnswerSelection(selectedAnswer, selectedIsCorrect, alternativesJson.toString());
    }

    /**
     * Extract studyTrial from session (handles both Boolean and String types)
     */
    public static boolean extractStudyTrial(Session session) {
        if (session.contains("studyTrial")) {
            Object studyTrialObj = session.get("studyTrial");
            if (studyTrialObj instanceof Boolean studyTrial) {
                return studyTrial;
            } else if (studyTrialObj instanceof String studyTrial) {
                return Boolean.parseBoolean(studyTrial);
            }
        }
        return false;
    }

    /**
     * Prepare session with calculated timing and selected answer for response submission
     */
    public static Session prepareResponseSession(Session session) {
        Timing timing = calculateTiming(session);
        AnswerSelection answerSel = selectAnswer(session);
        boolean studyTrial = extractStudyTrial(session);

        return session
            .set("startTime", timing.startTime)
            .set("reactionTime", timing.reactionTime)
            .set("presentationDuration", timing.presentationDuration)
            .set("sessionTime", timing.sessionTime)
            .set("presentationStartTime", timing.currentTime)
            .set("givenResponse", answerSel.givenResponse)
            .set("isCorrect", answerSel.isCorrect)
            .set("studyTrial", studyTrial)
            .set("alternatives", answerSel.alternativesJson);
    }

    /**
     * Validate that a factId is a valid UUID format (for V2)
     */
    public static void validateUuidFormat(String factId) {
        if (!factId.matches(UUID_REGEX)) {
            throw new IllegalStateException("factId is not a valid UUID: " + factId);
        }
    }

    /**
     * Validate that a factId is a valid numeric format (for V1)
     */
    public static void validateNumericFormat(String factId) {
        try {
            Long.parseLong(factId);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("factId is not a valid numeric ID: " + factId, e);
        }
    }

    // Counter for generating sequential numeric user IDs for V1
    private static long numericUserIdCounter = 10000;

    /**
     * Generate a numeric user ID for V1
     */
    public static synchronized String generateNumericUserId() {
        return String.valueOf(numericUserIdCounter++);
    }

    /**
     * Generate a UUID user ID for V2
     */
    public static String generateUuidUserId() {
        return UUID.randomUUID().toString();
    }
}
