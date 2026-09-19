(ns cognitect.aws.jolt.http-test
  (:require [clojure.test :refer [deftest is testing]]
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
    (is (= "hello" (jhttp/->body bb)))
    (testing "a retry re-reads the same bytes"
      (is (= "hello" (jhttp/->body bb))))))

(deftest body-handles-empty-buffer
  (is (= "" (jhttp/->body (ByteBuffer/wrap (byte-array 0))))))

(deftest response-maps-status-and-headers
  (let [r (jhttp/->response {:status 200 :headers {"x" "y"} :body "hi"})]
    (is (= 200 (:status r)))
    (is (= {"x" "y"} (:headers r)))
    (is (= "hi" (jhttp/->body (:body r))))))

(deftest response-with-no-body-has-nil-body
  ;; A 204 goes through the same mapping; ByteBuffer/wrap on nil would throw.
  (is (nil? (:body (jhttp/->response {:status 204 :headers {}})))))
