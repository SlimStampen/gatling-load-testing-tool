package ssaas;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;


public class SSaaSSimulation extends Simulation {
    FeederBuilder<String> userFeeder = csv("api_users.csv").circular();

    // Generate random IDs once per simulation run (shared across all users)
    private final List<Integer> randomIds;

    {
        // Initialize random IDs at simulation startup
        randomIds = new ArrayList<>();
        Random random = new Random();
        for (int i = 0; i < 16; i++) {
            randomIds.add(random.nextInt(1000)); // Random IDs between 0 and 999,999
        }
    }

    // Helper method to create shuffled facts JSON
    private String getShuffledFactsJson() {
        List<String> facts = new ArrayList<>(Arrays.asList(
                """
                {
                  "id": %d,
                  "cueTexts": ["the book"],
                  "answers": ["le livre"],
                  "distractors": ["le chat", "la maison", "l'eau"]
                }""".formatted(randomIds.get(0)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the tree"],
                  "answers": ["l'arbre"],
                  "distractors": ["la fleur", "le lac", "le ciel"]
                }""".formatted(randomIds.get(1)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the bank"],
                  "answers": ["la banque"],
                  "distractors": ["le banc", "la monnaie", "le billet"]
                }""".formatted(randomIds.get(2)),
                """
                {
                  "id": %d,
                  "cueTexts": ["three"],
                  "answers": ["trois"],
                  "distractors": ["un", "deux", "quatre"]
                }""".formatted(randomIds.get(3)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the water"],
                  "answers": ["l'eau"],
                  "distractors": ["le feu", "l'air", "la terre"]
                }""".formatted(randomIds.get(4)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the house"],
                  "answers": ["la maison"],
                  "distractors": ["l'appartement", "le château", "la cabane"]
                }""".formatted(randomIds.get(5)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the cat"],
                  "answers": ["le chat"],
                  "distractors": ["le chien", "l'oiseau", "le poisson"]
                }""".formatted(randomIds.get(6)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the flower"],
                  "answers": ["la fleur"],
                  "distractors": ["la rose", "la tulipe", "le jardin"]
                }""".formatted(randomIds.get(7)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the dog"],
                  "answers": ["le chien"],
                  "distractors": ["le chat", "le loup", "le renard"]
                }""".formatted(randomIds.get(8)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the table"],
                  "answers": ["la table"],
                  "distractors": ["la chaise", "le bureau", "le lit"]
                }""".formatted(randomIds.get(9)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the sun"],
                  "answers": ["le soleil"],
                  "distractors": ["la lune", "l'étoile", "le nuage"]
                }""".formatted(randomIds.get(10)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the moon"],
                  "answers": ["la lune"],
                  "distractors": ["le soleil", "l'étoile", "la planète"]
                }""".formatted(randomIds.get(11)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the car"],
                  "answers": ["la voiture"],
                  "distractors": ["le vélo", "le bus", "le train"]
                }""".formatted(randomIds.get(12)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the bird"],
                  "answers": ["l'oiseau"],
                  "distractors": ["le chat", "le papillon", "l'aigle"]
                }""".formatted(randomIds.get(13)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the bread"],
                  "answers": ["le pain"],
                  "distractors": ["le gâteau", "la baguette", "le croissant"]
                }""".formatted(randomIds.get(14)),
                """
                {
                  "id": %d,
                  "cueTexts": ["the door"],
                  "answers": ["la porte"],
                  "distractors": ["la fenêtre", "le mur", "le toit"]
                }""".formatted(randomIds.get(15))
        ));

        Collections.shuffle(facts);

        return """
                {
                  "context": "French vocabulary chapter 4",
                  "randomOrder": true,
                  "userId": 123,
                  "facts": [%s],
                  "answerMethod": "MULTIPLE_CHOICE_4",
                  "timezone": "Europe/Amsterdam"
                }""".formatted(String.join(",", facts));
    }

    ChainBuilder sessionInitializationChain = feed(userFeeder)
            .exec(session -> {
                // Generate shuffled facts for each user
                String requestBody = getShuffledFactsJson();
                return session.set("requestBody", requestBody);
            })
            .exec(http("Initialize Session")
                    .post("/v1/session/initialize")
                    .body(StringBody("#{requestBody}"))
                    .check(status().is(200)));


    ChainBuilder versionChain = feed(userFeeder)
            .exec(http("Get Version")
                    .get("/v1/version?json=true")
                    .check(status().is(200)));


    HttpProtocolBuilder httpProtocol =
            http.baseUrl(Config.getBaseUrl())
                    .acceptHeader("application/json,text/plain,*/*")
                    .acceptLanguageHeader("nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7,de;q=0.6")
                    .acceptEncodingHeader("gzip, deflate, br")
                    .contentTypeHeader("application/json")
                    .userAgentHeader("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0")
                    .authorizationHeader("Bearer gatling-token");

    ScenarioBuilder sessionInitializationScenario = scenario("Session Initialization Scenario")
            .exec(sessionInitializationChain);

    ScenarioBuilder versionScenario = scenario("Version Scenario")
            .exec(versionChain);

    {
        setUp(
                versionScenario.injectOpen(atOnceUsers(2)),
                sessionInitializationScenario.injectOpen(atOnceUsers(200))
        ).protocols(httpProtocol);
    }
}
