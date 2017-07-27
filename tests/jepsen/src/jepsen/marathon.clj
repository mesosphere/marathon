(ns jepsen.marathon
  (:gen-class)
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.format :as time.format]
            [clj-time.core :as time]
            [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [clostache.parser :as parser]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.checker :as checker]
            [jepsen.cli :as cli]
            [jepsen.client :as client]
            [jepsen.db :as db]
            [jepsen.generator :as gen]
            [jepsen.marathon.checker :refer [marathon-checker]]
            [jepsen.mesos :as mesos]
            [jepsen.nemesis :as nemesis]
            [jepsen.os.debian :as debian]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.tests :as tests]
            [jepsen.util :as util :refer [meh timeout]]
            [jepsen.zookeeper :as zk]
            [slingshot.slingshot :as slingshot]))

(def marathon-home     "/home/ubuntu/marathon")
(def marathon-bin      "marathon")
(def app-dir           "/tmp/marathon-test/")
(def marathon-service  "/lib/systemd/system/marathon.service")
(def marathon-log      "/home/ubuntu/marathon.log")
(def test-duration     200)

(defn install!
  [test node]
  (c/su
   (info node "Fetching Marathon Snapshot")
   (cu/install-archive! "https://downloads.mesosphere.io/marathon/snapshots/marathon-1.5.0-SNAPSHOT-586-g2a75b8e.tgz" marathon-home)
   (info node "Done fetching Marathon Snapshot")
   (c/exec :mkdir :-p app-dir)))

(defn configure
  [test node]
  (c/su
   (c/exec :touch marathon-service)
   (c/exec :echo :-e  (parser/render-resource
                       "services-templates/marathon-service.mustache"
                       {:marathon-home marathon-home
                        :node node
                        :zk-url (zk/zk-url test)
                        :log-file marathon-log})
           :|
           :tee marathon-service)
   (c/exec :systemctl :daemon-reload)))

(defn uninstall!
  [test node]
  (c/su
   (c/exec :rm :-rf
           (c/lit marathon-home)
           (c/lit app-dir))
   (c/exec :rm marathon-service)
   (c/exec :rm marathon-log)))

(defn start-marathon!
  [test node]
  (c/su
   (meh (c/exec
         :systemctl :start :marathon.service))))

(defn stop-marathon!
  [node]
  (info node "Stopping Marathon framework")
  (c/su
   (meh (c/exec
         :systemctl :stop :marathon.service))))

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
  [node app-id op]
  (slingshot/try+
   (http/post (str "http://" node ":8080/v2/apps")
              {:form-params   {:id    app-id
                               :cmd   (app-cmd app-id)
                               :cpus  0.001
                               :mem   10.0}
               :content-type   :json}
              {:throw-entire-message? true})
   (assoc op :type :ok, :value app-id)
   (catch [:status 502] {:keys [body]}
     (assoc op :type :fail, :value "Proxy node failed to respond"))))

(defn check-status!
  [test op app-id]
  (let [node (rand-nth (:nodes test))]
    (slingshot/try+
     (http/get (str "http://" node ":8080/v2/apps/" app-id) {:throw-entire-message? true})
     (assoc op :type :ok, :node node, :value app-id)
     (catch [:status 404] {:keys [body]}
       (assoc op :type :fail, :node node, :value (str "App does not exist: " app-id))))))

(defrecord Client [node]
  client/Client
  (setup! [this test node]
    (assoc this :node node))

  (invoke! [this test op]

    (case (:f op)
      :add-app      (timeout 20000 (assoc op :type :info, :value :timed-out)
                             (try
                               (do (info "Adding app:" (:id (:value op)))
                                   (add-app! node (:id (:value op)) op))
                               (catch org.apache.http.ConnectionClosedException e
                                 (assoc op :type :fail, :value (.getMessage e)))
                               (catch org.apache.http.NoHttpResponseException e
                                 (assoc op :type :fail, :value (.getMessage e)))
                               (catch java.net.ConnectException e
                                 (assoc op :type :fail, :value (.getMessage e)))))
      :check-status (timeout 50000 (assoc op :type :info, :value :timed-out)
                             (do
                               (check-status! test op (:id (:value op)))))))

  (teardown! [_ test]))

(defn add-app
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        (let [app-id (str "basic-app-" (swap! id inc))]
          {:type   :invoke
           :f      :add-app
           :value  {:id    app-id}})))))

(defn db
  "Setup and teardown marathon, mesos and zookeeper"
  [mesos-version zookeeper-version]
  (let [[mesos zk] [(mesos/db mesos-version) (zk/db zookeeper-version)]]
    (reify db/DB
      (setup! [_ test node]
        (db/setup! zk test node)
        (install! test node)
        (configure test node)
        (info node "starting setting mesos")
        (db/setup! mesos test node)
        (start-marathon! test node))
      (teardown! [_ test node]
        (stop-marathon! node)
        (db/teardown! zk test node)
        (info node "stopping mesos")
        (db/teardown! mesos test node)
        (info node "stopping Marathon framework")
        (uninstall! test node))
      db/LogFiles
      (log-files [_ test node]
        (concat (db/log-files zk test node)
                (db/log-files mesos test node)
                [marathon-log])))))

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
              (when-let  [current-app (peek @apps)]
                  (alter apps pop)
                  {:type  :invoke
                   :f     :check-status
                   :value {:id current-app}}))))))))

(defn marathon-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
   :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name      "marathon"
          :os        ubuntu/os
          :db        (db "1.3.0" "zookeeper-version")
          :client    (->Client nil)
          :generator (track-check-added-apps
                      (gen/phases
                       (->> (add-app)
                            (gen/stagger 10)
                            (gen/nemesis
                             (gen/seq (cycle [(gen/sleep 50)
                                              {:type :info, :f :start}
                                              (gen/sleep 20)
                                              {:type :info, :f :stop}])))
                            (gen/time-limit test-duration))
                       (gen/nemesis (gen/once {:type :info, :f :stop}))
                       (gen/log "Done generating and launching apps.")
                       (gen/sleep 60)))
          :nemesis   (nemesis/partition-random-halves)
          :checker   (checker/compose
                      {:marathon (marathon-checker)})}
         opts))

(defn -main
  "Handles command line arguments. This will run marathon-test"
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn marathon-test})
            args))
