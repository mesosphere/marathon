(ns jepsen.marathon
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clj-time.format :as time.format]
            [cheshire.core :as json]
            [jepsen.control :as c]
            [jepsen.generator :as gen]
            [jepsen.client :as client]
            [jepsen.db :as db]
            [jepsen.cli :as cli]
            [jepsen.tests :as tests]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.mesos :as mesos]
            [jepsen.zookeeper :as zk]
            [jepsen.util :as util :refer [meh timeout]]))

(def marathon-pidfile "~/marathon/master.pid")
(def marathon-dir     "~/marathon/bin/")
(def marathon-bin     "marathon")
(def marathon-run-log "~/marathon-log-file.log")

(defn install!
  [test node]
  (c/su
   (cu/install-archive! "https://downloads.mesosphere.io/marathon/snapshots/marathon-1.5.0-SNAPSHOT-586-g2a75b8e.tgz" "/home/vagrant/marathon")))

(defn uninstall!
  [test node]
  (info node "Code for uninstalling marathon goes here"))

(defn start-marathon!
  [test node]
  (c/su
   (cu/start-daemon! {:logfile marathon-run-log
                      :make-pidfile? true
                      :pidfile marathon-pidfile
                      :chdir marathon-dir}
                     marathon-bin
                     :--disable_ha
                     :--framework_name          "marathon-dev"
                     :--hostname                 node
                     :--http_address             node
                     :--http_port                "8080"
                     :--https_address            node
                     :--https_port               "8443"
                     :--master                   (str "zk://" node ":2181/mesos"))))

(defn stop-marathon!
  [node]
  (info node "Stopping Marathon framework")
  (meh (c/exec :kill
               :-KILL
               (str "`")
               (str "cat")
               marathon-pidfile
               (str "`")))
  (meh (c/exec :rm :-rf marathon-pidfile)))

(defn ping-marathon!
  [node]
  (http/get (str "http://" node ":8080/ping")))

(defrecord Client [node]
  client/Client
  (setup! [this test node]
    (assoc this :node node))

  (invoke! [this test op]
    (timeout 10000 (assoc op :type :info, :value :timed-out)
             (try
               (case (:f op)
                 :ping-marathon (do (info "Pinging Marathon Framework")
                                    (ping-marathon! node)
                                    (assoc op :type :ok)))
               (catch org.apache.http.ConnectionClosedException e
                 (assoc op :type :fail, :value (.getMessage e)))
               (catch java.net.ConnectException e
                 (assoc op :type :fail, :value (.getMessage e))))))

  (teardown! [_ test]))

(defn ping-marathon
  "Generator for creating new jobs."
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        {:type   :invoke
         :f      :ping-marathon
         :value  {:id     (swap! id inc)}}))))

(defn db
  "Setup and teardown marathon, mesos and zookeeper"
  [mesos-version zookeeper-version]
  (let [[mesos zk] [(mesos/db mesos-version) (zk/db zookeeper-version)]]
    (reify db/DB
      (setup! [_ test node]
        (db/setup! zk test node)
        (info node "starting setting mesos")
        (db/setup! mesos test node)
        (install! test node)
        (start-marathon! test node))
      (teardown! [_ test node]
        (stop-marathon! node)
        (info node "stopping mesos")
        (db/teardown! mesos test node)
        (db/teardown! zk test node)
        (uninstall! test node)))))

(defn marathon-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
   :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name      "marathon"
          :os        debian/os
          :db        (db "1.3.0" "zookeeper-version")
          :client (->Client nil)
          :generator (gen/phases
                      (->> (ping-marathon)
                           (gen/delay 5)
                           (gen/stagger 5)
                           (gen/nemesis
                            (gen/seq (cycle [(gen/sleep 10)
                                             {:type :info, :f :start}
                                             (gen/sleep 10)
                                             {:type :info, :f :stop}])))
                           (gen/time-limit 100))
                      (gen/nemesis (gen/once {:type :info, :f :stop}))
                      (gen/log "Waiting for job executions")
                      (gen/sleep 5))}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
   browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn marathon-test})
            args))
