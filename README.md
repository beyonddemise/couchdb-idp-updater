# CouchDB Identity Provider updater

This container provides the link between CouchDB's [JWT AUthentication](https://docs.couchdb.org/en/stable/api/server/authn.html#jwt-authentication) and the identity provider's advertisement of their public keys under `/.well-known/openid-configuration`

This project uses Quarkus, the Supersonic Subatomic Java Framework. If you want to learn more about Quarkus, please visit [its website](https://quarkus.io/).

## Prerequisites (for development)

- current Java
- Apache maven installed (and on the path)
- Postman when you want to use the postman collection
- mapped file `data/config.json`

## config.json

The `config.json` contains a list of IdPs (minimum: one) and CouchDB servers (minimum: one).
All nodes of each server gets all the public keys of each IdP. the file needs to be mapped
to `/work/data/config.json`

```json
{
  "UpdateIntervalSeconds": 21600,
  "IdPs": ["http://localhost:8090/realms/foundation"],
  "CouchDBservers": ["http://localhost:5984"]
}
```

| Field                 | Default | Purpose                                                      |
| --------------------- | ------- | ------------------------------------------------------------ |
| UpdateIntervalSeconds | 21600   | How often to re-read the jwks (= 6h)                         |
| IdPs                  | `[]`    | List of URLs pointing to `/.well-known/openid-configuration` |
| CouchDBservers        | `[]`    | List of CouchDB servers                                      |

Typically the `/.well-known/openid-configuration` can be found on the root of a server, so the entry
would be e.g `https://apps.acme.com` (No trailing slash). A notable difference is [Keycloak](https://www.keycloak.org/),
which can host multiple Idp, so you need the URL to include `/realms/realmname` (no trailing slash)

Credentials are retrieved using `.env`

| Variable         | Example  | Purpose                         |
| ---------------- | -------- | ------------------------------- |
| COUCHDB_USER     | admin    | User with administrative access |
| COUCHDB_PASSWORD | password | should be a better one          |

## Running the application in dev mode

That's what you want since it allows hot reload (a.k.a live coding):

```bash
mvn compile quarkus:dev
```

You now can interact with the application on port `8080`

- `/` -> Status page
- `http://localhost:8080/q/dev/` -> Quarkus dev UI
- any route defined in openapidemo.yaml

## Packaging and running

Follow the Quarkus [documentation](https://quarkus.io/)

the application gets delivered a native image, there's a github action creating
the `ghcr.io/beyonddemise/couchdb-idp-updater` container image.
For local creation you can use `makedockerlocal.sh`

To run the configuration you need to map the directory containing `config.json` to `/work/data`

## Sample docker-compose.yml

```yml
version: '3.8'

services:
  idpupdate:
    container_name: idpupdate
    image: ghcr.io/beyonddemise/couchdb-idp-updater:latest
    environment:
      COUCHDB_USER: admin
      COUCHDB_PASSWORD: password
    volumes:
      - ./data:/work/data
    ports:
      - 8080 :8080
    restart: unless-stopped
    depends_on:
      - couchdb

  couchdb:
    image: couchdb:latest
    container_name: couchdb
    environment:
      COUCHDB_USER: admin
      COUCHDB_PASSWORD: password
    ports:
      - 5983: 5983
    volumes:
      - type: volume
        source: couchdb_etc
        target: /opt/couchdb/etc

      - type: volume
        source: couchdb_data
        target: /opt/couchdb/data

      - type: volume
        source: couchdb_logs
        target: /opt/couchdb/logs

    restart: unless-stopped

volumes:
  couchdb_data:
  couchdb_etc:
  couchdb_logs:
```

## Devcontainer

TODO: Create

## Postman

TODO: In `src/main/postman` there's a postman collection used to test endpoints

## Running the container

You can use the sample `docker-compose.yml` file as a strting point. For "just testing it out", use these commands:

- Run a temporary instance of CouchDB ( you don't want to mess with your production system initially):

```bash
#!/bin/bash
# Run a disposable CouchDB
docker run --rm --name testcouch -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password -p 5984:5984 apache/couchdb:latest
```

- create the directory `data` and the `config.json` in it.

- run the container

```bash
docker run --rm --name couchidp -v $(pwd)/data:/work/data -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password -p 8080:8080 ghcr.io/beyonddemise/couchdb-idp-updater:latest
```

## TODO

- add log levels
- web UI with status
- more error handling
- rework node booting

## Feedback

Use the [Github issues](https://github.com/beyonddemise/couchdb-idp-updater/issues) or [Slack](https://couchdb.slack.com/)
