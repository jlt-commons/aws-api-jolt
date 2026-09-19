(ns cognitect.aws.jolt.test-runner
  (:require [clojure.test :refer [run-tests]]
            [cognitect.aws.jolt.http-test]
            [cognitect.aws.jolt.contract-test]
            [cognitect.aws.jolt.regression-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'cognitect.aws.jolt.http-test
                                        'cognitect.aws.jolt.contract-test
                                        'cognitect.aws.jolt.regression-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
