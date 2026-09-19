(ns cognitect.aws.jolt.contract-test
  "The full aws-api invoke path against a stub transport: no network, no
   credentials, deterministic."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [cognitect.aws.http :as http]
            [cognitect.aws.jolt.provides])
  (:import [java.nio ByteBuffer]))

(def list-buckets-xml
  (str "<ListAllMyBucketsResult><Buckets>"
       "<Bucket><Name>b1</Name><CreationDate>2020-01-02T03:04:05.000Z</CreationDate></Bucket>"
       "</Buckets><Owner><ID>oid</ID></Owner></ListAllMyBucketsResult>"))

(defn stub
  "An HttpClient that records the request and replays a canned response."
  [captured body]
  (reify http/HttpClient
    (-submit [_ request channel]
      (reset! captured request)
      (a/put! channel {:status 200
                       :headers {"content-type" "application/xml"}
                       :body (ByteBuffer/wrap (.getBytes ^String body "UTF-8"))})
      channel)
    (-stop [_] nil)))

(defn s3-client [captured]
  (aws/client {:api :s3
               :region "us-east-1"
               :http-client (stub captured list-buckets-xml)
               :credentials-provider
               (creds/basic-credentials-provider
                {:access-key-id     "AKIAIOSFODNN7EXAMPLE"
                 :secret-access-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"})}))

(deftest signs-with-sigv4
  (let [captured (atom nil)
        _        (aws/invoke (s3-client captured) {:op :ListBuckets})
        auth     (get-in @captured [:headers "authorization"])]
    (testing "an AWS4-HMAC-SHA256 credential scope for the right region and service"
      (is (str/starts-with? auth "AWS4-HMAC-SHA256 "))
      (is (str/includes? auth "Credential=AKIAIOSFODNN7EXAMPLE/"))
      (is (str/includes? auth "/us-east-1/s3/aws4_request")))
    (testing "signed headers and a signature are present"
      (is (str/includes? auth "SignedHeaders="))
      (is (re-find #"Signature=[0-9a-f]{64}" auth)))))

(deftest sends-payload-hash-and-date
  (let [captured (atom nil)
        _        (aws/invoke (s3-client captured) {:op :ListBuckets})
        headers  (:headers @captured)]
    (testing "an empty GET body hashes to the well-known empty SHA-256"
      (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
             (get headers "x-amz-content-sha256"))))
    (is (re-matches #"\d{8}T\d{6}Z" (get headers "x-amz-date")))))

(deftest targets-the-resolved-s3-endpoint
  (let [captured (atom nil)]
    (aws/invoke (s3-client captured) {:op :ListBuckets})
    (is (= :get (:request-method @captured)))
    (is (= "s3.amazonaws.com" (:server-name @captured)))))

(deftest parses-the-xml-response
  (let [captured (atom nil)
        r        (aws/invoke (s3-client captured) {:op :ListBuckets})]
    (is (= ["b1"] (mapv :Name (:Buckets r))))
    (is (= "oid" (get-in r [:Owner :ID])))
    (testing "CreationDate comes back as a real Date, not a string"
      (is (instance? java.util.Date (:CreationDate (first (:Buckets r))))))))

(deftest auto-discovers-the-client-from-the-edn-resource
  (testing "no :http-client key needed; cognitect_aws_http.edn names ours"
    (let [c (aws/client {:api :s3 :region "us-east-1"
                         :credentials-provider
                         (creds/basic-credentials-provider
                          {:access-key-id "AKIA" :secret-access-key "s"})})]
      (is (some? c))
      (is (pos? (count (aws/ops c)))))))
