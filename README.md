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
         :git/sha "752a0f5d592649cb59a4002698c4959f7b4efe22"}
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

**aws-api is pinned to 0.8.847.** Older releases fail on Jolt for reasons that
differ by version, measured across the releases we tested:

| version | fails because |
|---------|---------------|
| 0.8.612, 0.8.692 | ships its own `cognitect_aws_http.edn`, so discovery finds two configs; also calls `(.-invoke-async …)` |
| 0.8.723 | calls `(.-invoke-async …)`, which Jolt reads as a field access where the JVM treats it as a method call |
| 0.8.762 | no `cognitect/aws/util/xml.clj` at all, so the `:jolt/provides` table here does not match what it imports |
| 0.8.824 | works in our testing, but predates the XML layout this library's provides table is written against |

0.8.847 is what the live suite is run against. Other versions are untested
here, so treat the pin as the supported configuration rather than a floor with
a known-good range below it.

## What is verified

All four AWS wire protocols, against real AWS:

| Protocol | Exercised by |
|----------|--------------|
| rest-xml | s3 ListBuckets |
| query | sts GetCallerIdentity |
| json | dynamodb ListTables |
| rest-json | lambda ListFunctions |

`jolt-lang/crypto`'s SHA-256, HMAC-SHA256 and MD5 match their published test
vectors, and the empty-payload hash aws-api sends is asserted against the
well-known constant. Note what is not checked: no test pins a complete SigV4
signature against an AWS-published vector, because aws-api takes its timestamp
from `(Date.)` with no injection seam and this library does not patch aws-api.
A broken signature would surface in the live suite, not the offline one. The
resource-only
service-descriptor jars resolve, so the whole AWS catalogue is reachable
rather than a hand-picked subset.

## Not supported

**Streaming.** Bodies are read fully into memory, so a large `GetObject`
holds the whole object at once rather than streaming it. Binary payloads are
carried as raw bytes and are not corrupted, but they are not streamed either.

**Redirects.** Not followed, matching aws-api's own client. A 3xx is returned
to aws-api as-is. Following one would replay the SigV4 `Authorization` header,
signed for the original host, at the redirect target.

**Timeouts.** Only honoured when aws-api asks for one via `:timeout-msec`.
There is no default deadline, which matches the reference client. A request
aws-api does not put a timeout on is bounded only by the OS.

## Tests

```bash
jolt -M:test              # offline: unit, contract, regression guards
AWS_PROFILE=you jolt -M:live   # live, needs credentials
```

## Requirements

Jolt v0.8.9 or later.
