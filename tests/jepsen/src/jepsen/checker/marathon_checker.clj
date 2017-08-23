(ns jepsen.checker.marathon-checker
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

(defn verify-pods-survival
  [pods-ack pods-survived]
  (info "Acknowledged Pods: " pods-ack)
  (info "Total: " (count pods-ack))

  (info "Existing Pods: " pods-survived)
  (info "Total: " (count pods-survived))

  (info "Pods which got acknowledged but were lost: " (set/difference (set pods-ack) (set pods-survived)))
  {:valid? (every? (set pods-survived) pods-ack)})

(defn verify-groups-survival
  [groups-ack groups-survived]
  (info "Acknowledged Groups: " groups-ack)
  (info "Total: " (count groups-ack))

  (info "Existing Groups: " groups-survived)
  (info "Total: " (count groups-survived))

  (info "Groups which got acknowledged but were lost: " (set/difference (set groups-ack) (set groups-survived)))
  (info "Groups which not acknowledged but survived: " (set/difference (set groups-survived) (set groups-ack)))
  {:valid? (every? (set groups-survived) groups-ack)})

(defn marathon-app-checker
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
        (info apps-ack "Total Acknowledged apps: " (count apps-ack))
        (info apps-survived "Total Survived apps: " (count apps-survived))
        (verify-apps-survival apps-ack apps-survived)))))

(defn marathon-pod-checker
  "Constructs a Jepsen checker."
  []
  (reify checker/Checker
    (check [this test model history opts]
      (let [pods-ack (->> history
                          (filter #(and (= :ok (:type %))
                                        (= :add-pod (:f %))))
                          (map :value))
            pods-survived (->> history
                               (filter #(and (= :ok (:type %))
                                             (= :check-pod-status (:f %))))
                               (map :value))]
        (info pods-ack "Total Acknowledged pods: " (count pods-ack))
        (info pods-survived "Total Survived pods: " (count pods-survived))
        (verify-pods-survival pods-ack pods-survived)))))

(defn marathon-group-checker
  []
  (reify checker/Checker
    (check [this test model history opts]
      (let [groups-ack (->> history
                            (filter #(and (= :ok (:type %))
                                          (= :add-group (:f %))))
                            (map :value))
            groups-survived (->> history
                                 (filter #(and (= :ok (:type %))
                                               (= :check-group-status (:f %))))
                                 (map :value))]
        (info groups-ack "Total Acknowledged groups: " (count groups-ack))
        (info groups-survived "Total Survived groups: " (count groups-survived))
        (verify-groups-survival groups-ack groups-survived)))))
