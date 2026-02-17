package ssaas;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;


public class SSaaSV2Simulation extends Simulation {

    // CONFIGURATION: Choose fact ID strategy
    // - true: All users practice with the SAME fact IDs (tests caching, predictable)
    // - false: Each user gets UNIQUE fact IDs (tests UUID→numeric mapping service)
    private static final boolean USE_SAME_FACT_IDS_FOR_ALL_USERS = false;

    // Fixed set of fact content (cue texts and answers)
    // IDs will be either shared or generated per user based on configuration
    private static final List<FactContent> FACT_CONTENTS = Arrays.asList(
        new FactContent("the book", "le livre"),
        new FactContent("the cat", "le chat"),
        new FactContent("the house", "la maison"),
        new FactContent("the car", "la voiture"),
        new FactContent("the water", "l'eau"),
        new FactContent("the tree", "l'arbre"),
        new FactContent("the dog", "le chien"),
        new FactContent("the sun", "le soleil")
    );

    // Shared fact IDs - used when USE_SAME_FACT_IDS_FOR_ALL_USERS = true
    private static final List<String> SHARED_FACT_IDS = Arrays.asList(
        "bfe507f4-cbdd-454b-a507-f4cbddb54b91",
        "7f3e9a2c-1b8d-4e5f-a3c6-9d8e7f6a5b4c",
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
        "c4b5a6d7-e8f9-4a1b-2c3d-4e5f6a7b8c9d",
        "d1e2f3a4-b5c6-4d7e-8f9a-0b1c2d3e4f5a",
        "e6f7a8b9-c0d1-4e2f-3a4b-5c6d7e8f9a0b",
        "f9a0b1c2-d3e4-4f5a-6b7c-8d9e0f1a2b3c",
        "a2b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d"
    );

    private static class FactContent {
        String cueText;
        String answer;

        FactContent(String cueText, String answer) {
            this.cueText = cueText;
            this.answer = answer;
        }
    }

    private static class Fact {
        String id;
        String cueText;
        String answer;

        Fact(String id, String cueText, String answer) {
            this.id = id;
            this.cueText = cueText;
            this.answer = answer;
        }

        String toJson() {
            return String.format("{\"id\":\"%s\",\"cueTexts\":[\"%s\"],\"answers\":[\"%s\"],\"imageFileIds\":[]}",
                id, cueText, answer);
        }
    }

    // Helper method to build facts list based on configuration
    private static List<Fact> buildFactsList(boolean useSharedIds) {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < FACT_CONTENTS.size(); i++) {
            FactContent content = FACT_CONTENTS.get(i);
            String factId = useSharedIds ? SHARED_FACT_IDS.get(i) : UUID.randomUUID().toString();
            facts.add(new Fact(factId, content.cueText, content.answer));
        }
        return facts;
    }

    private static class Timing {
        long startTime;
        long reactionTime;
        long presentationDuration;
        long currentTime;
        long sessionTime;

        Timing(long startTime, long reactionTime, long presentationDuration, long currentTime, long sessionTime) {
            this.startTime = startTime;
            this.reactionTime = reactionTime;
            this.presentationDuration = presentationDuration;
            this.currentTime = currentTime;
            this.sessionTime = sessionTime;
        }
    }

    private static Timing calculateTiming(Session session) {
        long startTime = session.contains("startTime") ? session.getLong("startTime") : System.currentTimeMillis();
        long reactionTime = ThreadLocalRandom.current().nextLong(800, 3000);
        long presentationDuration = reactionTime + ThreadLocalRandom.current().nextLong(500, 2000);
        long currentTime = System.currentTimeMillis();
        long sessionTime = currentTime - startTime;
        return new Timing(startTime, reactionTime, presentationDuration, currentTime, sessionTime);
    }

    private static class AnswerSelection {
        String givenResponse;
        boolean isCorrect;
        String alternativesJson;

        AnswerSelection(String givenResponse, boolean isCorrect, String alternativesJson) {
            this.givenResponse = givenResponse;
            this.isCorrect = isCorrect;
            this.alternativesJson = alternativesJson;
        }
    }

    private static AnswerSelection selectAnswer(Session session) {
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

    private static boolean extractStudyTrial(Session session) {
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

    private static Session prepareResponseSession(Session session) {
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

    ChainBuilder practiceSessionFlow = exec(session -> {
                // Generate userId once per virtual user
                String userId = UUID.randomUUID().toString();

                // Build facts list based on configuration
                // - If USE_SAME_FACT_IDS_FOR_ALL_USERS = true: use shared IDs (cache testing)
                // - If USE_SAME_FACT_IDS_FOR_ALL_USERS = false: generate unique IDs (UUID mapping testing)
                List<Fact> userFacts = buildFactsList(USE_SAME_FACT_IDS_FOR_ALL_USERS);

                // Build facts JSON array
                StringBuilder factsJson = new StringBuilder("[");
                for (int i = 0; i < userFacts.size(); i++) {
                    if (i > 0) factsJson.append(",");
                    factsJson.append(userFacts.get(i).toJson());
                }
                factsJson.append("]");

                // Generate dynamic context with timestamp
                long timestamp = System.currentTimeMillis();
                String sessionContext = String.format("Gatling load test session [%d]", timestamp);

                // Build initialize request body
                String requestBody = String.format(
                    "{\"context\":\"%s\",\"randomOrder\":true,\"userId\":\"%s\",\"facts\":%s,\"answerMethod\":\"MULTIPLE_CHOICE_4\",\"timezone\":\"Europe/Amsterdam\"}",
                    sessionContext, userId, factsJson.toString()
                );

                return session
                    .set("userId", userId)
                    .set("userFacts", userFacts)  // Store facts in session for answer generation
                    .set("requestBody", requestBody)
                    .set("startTime", timestamp);
            })
            .exec(http("Initialize Session")
                    .post("/v2/session/initialize")
                    .body(StringBody("#{requestBody}"))
                    .check(status().is(200))
                    .check(jsonPath("$.sessionId").saveAs("sessionId"))
                    .check(jsonPath("$.cue.fact.id").saveAs("factId"))
                    .check(jsonPath("$.cue.fact.answers[0]").saveAs("correctAnswer"))
                    .check(jsonPath("$.cue.fact.presentedCueTextIndex").saveAs("presentedCueTextIndex"))
                    .check(jsonPath("$.cue.fact.presentedImageIndex").saveAs("presentedImageIndex"))
                    .check(jsonPath("$.sessionProgress.achievedCredit").saveAs("achievedCredit"))
            )
            .pause(1, 2)
            .repeat(10).on(
                    exec(SSaaSV2Simulation::prepareResponseSession)
                            .exec(http("Submit Response")
                                    .post("/v2/response/save")
                                    .body(StringBody(session -> {
                                        String presentedImageIndex = session.getString("presentedImageIndex");
                                        String imageIndexValue = (presentedImageIndex == null || presentedImageIndex.equals("null")) ? "null" : presentedImageIndex;

                                        return String.format(
                                            "{\"sessionId\":\"%s\",\"factId\":\"%s\",\"userId\":\"%s\",\"reactionTime\":%d,\"presentedCueTextIndex\":%s,\"presentedImageIndex\":%s,\"presentationStartTime\":%d,\"presentationDuration\":%d,\"sessionTime\":%d,\"data\":null,\"givenResponse\":\"%s\",\"correct\":%b,\"answerMethod\":\"MULTIPLE_CHOICE_4\",\"studyTrial\":%b,\"deviceInfo\":null,\"alternatives\":%s}",
                                            session.getString("sessionId"),
                                            session.getString("factId"),
                                            session.getString("userId"),
                                            session.getLong("reactionTime"),
                                            session.getString("presentedCueTextIndex"),
                                            imageIndexValue,
                                            session.getLong("presentationStartTime"),
                                            session.getLong("presentationDuration"),
                                            session.getLong("sessionTime"),
                                            session.getString("givenResponse"),
                                            session.getBoolean("isCorrect"),
                                            session.getBoolean("studyTrial"),
                                            session.getString("alternatives")
                                        );
                                    }))
                                    .check(status().is(200))
                                    .check(jsonPath("$.cue").optional().saveAs("cueExists"))
                                    .check(jsonPath("$.cue.fact.id").optional().saveAs("factId"))
                                    .check(jsonPath("$.cue.fact.answers[0]").optional().saveAs("correctAnswer"))
                                    .check(jsonPath("$.cue.fact.presentedCueTextIndex").optional().saveAs("presentedCueTextIndex"))
                                    .check(jsonPath("$.cue.fact.presentedImageIndex").optional().saveAs("presentedImageIndex"))
                                    .check(jsonPath("$.sessionProgress.achievedCredit").optional().saveAs("achievedCredit"))
                            )
                            .pause(1, 3)
                            .exitHereIf(session -> {
                                // Exit if achievedCredit is true or no more cues
                                Boolean achievedCredit = session.getBoolean("achievedCredit");
                                String cueExists = session.getString("cueExists");
                                return achievedCredit || cueExists == null || cueExists.equals("null");
                            })
            );

    HttpProtocolBuilder httpProtocol =
            http.baseUrl(Config.getBaseUrl())
                    .acceptHeader("application/json,text/plain,*/*")
                    .acceptLanguageHeader("nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7,de;q=0.6")
                    .acceptEncodingHeader("gzip, deflate, br")
                    .contentTypeHeader("application/json")
                    .userAgentHeader("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0")
                    .authorizationHeader("Bearer gatling-v2");

    ScenarioBuilder practiceSessionScenario = scenario("V2 Practice Session Scenario")
            .exec(practiceSessionFlow);

    {
        setUp(
//                practiceSessionScenario.injectOpen(rampUsers(50).during(15)),
                practiceSessionScenario.injectOpen(atOnceUsers(40))

        ).protocols(httpProtocol);
    }
}
