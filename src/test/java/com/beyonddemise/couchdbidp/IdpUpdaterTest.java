package com.beyonddemise.couchdbidp;

import static io.restassured.RestAssured.given;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
class IdpUpdaterTest {

    static String verticleId = null;

    @BeforeAll
    static void deploy_verticle(Vertx vertx, VertxTestContext testContext) {
        vertx.deployVerticle(new IdpUpdater(), testContext.succeeding(id -> {
            verticleId = id;
            testContext.completeNow();
        }));
    }

    @AfterAll
    static void undeploy_verticle(Vertx vertx, VertxTestContext testContext) {
        if (verticleId != null) {
            vertx.undeploy(verticleId, testContext.succeeding(v -> testContext.completeNow()));
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void getRoot() {
        given()
                .port(8080)
                .when()
                .get("/status")
                .then()
                .header("Content-Type", "text/html;charset=utf-8");

    }


}
