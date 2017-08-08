(ns jepsen.marathon
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
            [jepsen.marathon.checker :as mchecker]
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
(def apps (atom []))
(def verify-check256sum-download
  (str
   "bash resources/download-checked.sh \\
   https://github.com/timcharper/mcli/archive/v0.2.tar.gz \\
   $(pwd)/v0.2.tar.gz 939b6360a1f5ce93daf654f19c97bc4290227a72ec590b21c5f84fd2165752ba"))

(defn install!
  [test node]
  (c/su
   (info node "Fetching Marathon Snapshot")
   (cu/install-archive! "https://downloads.mesosphere.io/marathon/snapshots/marathon-1.5.0-SNAPSHOT-673-gaadd1c3.tgz" marathon-home)
   (info node "Done fetching Marathon Snapshot")
   (c/exec :mkdir :-p app-dir))
  (dosync
   (info "Verifying checksum and downloading mcli v0.2: " (= 0 (:exit (shell/sh "sh" "-c" verify-check256sum-download))))
   (shell/sh "tar" "-xzf" "v0.2.tar.gz")))

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

(defn store-mcli-logs
  ([test cmd logfile]
   (let [folder (.getCanonicalPath (store/path! test))]
     (info "Retrieving the logs for cmd: " cmd "and storing it as " folder "/" logfile)
     (shell/sh "sh" "-c" (str "mcli-0.2/marathon-cli " cmd ">" folder "/" logfile))))

  ([test cmd logfile fileop]
   (when (= fileop "append")
     (let [folder (.getCanonicalPath (store/path! test))]
       (info "Retrieving the logs for cmd: " cmd "and storing (append mode) it as " folder "/" logfile)
       (shell/sh "sh" "-c" (str "mcli-0.2/marathon-cli " cmd ">>" folder "/" logfile))))))

(defn uninstall!
  [test node]
  (c/su
   (c/exec :rm :-rf
           (c/lit marathon-home)
           (c/lit app-dir))
   (c/exec :rm marathon-service)
   (c/exec :rm marathon-log))
  (shell/sh "rm" "-rf" "mcli-0.2")
  (shell/sh "rm" "v0.2.tar.gz"))

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

(defn add-app
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        (let [app-id (str "basic-app-" (swap! id inc))]
          {:type   :invoke
           :f      :add-app
           :value  {:id    app-id}})))))

(defn add-app!
  [node app-id op]
  (slingshot/try+
   (dosync
    (http/post (str "http://" node ":8080/v2/apps")
               {:form-params   {:id    app-id
                                :instances 5
                                :cmd   (app-cmd app-id)
                                :cpus  0.001
                                :mem   10.0}
                :content-type   :json}
               {:throw-entire-message? true})
    (swap! apps conj app-id)
    (assoc op :type :ok, :value app-id))
   (catch [:status 502] {:keys [body]}
     (assoc op :type :fail, :value "Proxy node failed to respond"))
   (catch [:status 503] {:keys [body]}
     (assoc op :type :fail, :value body))))

(defn update-app
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        (let [app-id (str "basic-app-" (swap! id inc))]
          {:type   :invoke
           :f      :update-app
           :value  {:id    app-id
                    :instances (+ 1 (count (:nodes test)))}})))))

(defn update-app-again
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        (let [app-id (str "basic-app-" (swap! id inc))]
          {:type   :invoke
           :f      :update-app
           :value  {:id    app-id
                    :instances (count (:nodes test))}})))))

(defn update-app!
  [node app-id op test]
  (slingshot/try+
   (http/put (str "http://" node ":8080/v2/apps/" app-id)
             {:form-params   {:id    app-id
                              :instances (:instances (:value op))
                              :cmd   "sleep 1000"
                              :cpus  0.001
                              :mem   10.0
                              :constraints [["hostname", "UNIQUE"]]}
              :content-type   :json}
             {:query-params {"force" "true"}}
             {:throw-entire-message? true})
   (store-mcli-logs test "apps" (str "mcli-apps-update-" (:instances (:value op)) ".log"))
   (store-mcli-logs test "tasks" (str "mcli-tasks-update-" (:instances (:value op)) ".log"))
   (store-mcli-logs test (str "app /" app-id) (str "mcli-app-details-update-" (:instances (:value op)) ".log") "append")
   (assoc op :type :ok, :value app-id)
   (catch [:status 409] {:keys [body]}
     (assoc op :type :fail, :value body))
   (catch [:status 502] {:keys [body]}
     (assoc op :type :fail, :value body))
   (catch [:status 503] {:keys [body]}
     (assoc op :type :fail, :value body))))

(defn check-status!
  [test op app-id]
  (let [node (rand-nth (:nodes test))]
    (slingshot/try+
     (http/get (str "http://" node ":8080/v2/apps/" app-id) {:throw-entire-message? true})
     (assoc op :type :ok, :node node, :value app-id)
     (catch [:status 404] {:keys [body]}
       (assoc op :type :fail, :node node, :value (str "App does not exist: " app-id))))))

(defn add-pod
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        (let [pod-id (str "basic-pod-" (swap! id inc))]
          {:type   :invoke
           :f      :add-pod
           :value  {:id    pod-id}})))))

(defn add-pod!
  [node pod-id op]
  (slingshot/try+
   (http/post (str "http://" node ":8080/v2/pods")
              {:form-params   {:id    (str "/" pod-id)
                               :scaling {:kind "fixed", :instances 1}
                               :containers [{:name (str pod-id "-container")
                                             :exec {:command {:shell "sleep 1000"}}
                                             :resources {:cpus 0.001
                                                         :mem 10.0}}]
                               :networks [{:mode "host"}]}
               :content-type   :json}
              {:throw-entire-message? true})
   (assoc op :type :ok, :value pod-id)
   (catch [:status 422] {:keys [body]}
     (info body)
     (assoc op :type :fail, :value "Invalid object specification"))
   (catch [:status 502] {:keys [body]}
     (assoc op :type :fail, :value "Proxy node failed to respond"))
   (catch [:status 503] {:keys [body]}
     (assoc op :type :fail, :value body))))

(defn check-pod-status!
  [test op pod-id]
  (let [node (rand-nth (:nodes test))]
    (slingshot/try+
     (http/get (str "http://" node ":8080/v2/pods/" pod-id "::status") {:throw-entire-message? true})
     (assoc op :type :ok, :node node, :value pod-id)
     (catch [:status 404] {:keys [body]}
       (assoc op :type :fail, :node node, :value (str "Pod does not exist: " pod-id))))))

(defrecord Client [node]
  client/Client
  (setup! [this test node]
    (assoc this :node node))

  (invoke! [this test op]

    (case (:f op)
      :add-app         (timeout 20000 (assoc op :type :info, :value :timed-out)
                                (try
                                  (do (info "Adding app:" (:id (:value op)))
                                      (add-app! node (:id (:value op)) op))
                                  (catch org.apache.http.ConnectionClosedException e
                                    (assoc op :type :fail, :value (.getMessage e)))
                                  (catch org.apache.http.NoHttpResponseException e
                                    (assoc op :type :fail, :value (.getMessage e)))
                                  (catch java.net.ConnectException e
                                    (assoc op :type :fail, :value (.getMessage e)))))
      :update-app         (timeout 20000 (assoc op :type :info, :value :timed-out)
                                   (try
                                     (do (info "Updating app:" (:id (:value op)))
                                         (update-app! node (:id (:value op)) op test))
                                     (catch org.apache.http.ConnectionClosedException e
                                       (assoc op :type :fail, :value (.getMessage e)))
                                     (catch org.apache.http.NoHttpResponseException e
                                       (assoc op :type :fail, :value (.getMessage e)))
                                     (catch java.net.ConnectException e
                                       (assoc op :type :fail, :value (.getMessage e)))))
      :add-pod          (timeout 20000 (assoc op :type :info, :value :timed-out)
                                 (try
                                   (do (info "Adding pod:" (:id (:value op)))
                                       (add-pod! node (:id (:value op)) op))
                                   (catch org.apache.http.ConnectionClosedException e
                                     (assoc op :type :fail, :value (.getMessage e)))
                                   (catch org.apache.http.NoHttpResponseException e
                                     (assoc op :type :fail, :value (.getMessage e)))
                                   (catch java.net.ConnectException e
                                     (assoc op :type :fail, :value (.getMessage e)))))
      :check-status     (timeout 50000 (assoc op :type :info, :value :timed-out)
                                 (do
                                   (check-status! test op (:id (:value op)))))
      :check-pod-status (timeout 50000 (assoc op :type :info, :value :timed-out)
                                 (do
                                   (check-pod-status! test op (:id (:value op)))))))

  (teardown! [_ test]))

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
        (store-mcli-logs test "apps" "mcli-apps.log")
        (store-mcli-logs test "tasks" "mcli-tasks.log")
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

(defn abdicate-leader
  []
  (reify client/Client
    (setup! [this test node]
      (abdicate-leader))

    (invoke! [this test op]
      (do
        (let [node (rand-nth (:nodes test))]
          (slingshot/try+
           (http/delete (str "http://" node ":8080/v2/leader") {:throw-entire-message? true})
           (assoc op :type :ok, :node node, :value "Current leader abdicated")
           (catch [:status 404] {:keys [body]}
             (assoc op :type :fail, :node node, :value body))
           (catch [:status 502] {:keys [body]}
             (assoc op :type :fail, :value "Proxy node failed to respond"))
           (catch org.apache.http.ConnectionClosedException e
             (assoc op :type :fail, :value (.getMessage e)))
           (catch org.apache.http.NoHttpResponseException e
             (assoc op :type :fail, :value (.getMessage e)))
           (catch java.net.ConnectException e
             (assoc op :type :fail, :value (.getMessage e)))))))

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
          (slingshot/try+
           (if (< 0.5 (rand))
             (do
               (http/put (str "http://" node ":8080/v2/apps/" app-id)
                         {:form-params   {:id    app-id
                                          :instances 100
                                          :cmd   (app-cmd app-id)
                                          :cpus  0.001
                                          :mem   10.0}
                          :content-type   :json}
                         {:query-params {"force" "true"}}
                         {:throw-entire-message? true})
               (assoc op :type :ok, :value (str "Scaling up: " app-id " to 100 instances")))
             (do
               (http/put (str "http://" node ":8080/v2/apps/" app-id)
                         {:form-params   {:id    app-id
                                          :instances 1
                                          :cmd   (app-cmd app-id)
                                          :cpus  0.001
                                          :mem   10.0}
                          :content-type   :json}
                         {:query-params {"force" "true"}}
                         {:throw-entire-message? true})
               (assoc op :type :ok, :value (str "Scaling down: " app-id " to 1 instance"))))
           (catch [:status 409] {:keys [body]}
             (assoc op :type :fail, :value (str app-id ": " body)))
           (catch [:status 502] {:keys [body]}
             (assoc op :type :fail, :value (str app-id ": " body)))
           (catch [:status 503] {:keys [body]}
             (assoc op :type :fail, :value (str app-id ": " body)))
           (catch org.apache.http.ConnectionClosedException e
             (assoc op :type :fail, :value (.getMessage e)))
           (catch org.apache.http.NoHttpResponseException e
             (assoc op :type :fail, :value (.getMessage e)))
           (catch java.net.ConnectException e
             (assoc op :type :fail, :value (.getMessage e)))))))

    (teardown! [this test]
      (client/teardown! nemesis test))))

(defn marathon-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
   :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name      "marathon"
          :os        ubuntu/os
          :db        (db "1.3.0" "zookeeper-version")}
         opts))

(defn basic-app-test
  [opts]
  (marathon-test
   (merge
    {:client    (->Client nil)
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
                  (gen/once (gen/sleep 60))
                  (gen/log "Done generating and launching apps.")))
     :nemesis   (nemesis/partition-random-halves)
     :checker   (checker/compose
                 {:marathon (mchecker/marathon-app-checker)})}
    opts)))

(defn basic-pod-test
  [opts]
  (marathon-test
   (merge
    {:client    (->Client nil)
     :generator (track-check-added-pods
                 (gen/phases
                  (->> (add-pod)
                       (gen/stagger 10)
                       (gen/nemesis
                        (gen/seq (cycle [(gen/sleep 50)
                                         {:type :info, :f :start}
                                         (gen/sleep 20)
                                         {:type :info, :f :stop}])))
                       (gen/time-limit test-duration))
                  (gen/nemesis (gen/once {:type :info, :f :stop}))
                  (gen/once (gen/sleep 60))
                  (gen/log "Done generating and launching apps.")))
     :nemesis   (nemesis/partition-random-halves)
     :checker   (checker/compose
                 {:marathon (mchecker/marathon-pod-checker)})}
    opts)))

(defn app-instance-test
  [opts]
  (marathon-test
   (merge
    {:client    (->Client nil)
     :generator (track-check-added-updated-apps
                 (gen/phases
                  (->> (add-app)
                       (gen/stagger 10)
                       (gen/nemesis
                        (gen/seq (cycle [(gen/sleep 50)
                                         {:type :info, :f :start}
                                         (gen/sleep 20)
                                         {:type :info, :f :stop}])))
                       (gen/time-limit 100))
                  (gen/nemesis (gen/once {:type :info, :f :stop}))
                  (->> (update-app)
                       (gen/stagger 10)
                       (gen/nemesis
                        (gen/seq (cycle [(gen/sleep 50)
                                         {:type :info, :f :start}
                                         (gen/sleep 20)
                                         {:type :info, :f :stop}])))
                       (gen/time-limit 30))
                  (gen/nemesis (gen/once {:type :info, :f :stop}))
                  (->> (update-app-again)
                       (gen/stagger 10)
                       (gen/nemesis
                        (gen/seq (cycle [(gen/sleep 50)
                                         {:type :info, :f :start}
                                         (gen/sleep 20)
                                         {:type :info, :f :stop}])))
                       (gen/time-limit 30))
                  (gen/nemesis (gen/once {:type :info, :f :stop}))
                  (gen/once (gen/sleep 60))
                  (gen/log "Done generating and launching apps.")))
     :nemesis   (nemesis/partition-random-halves)}
    opts)))

(defn leader-abdication-test
  [opts]
  (marathon-test
   (merge
    {:client    (->Client nil)
     :generator (track-check-added-apps
                 (gen/phases
                  (->> (add-app)
                       (gen/stagger 10)
                       (gen/nemesis
                        (gen/seq (cycle [(gen/sleep 20)
                                         {:type :info, :f :abdicate-leader}])))
                       (gen/time-limit 100))
                  (gen/once (gen/sleep 60))
                  (gen/log "Done generating and launching apps.")))
     :nemesis   (abdicate-leader)
     :checker   (checker/compose
                 {:marathon (mchecker/marathon-app-checker)})}
    opts)))

(defn scale-test
  [opts]
  (marathon-test
   (merge
    {:client    (->Client nil)
     :generator (track-check-added-apps
                 (gen/phases
                  (->> (add-app)
                       (gen/stagger 10)
                       (gen/nemesis
                        (gen/seq (cycle [(gen/sleep 25)
                                         {:type :info, :f :random-scale}
                                         (gen/sleep 20)
                                         {:type :info, :f :start}
                                         (gen/sleep 10)
                                         {:type :info, :f :stop}])))
                       (gen/time-limit 300))
                  (gen/nemesis (gen/once {:type :info, :f :stop}))
                  (gen/once (gen/sleep 60))
                  (gen/log "Done generating, launching and scaling apps")))
     :nemesis   (random-scale
                 (nemesis/partition-random-halves))
     :checker   (checker/compose
                 {:marathon (mchecker/marathon-app-checker)})}
    opts)))
