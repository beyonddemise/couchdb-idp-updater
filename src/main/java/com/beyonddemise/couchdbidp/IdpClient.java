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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.authentication.UsernamePasswordCredentials;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;

/**
 * Represents a client for interacting with the Identity Provider (IdP)
 * and the couchDB, pulling public keys from the IdP and distributing them to the couchDB servers.
 */
public class IdpClient {

  private final JsonObject config;
  private final WebClient client;
  private final Vertx vertx;
  private final AtomicInteger counter = new AtomicInteger(0);

  /**
   * Constructs a new instance of the IdpClient class.
   *
   * @param config The configuration for the IdP client.
   * @param vertx The Vert.x instance used for creating the WebClient.
   */
  public IdpClient(final JsonObject config, final Vertx vertx) {
    this.config = config;
    this.client = WebClient.create(vertx);
    this.vertx = vertx;
  }

  /**
   * Updates the keys from the Identity Provider (IdP) and distributes them.
   * Main entry point for the IdpClient class.
   *
   * @return a Future representing the completion of the update operation
   */
  public Future<Void> updateKeys() {
    Promise<Void> promise = Promise.promise();
    // Collect the keys from the IdP, then distribute them out
    collectKeys()
        .compose(this::distributeKeys)
        .onSuccess(v -> promise.complete())
        .onFailure(promise::fail);

    return promise.future();
  }

  /**
   * Distributes the given keys to multiple CouchDB servers asynchronously.
   * Gets called after the keys are collected from the IdP by @see collectKeys.
   *
   * @param keys a map of keys to be distributed
   * @return a Future representing the completion of the distribution process
   */
  Future<Void> distributeKeys(Map<String, String> keys) {

    Promise<Void> promise = Promise.promise();
    JsonArray servers = this.config.getJsonArray("CouchDBservers", new JsonArray());
    System.out.printf("Found %d servers to update%n", servers.size());
    List<Future<Void>> finishedUpdates = new ArrayList<>();
    servers
        .forEach(server -> finishedUpdates.add(distributeKeysToOneServer(server.toString(), keys)));
    Future.join(finishedUpdates)
        .onFailure(promise::fail)
        .onSuccess(v -> promise.complete());

    return promise.future();
  }

  /**
   * Distributes keys to a single server in the CouchDB cluster.
   * Gets called by @see distributeKeys.
   *
   * @param server The URL of the server to distribute the keys to.
   * @param keys A map of keys to be distributed.
   * @return A Future representing the completion of the distribution process.
   */
  Future<Void> distributeKeysToOneServer(String server, Map<String, String> keys) {

    Promise<Void> promise = Promise.promise();

    String url = server + "/_membership";
    String user = this.config.getString("couchdbUser");
    String pwd = this.config.getString("couchdbPwd");

    Credentials cred = new UsernamePasswordCredentials(user, pwd);

    this.client.getAbs(url)
        .authentication(cred)
        .expect(ResponsePredicate.SC_SUCCESS)
        .expect(ResponsePredicate.JSON)
        .send()
        .onSuccess(response -> {
          JsonObject body = response.bodyAsJsonObject();
          JsonArray nodes = body.getJsonArray("cluster_nodes", new JsonArray());
          System.out.printf("Found %d nodes on server %s%n", nodes.size(), server);
          List<Future<Void>> finishedUpdates = new ArrayList<>();
          nodes.forEach(node -> finishedUpdates
              .add(distributeKeysToOneServerNode(server, node.toString(), cred, keys)));
          Future.join(finishedUpdates)
              .onFailure(promise::fail)
              .onSuccess(v -> promise.complete());
        })
        .onFailure(promise::fail);

    return promise.future();
  }

  /**
   * Distributes the provided keys to a specific server node in a cluster,
   * gets called by @see distributeKeysToOneServer.
   *
   * @param server the server URL
   * @param node the node ID
   * @param cred the credentials for authentication
   * @param keys the map of keys to distribute
   * @return a Future representing the completion of the distribution process
   */
  Future<Void> distributeKeysToOneServerNode(String server, String node, Credentials cred,
      Map<String, String> keys) {
    Promise<Void> promise = Promise.promise();

    String url = String.format("%s/_node/%s/_config/jwt_keys", server, node);
    this.client.getAbs(url)
        .authentication(cred)
        .expect(ResponsePredicate.SC_SUCCESS)
        .expect(ResponsePredicate.JSON)
        .send()
        .onFailure(promise::fail)
        .onSuccess(response -> {
          JsonObject body = response.bodyAsJsonObject();
          System.out.printf("node %s has %d keys%n", node, body.size());
          List<Future<Void>> finishedUpdates = new ArrayList<>();
          keys.forEach((key, value) -> {
            System.out.printf("checking Key: %s%n", key);
            String candidate = body.getString(key);
            if (value.equals(candidate)) {
              System.out.printf("Exisiting key is current: %s%n", key);
            } else {
              String nodeUrl = String.format("%s/_node/%s/_config/jwt_keys/%s", server, node, key);
              finishedUpdates.add(distributeOneKeyToOneServerNode(nodeUrl, cred, value));
            }
          });

          Future.join(finishedUpdates).onComplete(v -> {
            if (!finishedUpdates.isEmpty()) {
              // stagger reboots in increments of 5 seconds
              long delay = 5000L * counter.incrementAndGet();
              this.vertx.setTimer(delay, id -> {
                System.out.printf("Rebooting node %s on server %s%n", node, server);
                String rebootUrl = String.format("%s/_node/%s/_restart", server, node);
                this.client.postAbs(rebootUrl)
                    .authentication(cred)
                    .expect(ResponsePredicate.SC_SUCCESS)
                    .expect(ResponsePredicate.JSON)
                    .send()
                    .onFailure(err -> System.out.printf(
                        "Failed to request reboot node %s on server %s: %s%n", node, server,
                        err.getMessage()))
                    .onSuccess(v1 -> System.out
                        .printf("Reboot request sent to node %s on server %s%n", node, server));
              });

            }
            promise.complete();
          });

        });

    return promise.future();

  }

  /**
   * Distributes one key to one server node.
   * Gets called by @see distributeKeysToOneServerNode.
   *
   * @param url The URL of the server node.
   * @param cred The credentials for authentication.
   * @param keyValue The value of the key to be distributed.
   * @return A Future representing the completion of the distribution process.
   */
  Future<Void> distributeOneKeyToOneServerNode(String url, Credentials cred, String keyValue) {
    Promise<Void> promise = Promise.promise();
    Buffer buff = Buffer.buffer("\"");
    buff.appendString(keyValue.replace("\\n", "\\\\n"), "UTF-8");
    buff.appendString("\"");
    System.out.println("updating: " + url);
    this.client.putAbs(url)
        .authentication(cred)
        .putHeader("content-type", "application/json")
        .expect(ResponsePredicate.SC_SUCCESS)
        .sendBuffer(buff)
        .onFailure(promise::fail)
        .onSuccess(response -> {
          SharedData sd = this.vertx.sharedData();
          LocalMap<String, JsonObject> statusMap = sd.getLocalMap("status");
          JsonObject status = statusMap.getOrDefault("status", new JsonObject());
          status.put(url, new Date().toString());
          statusMap.put("status", status);
          promise.complete();
        });

    return promise.future();
  }

  /**
   * Collects the keys from the configured IdPs.
   * First operation in the key update process.
   * Can be one or more key servers
   *
   * @return a Future containing a Map of IdP keys, where the key is the IdP name and the value is
   *         the key
   */
  Future<Map<String, String>> collectKeys() {
    Promise<Map<String, String>> promise = Promise.promise();
    Map<String, String> result = new HashMap<>();

    List<Future<JsonObject>> potentialKeys =
        this.config.getJsonArray("IdPs", new JsonArray()).stream()
            .map(String::valueOf).map(this::retrieveKey).collect(Collectors.toList());

    Future.join(potentialKeys).onComplete(ar -> {
      ar.result().list().stream().filter(r -> r != null).map(r -> (JsonObject) r)
          .forEach(entry -> extractPublicKeys(entry, result));
      if (result.isEmpty()) {
        promise.fail("No keys were retrieved from the IdP");
      } else {
        promise.complete(result);
      }
    });

    return promise.future();
  }

  /**
   * Extracts public keys from the given JSON object and adds them to the provided result map.
   * called by @see collectKeys. Once per server
   *
   * @param entry The JSON object containing the keys.
   * @param result The map to store the extracted public keys.
   */
  void extractPublicKeys(final JsonObject entry, Map<String, String> result) {

    entry.getJsonArray("keys", new JsonArray()).stream()
        .filter(JsonObject.class::isInstance)
        .map(JsonObject.class::cast)
        .forEach(j -> {
          String key =
              String.format("%s:%s", j.getString("kty", "RSA").toLowerCase(), j.getString("kid"));
          String alg = j.getString("alg");
          JsonArray x5cArray = j.getJsonArray("x5c");
          // FIXME: all certs
          String pemCert = "-----BEGIN CERTIFICATE-----\n" + x5cArray.getString(0)
              + "\n-----END CERTIFICATE-----";
          String pemKey;
          try {
            pemKey = extractPublicKeyFromCert(pemCert, alg);
            System.out.printf("Key %s (%s)%n", key, alg);
            result.put(key, pemKey);
          } catch (CertificateException e1) {
            e1.printStackTrace();
          }
        });
  }

  /**
   * Extracts the public key from a PEM certificate and returns it as a string representation.
   *
   * @param pemCert the PEM certificate string
   * @param alg the algorithm used in the certificate (e.g., "RSA", "EC")
   * @return the public key as a string representation
   * @throws CertificateException if an error occurs while parsing the certificate
   * @throws IllegalArgumentException if the algorithm is not supported
   */
  String extractPublicKeyFromCert(String pemCert, String alg) throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    X509Certificate cert = (X509Certificate) factory
        .generateCertificate(new ByteArrayInputStream(pemCert.getBytes(StandardCharsets.UTF_8)));

    PublicKey publicKey = cert.getPublicKey();
    StringWriter writer = new StringWriter();
    try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
      if (alg.startsWith("RS")) {
        pemWriter.writeObject(publicKey);
      } else if (alg.startsWith("ES")) {
        SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
        pemWriter.writeObject(spki);
      } else {
        throw new IllegalArgumentException("Unsupported algorithm: " + alg);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    return writer.toString().replace("\n", "\\n");

  }

  /**
   * Retrieves the key from the Identity Provider (IdP) using the specified URL.
   * Gets called by @see collectKeys once per IdP server.
   *
   * @param url the base URL of the IdP
   * @return a Future that represents the asynchronous result of the key retrieval
   */
  Future<JsonObject> retrieveKey(final String url) {
    Promise<JsonObject> promise = Promise.promise();

    // Retrieve the key from the IdP
    final String wellKnownUrl = url + "/.well-known/openid-configuration";
    this.client.getAbs(wellKnownUrl)
        .expect(ResponsePredicate.SC_SUCCESS)
        .expect(ResponsePredicate.JSON)
        .send()
        .compose(this::getJksKey)
        .onSuccess(promise::complete)
        .onFailure(e -> {
          System.out.printf("Failed to retrieve key from %s: %s%n", url, e.getMessage());
          promise.fail(e);
        });
    return promise.future();
  }

  /**
   * Retrieves the JWKS key from the provided OpenID configuration response.
   * callwed by @see retrieveKey.
   *
   * @param response The HTTP response containing the OpenID configuration.
   * @return A Future that resolves to a JsonObject representing the JWKS key.
   */
  Future<JsonObject> getJksKey(final HttpResponse<Buffer> response) {
    Promise<JsonObject> promise = Promise.promise();
    JsonObject openidJsonObject = response.bodyAsJsonObject();

    String jwksUri = openidJsonObject.getString("jwks_uri");
    if (jwksUri == null) {
      promise.fail("No JWKS URI found in the OpenID configuration");
    } else {
      this.client.getAbs(jwksUri)
          .expect(ResponsePredicate.SC_SUCCESS)
          .expect(ResponsePredicate.JSON)
          .send()
          .onSuccess(ar -> promise.complete(ar.bodyAsJsonObject()))
          .onFailure(e -> {
            System.out.printf("Failed to retrieve JWKS key from %s: %s%n", jwksUri, e.getMessage());
            promise.fail(e);
          });
    }
    return promise.future();
  }
}
