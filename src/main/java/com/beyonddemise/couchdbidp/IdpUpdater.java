/*
 * (C) 2024 notessensei (stw@linux.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.beyonddemise.couchdbidp;

import java.util.Date;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.StaticHandler;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The IdpUpdater class is responsible for updating the CouchDB IdP (Identity Provider).
 * It extends the AbstractVerticle class and implements the necessary methods for starting and
 * stopping the application.
 * The IdpUpdater class also sets up the server, handles routing, and manages the scheduler for
 * updating the keys from the IdP data.
 */
@ApplicationScoped
public class IdpUpdater extends AbstractVerticle {

    static final String DEFAULT_CSP_VALUE = "default-src 'self'; img-src 'self' data:;";
    static final String HEADER_CSP = "Content-Security-Policy";
    static final String HEADER_CONTENT_TYPE = "content-type";
    static final String HEADER_JSON = "application/json; charset=UTF8";

    static Handler<RoutingContext> contentSecurityHeaderHandler = ctx -> {
        final HttpServerResponse response = ctx.response();
        if (response.headers().contains(HEADER_CSP)) {
            response.headers().remove(HEADER_CSP);
        }
        response.putHeader(HEADER_CSP, DEFAULT_CSP_VALUE);
        ctx.next();
    };

    static Handler<RoutingContext> rootHandler = StaticHandler.create()
            .setFilesReadOnly(true)
            .setIndexPage("index.html")
            .setDefaultContentEncoding("UTF-8");

    Handler<RoutingContext> statusHandler = ctx -> {

        SharedData sd = this.getVertx().sharedData();
        LocalMap<String, JsonObject> statusMap = sd.getLocalMap("status");
        JsonObject status = statusMap.getOrDefault("status", new JsonObject());
        ctx.json(status);
    };

    /**
     * Handles the case when something goes wrong in the routing process.
     * If there is a failure, it sets the appropriate status code and response message.
     * If there is no failure, it sets the status code to 500 and returns a generic error message.
     *
     * @param ctx the routing context
     */
    Handler<RoutingContext> youScrewedUp = ctx -> {

        Throwable f = ctx.failure();
        JsonObject response = new JsonObject()
                .put("message", f.getMessage())
                .put("code", ctx.statusCode());

        ctx.response()
                .putHeader(HEADER_CONTENT_TYPE, HEADER_JSON)
                .setStatusCode(ctx.statusCode())
                .end(response.toBuffer());
    };


    /**
     * Starts the CouchDB IdP updater.
     * This method is called when the application starts. It performs the following steps:
     * 1. Prints a message indicating that the CouchDB IdP updater is up and running.
     * 2. Reads the configuration.
     * 3. Brings up the server.
     * 4. Starts the scheduler.
     * If any of the steps fail, the start promise is failed. Otherwise, the start promise is
     * completed.
     *
     * @param startPromise a Promise that will be completed or failed based on the success or
     *        failure of the start process.
     * @throws Exception if an error occurs during the start process.
     */
    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        System.out.println("CouchDB IdP updater up and away");
        this.readConfig()
                .compose(v -> bringupTheServer())
                .compose(v -> this.startScheduler())
                .onFailure(startPromise::fail)
                .onSuccess(v -> startPromise.complete());
    }

    /**
     * Starts the scheduler to update the keys from the IdP data.
     * The scheduler runs every 6 hours by default. It updates the keys from the IdP data.
     *
     * @return a Future that will be completed when the scheduler is started.
     */
    Future<Void> startScheduler() {

        long interval = this.config().getLong("UpdateIntervalSeconds", 21600L) * 1000;
        this.getVertx().setPeriodic(10000, interval, id -> {
            System.out.printf("Scheduler running: %s%n", new Date());
            IdpClient client = new IdpClient(this.config(), this.getVertx());
            client.updateKeys()
                    .onSuccess(v -> {
                        System.out.printf("Keys updated %s%n", new Date());
                        SharedData sd = this.vertx.sharedData();
                        LocalMap<String, JsonObject> statusMap = sd.getLocalMap("status");
                        JsonObject status = statusMap.getOrDefault("status", new JsonObject());
                        System.out.println(status.encodePrettily());
                    })
                    .onFailure(e -> System.out.printf("Failed to update keys %s: %s%n", new Date(),
                            e.getMessage()));

        });
        return Future.succeededFuture();
    }

    /**
     * Reads the configuration from the "data/config.json" file and merges it into the current
     * configuration.
     *
     * @return a Future that completes when the configuration is successfully read and merged
     */
    Future<Void> readConfig() {
        Promise<Void> promise = Promise.promise();

        this.getVertx().fileSystem().readFile("data/config.json")
                .onFailure(promise::fail)
                .onSuccess(buffer -> {
                    JsonObject config = buffer.toJsonObject()
                            .put("user.dir", System.getProperty("user.dir"))
                            .put("couchdbUser", System.getenv("COUCHDB_USER"))
                            .put("couchdbPwd", System.getenv("COUCHDB_PWD"));
                    this.config().mergeIn(config, true);
                    promise.complete();
                });

        return promise.future();
    }


    /**
     * Stops the IdpUpdater.
     *
     * @param stopPromise a Promise that will be completed when the IdpUpdater is stopped
     * @throws Exception if an error occurs while stopping the IdpUpdater
     */
    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        System.out.println("Gone with the wind!");
        stopPromise.complete();
    }

    /**
     * Starts the server and sets up the router actions.
     *
     * @param promise
     */
    Future<Void> bringupTheServer() {

        Promise<Void> promise = Promise.promise();
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        router.route().handler(contentSecurityHeaderHandler);
        router.route().failureHandler(youScrewedUp);
        router.route("/status").handler(statusHandler);
        router.route().handler(rootHandler);

        server.requestHandler(router).listen(8080)
                .onSuccess(r -> {
                    System.out.printf("%nServer up and running on port %s%n%n", r.actualPort());
                    promise.complete();
                })
                .onFailure(promise::fail);
        return promise.future();
    }

}
