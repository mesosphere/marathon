(ns jepsen.marathon.checker
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            [clojure.set :as set]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer :all]
            [jepsen.checker :as checker]
            [jepsen.util :as util :refer [meh]]
            [jepsen.store :as store]))

(defn verify-apps-survival
  [apps-ack apps-survived]
  (info "Acknowledged Apps: " apps-ack)
  (info "Total: " (count apps-ack))

  (info "Existing Apps: " apps-survived)
  (info "Total: " (count apps-survived))

  (info "Apps which got acknowledged but were lost: " (set/difference (set apps-ack) (set apps-survived)))
  {:valid? (every? (set apps-survived) apps-ack)})

(defn marathon-checker
  "Constructs a Jepsen checker."
  []
  (reify checker/Checker
    (check [this test model history opts]
      (let [apps-ack (->> history
                          (filter #(and (= :ok (:type %))
                                        (= :add-app (:f %))))
                          (map :value))
            apps-survived (->> history
                              (filter #(and (= :ok (:type %))
                                            (= :check-status (:f %))))
                              (map :value))]
        (info apps-ack "Total Acknowledged apps: " (count apps-ack ))
        (info apps-survived "Total Survived apps: " (count apps-survived))
        (verify-apps-survival apps-ack apps-survived)))))
