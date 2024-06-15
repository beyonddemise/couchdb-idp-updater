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

import java.util.ArrayList;
import java.util.List;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

@ApplicationScoped
/**
 * The main application class for initializing and running the application.
 */
public class App {

    /**
     * The main entry point of the application when started manually.
     *
     * @param args The command line arguments
     */
    public static void main(String[] args) {
        System.out.println("Manually starting the application.");
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new IdpUpdater());
        System.out.println("Application started.");
    }

    /**
     * Initializes the application on startup.
     *
     * @param e The startup event
     * @param vertx The Vert.x instance
     * @param verticles The collection of verticles to deploy
     */
    public void init(@Observes StartupEvent e, Vertx vertx, Instance<AbstractVerticle> verticles) {

        System.out.println(e.getClass().getName());

        List<Future<?>> loadedVerticles = new ArrayList<>();
        for (AbstractVerticle verticle : verticles) {
            loadedVerticles.add(vertx.deployVerticle(verticle));
        }
        Future.all(loadedVerticles)
                .onSuccess(v -> System.out.println("All up and running"))
                .onFailure(System.err::println);
    }
}
