## locurlfwd

Local URL Forwarding.

Built on:

Language: [Kotlin](https://kotlinlang.org/)

Virtual Machine: [JVM](https://jdk.java.net/15/)

Server: [Jooby](https://jooby.io/)

CLI: [kotlinx-cli](https://github.com/Kotlin/kotlinx-cli)

### Goals

Easy to use, easy to distribute url forwarding for local development without worrying about CORS. 

A quick jvm app doesn't need to be compiled for different environments, 
nor does it require users to compile it themselves. There's no constantly running background daemon.

### Requirements

JDK or JRE 15 or higher.

(Should run on earlier versions down to 11, but built targeting and tested against 15).

### Usage

Pass in a source URL, followed by at least one destination URL.
The first destination URL is the default - additional destination URLs must be paired with a root path to match on.
(For example, if the root path of a request is "/api", send it to destination "api", otherwise default).

```shell
java -jar locurlfwd.jar <port> -d <root destination>
java -jar locurlfwd.jar <port> -d <root destination> -d <root path a>=<destination a> -d <root path b>=<destination b>
java -jar locurlfwd.jar <port> -d <root destination> -d <root path a>*<destination a> -d <root path b>^<destination b>
```

General minimal example, send localhost on port 8080 to a remote server:

```shell
java -jar locurlfwd.jar 8080 -d https://subdomain.domain.tld
```

A typical setup for a decoupled backend and frontend running locally:

Pass through `=` version:

Send localhost on port 8080 to the frontend running off port 3000, and anything on the path "/api" to port 5000 on the path "/api".

```shell
java -jar locurlfwd.jar 8080 -d http://localhost:3000 -d api=http://localhost:5000
```

Pass over `^` version:

Send localhost on port 8080 to the frontend running off port 3000, and anything on the path "/api" to port 5000, but remove "/api" from the path.

```shell
java -jar locurlfwd.jar 8080 -d http://localhost:3000 -d api^http://localhost:5000
```

Supports `DELETE`, `GET`, `HEAD`, `OPTIONS`, `PATCH`, `POST`, `PUT`.
Content Encoding Support includes Brotli (BR) and GZIP.

### Limitations

* Does not validate urls - use carefully.