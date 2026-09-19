(ns cognitect.aws.jolt.test-runner
  (:require [clojure.test :refer [run-tests]]
            [cognitect.aws.jolt.http-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'cognitect.aws.jolt.http-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
