package ssaas;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static ssaas.SSaaSSimulationBase.*;


public class SSaaSV2Simulation extends Simulation {

    private static ChainBuilder buildFinishedStatsRequest() {
        return exec(http("Get Finished Stats")
                .get("/v2/statistics/session/finish/#{sessionId}")
                .check(status().is(200))
                // Verify base response parameters
                .check(jsonPath("$.currentSessionTime").saveAs("currentSessionTime"))
                .check(jsonPath("$.totalSessionTime").saveAs("totalSessionTime"))
                .check(jsonPath("$.currentAverageReactionTime").saveAs("currentAverageReactionTime"))
                .check(jsonPath("$.totalAverageReactionTime").saveAs("totalAverageReactionTime"))
                .check(jsonPath("$.correctAnswers").saveAs("correctAnswers"))
                .check(jsonPath("$.incorrectAnswers").saveAs("incorrectAnswers"))
                // Verify slowestRTFact (can be null or a full fact object with UUID)
                .check(jsonPath("$.slowestRTFact.id").optional().saveAs("slowestRTFactId"))
                .check(jsonPath("$.slowestRTFact.cueTexts[0]").optional().saveAs("slowestRTFactCueText"))
                .check(jsonPath("$.slowestRTFact.answers[0]").optional().saveAs("slowestRTFactAnswer"))
                // Verify mostIncorrectRTFact (can be null or a full fact object with UUID)
                .check(jsonPath("$.mostIncorrectRTFact.id").optional().saveAs("mostIncorrectRTFactId"))
                .check(jsonPath("$.mostIncorrectRTFact.cueTexts[0]").optional().saveAs("mostIncorrectRTFactCueText"))
                .check(jsonPath("$.mostIncorrectRTFact.answers[0]").optional().saveAs("mostIncorrectRTFactAnswer"))
        )
        .exec(session -> {
            // Validate UUID format for slowestRTFact if it exists
            String slowestRTFactId = session.getString("slowestRTFactId");
            if (slowestRTFactId != null && !slowestRTFactId.equals("null")) {
                validateUuidFormat(slowestRTFactId);
            }

            // Validate UUID format for mostIncorrectRTFact if it exists
            String mostIncorrectRTFactId = session.getString("mostIncorrectRTFactId");
            if (mostIncorrectRTFactId != null && !mostIncorrectRTFactId.equals("null")) {
                validateUuidFormat(mostIncorrectRTFactId);
            }

            return session;
        });
    }

    ChainBuilder practiceSessionFlow = exec(session -> {
                // Generate UUID userId for V2
                String userId = generateUuidUserId();

                // Build facts list based on configuration (V2 uses UUIDs)
                // - If USE_SAME_FACT_IDS_FOR_ALL_USERS = true: use shared UUIDs (cache testing)
                // - If USE_SAME_FACT_IDS_FOR_ALL_USERS = false: generate unique UUIDs (UUID mapping testing)
                List<Fact> userFacts = buildFactsListV2(USE_SAME_FACT_IDS_FOR_ALL_USERS);

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
                    sessionContext, userId, factsJson
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
                    .check(jsonPath("$.cue.fact.id").transform(factId -> {
                        validateUuidFormat(factId);
                        return factId;
                    }).exists())
                    .check(jsonPath("$.cue.fact.answers[0]").saveAs("correctAnswer"))
                    .check(jsonPath("$.cue.fact.presentedCueTextIndex").saveAs("presentedCueTextIndex"))
                    .check(jsonPath("$.cue.fact.presentedImageIndex").saveAs("presentedImageIndex"))
                    .check(jsonPath("$.sessionProgress.achievedCredit").saveAs("achievedCredit"))
            )
            .pause(1, 2)
            .repeat(30).on(
                    exec(SSaaSSimulationBase::prepareResponseSession)
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
                                    .check(jsonPath("$.cue.fact.id").transform(factId -> {
                                        validateUuidFormat(factId);
                                        return factId;
                                    }).exists())
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
            )
            .exec(buildFinishedStatsRequest());

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
                practiceSessionScenario.injectOpen(rampUsers(50).during(20))
//                practiceSessionScenario.injectOpen(atOnceUsers(40))

        ).protocols(httpProtocol);
    }
}
