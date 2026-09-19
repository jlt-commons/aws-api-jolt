(ns cognitect.aws.http.jolt
  "aws-api HttpClient backed by jolt-lang/http-client."
  (:require [clojure.core.async :as a]
            [cognitect.aws.http :as aws]
            [jolt.http-client :as http])
  (:import [java.nio ByteBuffer]))

(def ^:private default-ports {:http 80 :https 443})

(defn ->url
  "Assemble an aws-api request map into a URL string. A default port for the
   scheme is omitted: SigV4 signs the host header, so a redundant :443 risks
   disagreeing with the header the signature covered."
  [{:keys [scheme server-name server-port uri query-string]}]
  (let [scheme (or scheme :https)]
    (str (name scheme) "://" server-name
         (when (and server-port (not= server-port (default-ports scheme)))
           (str ":" server-port))
         (or uri "/")
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
    (a/put! channel (->response
                     (http/request {:url     (->url request)
                                    :method  (or (:request-method request) :get)
                                    :headers (:headers request)
                                    :body    (->body (:body request))
                                    ;; raw bytes both ways; see ->body
                                    :as      :byte-array
                                    :throw-exceptions false})))
    (catch Throwable t
      ;; aws-api's retry layer reads anomalies off the channel; an exception
      ;; thrown on this thread would never reach it, and invoke would hang.
      (a/put! channel {:cognitect.anomalies/category :cognitect.anomalies/fault
                       :cognitect.anomalies/message  (str (.getMessage t))
                       ::throwable t})))
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
