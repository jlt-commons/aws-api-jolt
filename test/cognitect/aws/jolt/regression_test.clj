(ns cognitect.aws.jolt.regression-test
  "Guards for two Jolt traps that fail far from their cause."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.impl :as impl]
            [cognitect.aws.jolt.provides]
            [cognitect.aws.util.xml :as axml]
            [jolt.xml]))

(deftest jolt-logging-backend-wins
  (testing "jolt-lang/logging, not a Maven tools.logging, supplies the factory"
    ;; A top-level org.clojure/tools.logging coordinate beats jolt-lang/logging
    ;; and breaks nine aws-api namespaces with an error naming none of them.
    (is (= "jolt/stderr" (impl/name (impl/find-factory))))))

(deftest aws-xml-parser-loads-and-parses
  (testing "javax.xml.stream resolves for the Maven-loaded aws-api namespace"
    (is (= {:tag :Root :attrs {} :content [{:tag :Name :attrs {} :content ["b1"]}]}
           (axml/parse "<Root><Name>b1</Name></Root>")))))

(deftest provides-table-matches-what-xml-registers
  (testing "every class we declare is one the shim actually installs"
    ;; Exactly the table deps.edn declares. jolt.xml registers these two and
    ;; nothing else in javax.xml.stream; XMLStreamReader appears only as a type
    ;; hint in aws-api, which jolt never resolves, so declaring it would be a
    ;; claim on a class that does not exist.
    (doseq [c ["javax.xml.stream.XMLInputFactory"
               "javax.xml.stream.XMLStreamConstants"]]
      (is (class? (clojure.lang.RT/classForName c)) (str c " is not registered")))))
