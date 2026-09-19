(ns cognitect.aws.jolt.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async]
            [cognitect.aws.http]
            [cognitect.aws.http.jolt :as jhttp])
  (:import [java.nio ByteBuffer]))

(deftest url-uses-https-by-default
  (is (= "https://s3.amazonaws.com/"
         (jhttp/->url {:server-name "s3.amazonaws.com" :uri "/"}))))

(deftest url-omits-default-ports
  (testing "443 on https and 80 on http are left out"
    (is (= "https://s3.amazonaws.com/"
           (jhttp/->url {:scheme :https :server-name "s3.amazonaws.com"
                         :server-port 443 :uri "/"})))
    (is (= "http://localhost/"
           (jhttp/->url {:scheme :http :server-name "localhost"
                         :server-port 80 :uri "/"})))))

(deftest url-keeps-non-default-port
  ;; LocalStack and MinIO live here; dropping the port sends traffic elsewhere.
  (is (= "http://localhost:4566/x"
         (jhttp/->url {:scheme :http :server-name "localhost"
                       :server-port 4566 :uri "/x"}))))

(deftest url-appends-query-string
  (is (= "https://h/?a=1&b=2"
         (jhttp/->url {:server-name "h" :uri "/" :query-string "a=1&b=2"}))))

(deftest url-omits-empty-query-string
  (is (= "https://h/" (jhttp/->url {:server-name "h" :uri "/" :query-string ""}))))

(deftest body-nil-stays-nil
  (is (nil? (jhttp/->body nil))))

(deftest body-reads-bytebuffer-without-consuming-it
  (let [bb (ByteBuffer/wrap (.getBytes "hello" "UTF-8"))]
    (is (= (vec (.getBytes "hello" "UTF-8")) (vec (jhttp/->body bb))))
    (testing "a retry re-reads the same bytes"
      (is (= (vec (.getBytes "hello" "UTF-8")) (vec (jhttp/->body bb)))))))

(deftest body-handles-empty-buffer
  (is (= [] (vec (jhttp/->body (ByteBuffer/wrap (byte-array 0)))))))

(deftest body-preserves-bytes-that-are-not-valid-utf8
  (testing "a String round-trip would replace these with U+FFFD, irrecoverably"
    (let [raw (byte-array [(unchecked-byte 0x89) (unchecked-byte 0x50)
                           (unchecked-byte 0xFF) (unchecked-byte 0xFE)])]
      (is (= (vec raw) (vec (jhttp/->body (ByteBuffer/wrap raw))))))))

(deftest response-maps-status-and-headers
  (let [r (jhttp/->response {:status 200 :headers {"x" "y"} :body "hi"})]
    (is (= 200 (:status r)))
    (is (= {"x" "y"} (:headers r)))
    (is (= (vec (.getBytes "hi" "UTF-8")) (vec (jhttp/->body (:body r)))))))

(deftest response-with-no-body-has-nil-body
  ;; A 204 goes through the same mapping; ByteBuffer/wrap on nil would throw.
  (is (nil? (:body (jhttp/->response {:status 204 :headers {}})))))

(deftest transport-failure-arrives-as-an-anomaly
  (testing "a connection that cannot be made yields a fault on the channel,
            never a throw, because -submit runs on another thread"
    (let [client (jhttp/create)
          ch     (clojure.core.async/chan 1)
          ;; port 1 on localhost: refused fast, no DNS, no network wait
          _      (cognitect.aws.http/-submit
                  client
                  {:scheme :http :server-name "127.0.0.1" :server-port 1
                   :uri "/" :request-method :get :headers {}}
                  ch)
          result (clojure.core.async/<!! ch)]
      (is (= :cognitect.anomalies/fault
             (:cognitect.anomalies/category result)))
      (is (string? (:cognitect.anomalies/message result))))))
