(ns cognitect.aws.http.jolt
  "aws-api HttpClient backed by jolt-lang/http-client."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [cognitect.aws.http :as aws]
            [jolt.http-client :as http])
  (:import [java.nio ByteBuffer]))

(def ^:private default-ports {:http 80 :https 443})

(defn ->url
  "Assemble an aws-api request map into a URL string. A default port for the
   scheme is omitted: SigV4 signs the host header, so a redundant :443 risks
   disagreeing with the header the signature covered."
  [{:keys [scheme server-name server-port uri query-string]}]
  ;; (name scheme) so a string scheme works too, as aws-api's own
  ;; http/uri-authority does; a keyword lookup alone would miss "https" and
  ;; leave a redundant :443 in the URL.
  (let [scheme (keyword (name (or scheme :https)))]
    (str (name scheme) "://" server-name
         (when (and server-port (not= server-port (default-ports scheme)))
           (str ":" server-port))
         ;; aws-api signs "/" for an empty path (signers.clj), so an empty
         ;; :uri must normalise the same way or the signature will not match.
         (if (str/blank? uri) "/" uri)
         (when (seq query-string) (str "?" query-string)))))

(defn ->body
  "aws-api hands bodies over as a ByteBuffer. Read it into a byte array without
   consuming the caller's buffer, so a retry sees the same bytes.

   Bytes, never a String. A String round-trip replaces every byte that is not
   valid UTF-8 with U+FFFD and cannot be undone: a 5430-byte binary body
   measured 12684 bytes after one such round-trip, so S3 GetObject on any
   binary object would return garbage."
  [body]
  (cond
    (nil? body) nil
    (instance? ByteBuffer body)
    (let [bb (.duplicate ^ByteBuffer body)
          ba (byte-array (.remaining bb))]
      (.get bb ba)
      ba)
    (string? body) (.getBytes ^String body "UTF-8")
    :else body))

(defn ->response
  "jolt-lang/http-client response -> the aws-api response map."
  [resp]
  {:status  (:status resp)
   :headers (:headers resp)
   :body    (when-let [b (:body resp)]
              (ByteBuffer/wrap (if (string? b)
                                 (.getBytes ^String b "UTF-8")
                                 ^bytes b)))})

(defn- send! [request channel]
  (try
    (let [timeout (:timeout-msec request)]
      (a/put! channel (->response
                       (http/request
                        (cond-> {:url     (->url request)
                                 :method  (or (:request-method request) :get)
                                 :headers (:headers request)
                                 :body    (->body (:body request))
                                 ;; raw bytes both ways; see ->body
                                 :as      :byte-array
                                 ;; aws-api's own client builds with
                                 ;; HttpClient$Redirect/NEVER. Following a
                                 ;; redirect here would replay the SigV4
                                 ;; Authorization header, signed for the
                                 ;; original host, at the new host, and
                                 ;; clj-http-lite's redirect loop has no depth
                                 ;; limit.
                                 :follow-redirects false
                                 :throw-exceptions false}
                          ;; aws-api asks for a deadline where it needs one --
                          ;; :timeout-msec 1000 on every IMDS call, so the
                          ;; credential chain fails fast on a machine with no
                          ;; instance profile. Honour it when positive, and
                          ;; invent no default, exactly as the reference
                          ;; client does.
                          (and timeout (pos? timeout))
                          (assoc :socket-timeout timeout
                                 :conn-timeout timeout))))))
    (catch Throwable t
      ;; aws-api's retry layer reads anomalies off the channel; an exception
      ;; thrown on this thread would never reach it, and invoke would hang.
      ;; :cognitect.aws/throwable is the key aws-api's own client uses.
      (a/put! channel {:cognitect.anomalies/category :cognitect.anomalies/fault
                       :cognitect.anomalies/message  (.getMessage t)
                       :cognitect.aws/throwable      t})))
  channel)

(defn create
  "Construct an aws-api HttpClient. Named by resources/cognitect_aws_http.edn,
   so aws-api finds it with no :http-client key."
  []
  (reify aws/HttpClient
    (-submit [_ request channel]
      ;; jolt-lang/http-client blocks; -submit must return at once.
      (a/thread (send! request channel))
      channel)
    (-stop [_] nil)))
