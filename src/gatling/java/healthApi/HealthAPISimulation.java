package healthApi;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.core.Session;

import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;


public class HealthAPISimulation extends Simulation {

    FeederBuilder<String> userFeeder = csv("healthapi_users.csv").circular();

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
        long startTime = session.contains("startTime") ? session.getLong("startTime") : System.currentTimeMillis() - ThreadLocalRandom.current().nextLong(500, 2001);
        long reactionTime = ThreadLocalRandom.current().nextLong(1000, 5000);
        long presentationDuration = reactionTime + ThreadLocalRandom.current().nextLong(1000, 5000);
        long currentTime = System.currentTimeMillis();
        long sessionTime = currentTime - startTime;
        return new Timing(startTime, reactionTime, presentationDuration, currentTime, sessionTime);
    }

    private static class AnswerSelection {
        String givenResponse;
        boolean isCorrect;
        AnswerSelection(String givenResponse, boolean isCorrect) {
            this.givenResponse = givenResponse;
            this.isCorrect = isCorrect;
        }
    }

    private static AnswerSelection selectAnswer(Session session) {
        String mcAnswersJson = session.getString("multipleChoiceAnswers");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{\\s*\\\"id\\\":\\s*\\d+,\\s*\\\"text\\\":\\s*\\\"(.*?)\\\",\\s*\\\"correct\\\":\\s*(true|false)");
        java.util.regex.Matcher m = p.matcher(mcAnswersJson);
        java.util.List<String> answers = new java.util.ArrayList<>();
        java.util.List<Boolean> correctFlags = new java.util.ArrayList<>();
        while (m.find()) {
            answers.add(m.group(1));
            correctFlags.add(Boolean.parseBoolean(m.group(2)));
        }
        int idx;
        boolean pickCorrect = ThreadLocalRandom.current().nextDouble() < 0.9;
        if (pickCorrect) {
            java.util.List<Integer> correctIndices = new java.util.ArrayList<>();
            for (int i = 0; i < correctFlags.size(); i++) {
                if (correctFlags.get(i)) correctIndices.add(i);
            }
            idx = correctIndices.isEmpty() ? 0 : correctIndices.get(ThreadLocalRandom.current().nextInt(correctIndices.size()));
        } else {
            java.util.List<Integer> incorrectIndices = new java.util.ArrayList<>();
            for (int i = 0; i < correctFlags.size(); i++) {
                if (!correctFlags.get(i)) incorrectIndices.add(i);
            }
            idx = incorrectIndices.isEmpty() ? 0 : incorrectIndices.get(ThreadLocalRandom.current().nextInt(incorrectIndices.size()));
        }
        return new AnswerSelection(answers.get(idx), correctFlags.get(idx));
    }

    private static boolean extractStudyTrial(Session session) {
        if (session.contains("studyTrial")) {
            Object firstTimeObj = session.get("studyTrial");
            if (firstTimeObj instanceof Boolean) {
                return (Boolean) firstTimeObj;
            } else if (firstTimeObj instanceof String) {
                return Boolean.parseBoolean((String) firstTimeObj);
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
            .set("studyTrial", studyTrial);
    }

    ChainBuilder createUserAndDoTest = feed(userFeeder)
            .exec(http("Create User")
                    .post("/user/create")
                    .body(StringBody("{ \"dateOfBirth\": \"${dateOfBirth}\", \"sex\": \"${sex}\", \"country\": \"${country}\", \"education\": \"${education}\"}"))
                    .check(status().is(200))
                    .check(jsonPath("$.id").saveAs("createdUserId"))
            )
            .pause(1)
            .exec(http("Initialize Session")
                    .post("/session/test")
                    .body(StringBody("{ \"userId\": \"${createdUserId}\", \"id\": 1, \"timezone\": \"Europe/Amsterdam\" }"))
                    .check(status().is(200))
                    .check(jsonPath("$.sessionId").saveAs("sessionId"))
                    .check(jsonPath("$.userId").saveAs("userId"))
                    .check(jsonPath("$.cue.fact.id").saveAs("factId"))
                    .check(jsonPath("$.cue.fact.answer").saveAs("answer"))
                    .check(jsonPath("$.cue.multipleChoiceAnswers").saveAs("multipleChoiceAnswers"))
                    .check(jsonPath("$.cue.studyTrial").saveAs("studyTrial"))

            )
            .pause(1, 3)
            .repeat(50).on(
                    exec(HealthAPISimulation::prepareResponseSession)
                            .exec(http("Save Response")
                                    .post("/response/save")
                                    .body(StringBody("{ " +
                                                     "\"sessionId\": \"${sessionId}\", " +
                                                     "\"factId\": ${factId}, " +
                                                     "\"userId\": \"${userId}\", " +
                                                     "\"reactionTime\": ${reactionTime}, " +
                                                     "\"presentationStartTime\": ${presentationStartTime}, " +
                                                     "\"presentationDuration\": ${presentationDuration}, " +
                                                     "\"sessionTime\": ${sessionTime}, " +
                                                     "\"data\": \"\", " +
                                                     "\"givenResponse\": \"${givenResponse}\", " +
                                                     "\"correct\": ${isCorrect}, " +
                                                     "\"multipleChoiceAnswers\": ${multipleChoiceAnswers}, " +
                                                     "\"studyTrial\": ${studyTrial} " +
                                                     "}"))
                                    .check(status().is(200))
                                    .check(jsonPath("$.cue.fact.id").saveAs("factId"))
                                    .check(jsonPath("$.cue.fact.answer").saveAs("answer"))
                                    .check(jsonPath("$.cue.multipleChoiceAnswers").saveAs("multipleChoiceAnswers"))
                                    .check(jsonPath("$.cue.studyTrial").saveAs("studyTrial"))
                            )
                            .pause(1, 3)
            )
            .exec(http("Get Test Statistics")
                    .post("/session/end")
                    .body(StringBody("{\"sessionId\": \"${sessionId}\"}"))
                    .check(status().is(200))
            );


    HttpProtocolBuilder httpProtocol =
            http.baseUrl(Config.getBaseUrl())
                    .acceptHeader("application/json,text/plain,*/*")
                    .acceptLanguageHeader("nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7,de;q=0.6")
                    .acceptEncodingHeader("gzip, deflate, br")
                    .contentTypeHeader("application/json")
                    .userAgentHeader("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0")
                    .authorizationHeader("Bearer integration-test");

    ScenarioBuilder createUserAndTestScenario = scenario("Create User and Test Scenario")
            .exec(createUserAndDoTest);

    {
        setUp(
                createUserAndTestScenario.injectOpen(rampUsers(3).during(3))
        ).protocols(httpProtocol);
    }
}
