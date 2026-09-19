# aws-api-jolt

[cognitect-labs/aws-api](https://github.com/cognitect-labs/aws-api) on
[Jolt](https://jolt-lang.net). aws-api itself is not forked or patched: this
library supplies the two things Jolt needs to run the stock Maven release, an
HTTP client and a host-class declaration.

## Usage

```clojure
;; deps.edn
{:deps {jlt-commons/aws-api-jolt
        {:git/url "https://github.com/jlt-commons/aws-api-jolt"
         :git/sha "<full-sha>"}
        ;; the services you actually call
        com.cognitect.aws/endpoints {:mvn/version "1.1.12.772"}
        com.cognitect.aws/s3        {:mvn/version "868.2.1580.0"}}}
```

```clojure
(require '[cognitect.aws.client.api :as aws])

(def s3 (aws/client {:api :s3 :region "us-east-1"}))
(aws/invoke s3 {:op :ListBuckets})
```

No `:http-client` key is needed. The client is found on the classpath.

## Two things that will bite you

**Never put `org.clojure/tools.logging` at the top level of your deps.**
`jolt-lang/logging` is that namespace. It beats a transitive Maven copy but
loses to a top-level one, and when it loses you get
`Valid logging implementation could not be found` from nine aws-api
namespaces, none of which is the cause.

**aws-api is pinned to 0.8.847 or later**, for two independent reasons.

Releases from 0.8.603 to 0.8.824 call `(.-invoke-async client op-map)`, and
Jolt reads `(.-name obj args)` as a field access where the JVM treats it as a
method call.

Older releases also ship their own `cognitect_aws_http.edn`. 0.8.847 ships
none, which is why ours is unambiguous. Downgrade below it and aws-api finds
two configs on the classpath and refuses to start with "Found more than one
cognitect_aws_http.edn file in the classpath."

## What is verified

All four AWS wire protocols, against real AWS:

| Protocol | Exercised by |
|----------|--------------|
| rest-xml | s3 ListBuckets |
| query | sts GetCallerIdentity |
| json | dynamodb ListTables |
| rest-json | lambda ListFunctions |

SigV4 signing matches the published test vectors, and the resource-only
service-descriptor jars resolve, so the whole AWS catalogue is reachable
rather than a hand-picked subset.

## Not supported

**Streaming.** Bodies are read fully into memory, so a large `GetObject`
holds the whole object at once rather than streaming it. Binary payloads are
carried as raw bytes and are not corrupted, but they are not streamed either.

## Tests

```bash
jolt -M:test              # offline: unit, contract, regression guards
AWS_PROFILE=you jolt -M:live   # live, needs credentials
```

## Requirements

Jolt v0.8.9 or later.
