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
            [jepsen.util :as util :refer [meh timeout]]
            [jepsen.nemesis :as nemesis]))

(def marathon-pidfile "~/marathon/master.pid")
(def marathon-dir     "~/marathon/bin/")
(def marathon-bin     "marathon")
(def marathon-run-log "~/marathon-log-file.log")
(def app-dir          "/tmp/marathon-test/")
(def test-duration    200)

(defn install!
  [test node]
  (c/su
   (cu/install-archive! "https://downloads.mesosphere.io/marathon/snapshots/marathon-1.5.0-SNAPSHOT-586-g2a75b8e.tgz" "/home/vagrant/marathon")
   (c/exec :mkdir :-p app-dir)))

(defn uninstall!
  [test node]
  (c/su
   (c/exec :rm :-rf
           (c/lit "~/marathon")
           (c/lit app-dir))))

(defn start-marathon!
  [test node]
  (c/su
   (cu/start-daemon! {:logfile marathon-run-log
                      :make-pidfile? true
                      :pidfile marathon-pidfile
                      :chdir marathon-dir}
                     marathon-bin
                     :--framework_name          "marathon"
                     :--hostname                 node
                     :--http_address             node
                     :--http_port                "8080"
                     :--https_address            node
                     :--https_port               "8443"
                     :--master                   (str "zk://" (zk/zk-url test) "/mesos")
                     :--zk                       (str "zk://" (zk/zk-url test) "/marathon"))))

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

(defn app-cmd
  [app-id]
  (str "LOG=$(mktemp -p " app-dir "); "
       "echo \"" app-id "\" >> $LOG; "
       "date -u -Ins >> $LOG; "
       "sleep " (* test-duration 10) ";"
       "date -u -Ins >> $LOG;"))

(defn add-app!
  [node app-id]
  (http/post (str "http://" node ":8080/v2/apps")
             {:form-params   {:id    app-id
                              :cmd   (app-cmd app-id)
                              :cpus  0.001
                              :mem   10.0}
              :content-type   :json}))

(defrecord Client [node]
  client/Client
  (setup! [this test node]
    (assoc this :node node))

  (invoke! [this test op]
    (timeout 10000 (assoc op :type :info, :value :timed-out)
             (try
               (case (:f op)
                 :add-app (do (info "Adding app:" (:id (:value op)))
                              (add-app! node (:id (:value op)))
                              (assoc op :type :ok)))
               (catch org.apache.http.ConnectionClosedException e
                 (assoc op :type :fail, :value (.getMessage e)))
               (catch java.net.ConnectException e
                 (assoc op :type :fail, :value (.getMessage e))))))

  (teardown! [_ test]))

(defn add-app
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        {:type   :invoke
         :f      :add-app
         :value  {:id    (str "basic-app-" (swap! id inc))}}))))

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
        (db/teardown! zk test node)
        (info node "stopping mesos")
        (db/teardown! mesos test node)
        (info node "stopping Marathon framework")
        (uninstall! test node)))))

(defn marathon-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
   :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name      "marathon"
          :os        debian/os
          :db        (db "1.3.0" "zookeeper-version")
          :client    (->Client nil)
          :generator (gen/phases
                      (->> (add-app)
                           (gen/stagger 10)
                           (gen/nemesis
                            (gen/seq (cycle [(gen/sleep 10)
                                             {:type :info, :f :start}
                                             (gen/sleep 10)
                                             {:type :info, :f :stop}])))
                           (gen/time-limit test-duration))
                      (gen/nemesis (gen/once {:type :info, :f :stop}))
                      (gen/log "Done generating and launching apps.")
                      (gen/sleep 5))}
         opts))

(defn -main
  "Handles command line arguments. This will run marathon-test"
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn marathon-test})
            args))
