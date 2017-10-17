(ns jepsen.nemesis.marathon-nemesis
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
            [jepsen.db :as db]
            [jepsen.generator :as gen]
            [jepsen.marathon :refer :all]
            [jepsen.client.marathon-client :refer :all]
            [jepsen.mesos :as mesos]
            [jepsen.nemesis :as nemesis]
            [jepsen.os.debian :as debian]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.tests :as tests]
            [jepsen.util :as util :refer [meh timeout]]
            [jepsen.zookeeper :as zk]
            [slingshot.slingshot :as slingshot]))

(defn abdicate-leader
  []
  (reify client/Client
    (setup! [this test node]
      (abdicate-leader))

    (invoke! [this test op]
      (do
        (let [node (rand-nth (:nodes test))]
          (handle-response
           http/delete
           (str "http://" node ":8080/v2/leader")
           nil
           op
           node
           "Current leader abdicated"
           nil))))

    (teardown! [this test])))

(defn random-scale
  [nemesis]
  (reify client/Client
    (setup! [this test node]
      (random-scale (client/setup! nemesis test node)))

    (invoke! [this test op]
      (if (not= :random-scale (:f op))
        (client/invoke! nemesis test op)
        (let [node (rand-nth (:nodes test))
              app-id (rand-nth @apps)]
          (if (< 0.5 (rand))
            (do
              (handle-response
               http/put
               (str "http://" node ":8080/v2/apps/" app-id)
               {:form-params  {:id        app-id
                               :instances 100
                               :cmd       (app-cmd app-id)
                               :cpus      0.001
                               :mem       10.0}
                :query-params {"force" "true"}
                :content-type :json}
               op
               node
               (str "Scaling up: " app-id " to 100 instances")
               nil))
            (do
              (handle-response
               http/put
               (str "http://" node ":8080/v2/apps/" app-id)
               {:form-params  {:id        app-id
                               :instances 1
                               :cmd       (app-cmd app-id)
                               :cpus      0.001
                               :mem       10.0}
                :query-params {"force" "true"}
                :content-type :json}
               op
               node
               (str "Scaling down: " app-id " to 1 instance")
               nil))))))

    (teardown! [this test]
      (client/teardown! nemesis test))))

(defn destroy-app
  [nemesis]
  (reify client/Client
    (setup! [this test node]
      (destroy-app (client/setup! nemesis test node)))

    (invoke! [this test op]
      (if (not= :destroy-app (:f op))
        (client/invoke! nemesis test op)
        (let [node (rand-nth (:nodes test))
              app-id (rand-nth @apps)]
          (handle-response
           http/delete
           (str "http://" node ":8080/v2/apps/" app-id)
           nil
           op
           node
           app-id
           nil))))

    (teardown! [this test]
      (client/teardown! nemesis test))))
