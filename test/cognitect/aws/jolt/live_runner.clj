(ns cognitect.aws.jolt.live-runner
  "Live AWS calls across all four wire protocols. Opt-in: `jolt -M:live`.
   Profile comes from AWS_PROFILE, defaulting to `default`."
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]))

(def profile (or (System/getenv "AWS_PROFILE") "default"))
(def region  (or (System/getenv "AWS_REGION") "us-east-1"))

(def cases
  [{:api :s3       :protocol "rest-xml"  :op :ListBuckets       :expect :Buckets}
   {:api :sts      :protocol "query"     :op :GetCallerIdentity :expect :Arn}
   {:api :dynamodb :protocol "json"      :op :ListTables        :expect :TableNames}
   {:api :lambda   :protocol "rest-json" :op :ListFunctions     :expect :Functions}])

(defn run-case [{:keys [api protocol op expect]}]
  (let [c (aws/client {:api api :region region
                       :credentials-provider (creds/profile-credentials-provider profile)})
        r (aws/invoke c {:op op})]
    (cond
      (:cognitect.anomalies/category r)
      (do (println (format "  FAIL %-9s %-10s %s -- %s" (name api) protocol (name op)
                           (pr-str (:cognitect.anomalies/message r))))
          false)
      (not (contains? r expect))
      (do (println (format "  FAIL %-9s %-10s %s -- no %s in %s" (name api) protocol
                           (name op) expect (pr-str (vec (keys r)))))
          false)
      :else
      (do (println (format "  ok   %-9s %-10s %s" (name api) protocol (name op))) true))))

(defn -main [& _]
  (println (str "live AWS conformance (profile " profile ", region " region ")"))
  (let [results (mapv run-case cases)
        failed  (count (remove true? results))]
    (println (str "\n" (count results) " cases, " failed " failed"))
    (when (pos? failed) (System/exit 1))))
