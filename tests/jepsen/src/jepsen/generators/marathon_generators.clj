(ns jepsen.generators.marathon-generators
  (:gen-class)
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.format :as time.format]
            [clj-time.core :as time]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [clostache.parser :as parser]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.store :as store]
            [jepsen.checker :as checker]
            [jepsen.cli :as cli]
            [jepsen.client :as client]
            [jepsen.client.marathon-client :refer :all]
            [jepsen.db :as db]
            [jepsen.generator :as gen]
            [jepsen.mesos :as mesos]
            [jepsen.nemesis :as nemesis]
            [jepsen.os.debian :as debian]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.tests :as tests]
            [jepsen.util :as util :refer [meh timeout]]
            [jepsen.zookeeper :as zk]
            [slingshot.slingshot :as slingshot]))

(defn track-check-added-apps
  [gen]
  (let [apps (ref [])]
    (reify gen/Generator
      (op [_ test process]
        (if-let [op (gen/op gen test process)]
          (dosync
           (when (= :add-app (:f op))
             (alter apps conj (:id (:value op))))
           op)
          (if (not (#{:nemesis} (gen/process->thread test process)))
            (dosync
             (when-let [current-app (peek @apps)]
               (alter apps pop)
               {:type  :invoke
                :f     :check-status
                :value {:id current-app}}))))))))

(defn track-check-added-pods
  [gen]
  (let [pods (ref [])]
    (reify gen/Generator
      (op [_ test process]
        (if-let [op (gen/op gen test process)]
          (dosync
           (when (= :add-pod (:f op))
             (alter pods conj (:id (:value op))))
           op)
          (if (not (#{:nemesis} (gen/process->thread test process)))
            (dosync
             (when-let [current-pod (peek @pods)]
               (alter pods pop)
               {:type  :invoke
                :f     :check-pod-status
                :value {:id current-pod}}))))))))

(defn track-check-added-updated-apps
  [gen]
  (let [apps (ref [])
        updated-apps (ref [])]
    (reify gen/Generator
      (op [_ test process]
        (if-let [op (gen/op gen test process)]
          (dosync
           (when (= :add-app (:f op))
             (alter apps conj (:id (:value op))))
           (when (= :update-app (:f op))
             (if (not (.contains @updated-apps (:id (:value op))))
               (alter updated-apps conj (:id (:value op)))))
           op)
          (if (not (#{:nemesis} (gen/process->thread test process)))
            (dosync
             (if-let [current-app (peek @apps)]
               (do
                 (alter apps pop)
                 {:type  :invoke
                  :f     :check-status
                  :value {:id current-app
                          :operation "app attempted to be deployed - Check status"}})
               (when-let [current-app (peek @updated-apps)]
                 (alter updated-apps pop)
                 {:type  :invoke
                  :f     :check-status
                  :value {:id current-app
                          :operation "app attempted to be updated - Check status"}})))))))))

(defn track-check-added-groups
  [gen]
  (let [groups (ref [])]
    (reify gen/Generator
      (op [_ test process]
        (if-let [op (gen/op gen test process)]
          (dosync
           (when (= :add-group (:f op))
             (alter groups conj (:id (:value op))))
           op)
          (if (not (#{:nemesis} (gen/process->thread test process)))
            (dosync
             (when-let [current-groups (peek @groups)]
               (alter groups pop)
               {:type  :invoke
                :f     :check-group-status
                :value {:id current-groups}}))))))))
