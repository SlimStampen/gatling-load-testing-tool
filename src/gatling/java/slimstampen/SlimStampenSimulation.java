package slimstampen;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class SlimStampenSimulation extends Simulation {

    private static final int AMOUNT_OF_RESPONSES = 100;
    private static final int AMOUNT_OF_USERS = 100;
    private static final String TEST_BASE_URL = "https://gatling.test.slimstampen.nl/ruggedlearning";
    private static final String STAGING_BASE_URL = "https://gatling.staging.slimstampen.nl/ruggedlearning";
    private static final int TEST_LESSON_ID = 892;
    private static final int STAGING_LESSON_ID = 110;
    private static final String TEST_RESPONSES = "gatling_responses_test.json";
    private static final String STAGING_RESPONSES = "gatling_responses_staging.json";
    private static final String BASE_URL = TEST_BASE_URL;
    private static final String RESPONSES = TEST_RESPONSES;
    private static final int LESSON_ID = TEST_LESSON_ID;

    FeederBuilder<String> userFeeder = csv("users.csv").circular();
    FeederBuilder<Object> responseFeeder = jsonFile(RESPONSES).random();

    ChainBuilder jwks =
            exec(http("JWKS").get("/.well-known/jwks.json").check(status().is(200)));

    ChainBuilder loginAndPractice = feed(userFeeder)
            .exec(http("login")
                    .post("/api/user/login")
                    .body(StringBody("{ \"username\": \"#{email}\", \"password\": \"#{password}\"}"))
                    .check(header("Set-Cookie").exists().saveAs("session"))
            )
            .exec(addCookie(Cookie("Cookie", "#{session}")))
            .pause(1)
            .exec(http("profile_get")
                    .get("/api/profile/get")
                    .check(bodyString().saveAs("body"))
                    .check(status().is(200))
                    .check(jsonPath("$.anonymous").ofBoolean().is(false))
            )
            .pause(2)
            .exec(http("get_first_cue")
                    .get("/api/response/getFirstCue/" + LESSON_ID)
                    .check(bodyString().saveAs("first_cue"))
                    .check(jsonPath("$.sessionId").saveAs("initialized_session_id")) // comment out this line for staging
                    .check(status().is(200)))
            .pause(2)
            .repeat(AMOUNT_OF_RESPONSES).on(
                    feed(responseFeeder)
                            .pause(session -> Duration.ofMillis(2000), session -> Duration.ofMillis(3000))
                            .exec(http("response_save")
                                    .post("/api/response/save")
                                    .body(StringBody("{\"alternatives\": \"[]\", " +
                                            "\"answerMethod\": \"#{answer_method}\"," +
                                            "\"backSpaceUsed\": \"#{backspace_used}\", " +
                                            "\"backSpacedFirstLetter\": \"#{backspaced_first_letter}\", " +
                                            "\"correct\": \"#{correct}\", " +
                                            "\"factId\": \"#{fact_id}\", " +
                                            "\"givenResponse\": \"#{given_response}\", " +
                                            "\"lessonId\": \""+ LESSON_ID +"\", " +
                                            "\"mostDifficult\": \"false\", " +
                                            "\"presentationDuration\": \"#{presentation_duration}\", " +
                                            "\"presentationStartTime\": \"#{presentation_start_time}\", " +
                                            "\"presentedCueTextIndex\": \"#{presented_cue_text_index}\", " +
                                            "\"reactionTime\": \"#{reaction_time}\", " +
//                                            "\"sessionId\": \"#{initialized_session_id}\", " + // comment out this for staging
                                            "\"sessionId\": \"#{session_id}\", " + // comment out this for test
                                            "\"sessionTime\": \"#{session_time}\"}"))
                                    .check(status().is(200))
                            )
            );

    ChainBuilder loginAndCheckStatistics = feed(userFeeder)
            .exec(http("login")
                    .post("/api/user/login")
                    .body(StringBody("{ \"username\": \"#{email}\", \"password\": \"#{password}\"}"))
                    .check(header("Set-Cookie").exists().saveAs("session"))
            )
            .pause(1)
            .exec(addCookie(Cookie("Cookie", "#{session}")))
            .exec(http("profile_get")
                    .get("/api/profile/get/en-GB")
                    .check(bodyString().saveAs("body"))
                    .check(status().is(200))
                    .check(jsonPath("$.anonymous").ofBoolean().is(false))
            )
            .exec(http("get_sort_option")
                    .get("/api/lesson-group/sort")
                    .check(status().is(200))
            )
            .exec(http("get_environment")
                    .get("/api/environment")
                    .check(status().is(200))
            )
            .exec(http("get_domain")
                    .get("/api/domain")
                    .check(status().is(200))
            )
            .exec(http("get_categories")
                    .get("/api/category/all")
                    .check(status().is(200))
            )
            .exec(http("load_lesson")
                    .get("/api/lesson-group/library?pageNo=0&pageSize=50&categoryIds=&searchTerm=&sortBy=POPULARITY&sortDirection=ASC")
                    .check(status().is(200)))
            .exec(http("get_lesson_info")
                    .get("/api/lesson-info/" + LESSON_ID)
                    .check(status().is(200))
            )
            .exec(http("get_stats")
                    .get("/api/stats/lesson/" + LESSON_ID + "?timezone=Europe%2FAmsterdam")
                    .check(status().is(200))
            );

    HttpProtocolBuilder httpProtocol =
            http.baseUrl(BASE_URL)
                    .acceptHeader("application/json,text/plain,*/*")
                    .acceptLanguageHeader("nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7,de;q=0.6")
                    .acceptEncodingHeader("gzip, deflate, br")
                    .contentTypeHeader("application/json")
                    .userAgentHeader(
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0"
                    );

    ScenarioBuilder json = scenario("Json").exec(jwks);
    ScenarioBuilder loginScenario = scenario("Login and practice").exec(loginAndPractice);
    ScenarioBuilder classroomScenario = scenario("Login, load the library and check statistics").exec(loginAndCheckStatistics);

    {
        setUp(
                json.injectOpen(rampUsers(AMOUNT_OF_USERS).during(50)),
                loginScenario.injectOpen(rampUsers(AMOUNT_OF_USERS).during(AMOUNT_OF_USERS)),
                classroomScenario.injectOpen(atOnceUsers(30))
        ).protocols(httpProtocol);
    }
}
