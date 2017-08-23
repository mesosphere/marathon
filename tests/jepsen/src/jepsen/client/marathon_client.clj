(ns jepsen.client.marathon-client
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
            [jepsen.mesos :as mesos]
            [jepsen.marathon :refer :all]
            [jepsen.marathon-utils :refer :all]
            [jepsen.nemesis :as nemesis]
            [jepsen.os.debian :as debian]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.tests :as tests]
            [jepsen.util :as util :refer [meh timeout]]
            [jepsen.zookeeper :as zk]
            [slingshot.slingshot :as slingshot]))

(def apps (atom []))
(def groups (atom []))
(def app-ids (atom 0))
(def group-ids (atom 0))

(defn handle-response
  "Tries to make a request and throws custom errors based on status
  code. Returns the body of a response."
  [req-fn url options op node expected other]
  (dosync
   (slingshot/try+
    (req-fn url options)
    (doseq [function other]
      (apply (first function) (rest function)))
    (assoc op :type :ok, :value expected)
    (catch [:status 500] {:keys [body]}
      (assoc op :type :fail, :value body))
    (catch [:status 502] {:keys [body]}
      (assoc op :type :fail, :value "Proxy node failed to respond"))
    (catch [:status 503] {:keys [body]}
      (assoc op :type :fail, :value body))
    (catch [:status 422] {:keys [body]}
      (assoc op :type :fail, :value body))
    (catch [:status 401] {:keys [body]}
      (assoc op :type :fail, :node node, :value options))
    (catch [:status 403] {:keys [body]}
      (assoc op :type :fail, :node node, :value body))
    (catch [:status 404] {:keys [body]}
      (assoc op :type :fail, :node node, :value body))
    (catch [:status 409] {:keys [body]}
      (assoc op :type :fail, :node node, :value body))
    (catch org.apache.http.ConnectionClosedException e
      (assoc op :type :fail, :value (.getMessage e)))
    (catch org.apache.http.NoHttpResponseException e
      (assoc op :type :fail, :value (.getMessage e)))
    (catch java.net.ConnectException e
      (assoc op :type :fail, :value (.getMessage e))))))

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

(defn add-group
  []
  (let [id (atom 0)]
    (reify gen/Generator
      (op [_ test process]
        (let [group-id (str "/department-" (swap! id inc))]
          {:type :invoke
           :f    :add-group
           :value {:id group-id}})))))

(defn add-app!
  [node app-id op]
  (handle-response
   http/post
   (str "http://" node ":8080/v2/apps")
   {:form-params  {:id          app-id
                   :instances   5
                   :cmd         (app-cmd app-id)
                   :cpus        0.001
                   :mem         10.0}
    :content-type :json}
   op
   node
   app-id
   [[swap! apps conj app-id]]))

(defn service-app
  [group-id]
  {:id (str group-id "/service-" (swap! app-ids inc))
   :instances 1
   :cmd "sleep 1000"
   :cpus 0.5
   :mem 32.0})

(defn dependency-app
  [group-app-id]
  {:id (str group-app-id "/mesos-docker")
   :container {:docker
               {:image "busybox"}
               :type "MESOS"}
   :cmd "sleep 20 && touch foo && sleep 2000"
   :cpus 0.2
   :mem 16.0
   :instances 1
   :healthChecks [{:protocol "COMMAND",
                   :command {:value "pwd && cat foo"},
                   :gracePeriodSeconds 300,
                   :intervalSeconds 5,
                   :timeoutSeconds 1,
                   :maxConsecutiveFailures 3}]})

(defn group
  [group-id dep-group-id]
  (let [group-apps (atom [])
        id (str group-id "/product-" (swap! group-ids inc))]
    (doseq [app-list (take 1 (repeatedly #(service-app id)))]
      (swap! group-apps conj app-list))
    (do
      {:id id
       :dependencies [dep-group-id]
       :apps @group-apps})))

(defn dependency-group
  [group-id]
  (let [dependency-id (str group-id "/database")]
    (do
      {:id (str group-id "/database")
       :apps [(dependency-app dependency-id)]})))

(defn add-group!
  [node group-id op]
  (dosync
   (let [group (group group-id (str group-id "/database"))]
     (handle-response
      http/post
      (str "http://" node ":8080/v2/groups")
      {:form-params  {:id   group-id
                      :groups [(dependency-group group-id) group]}
       :content-type :json}
      op
      node
      group-id
      [[swap! groups conj group-id]]))))

(defn check-group-status!
  [test op group-id]
  (let [node (rand-nth (:nodes test))]
    (slingshot/try+
     (http/get (str "http://" node ":8080/v2/groups/" group-id) {:throw-entire-message? true})
     (assoc op :type :ok, :node node, :value group-id)
     (catch [:status 404] {:keys [body]}
       (assoc op :type :fail, :node node, :value (str "Group does not exist: " group-id))))))

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
  (handle-response
   http/put
   (str "http://" node ":8080/v2/apps/" app-id)
   {:form-params   {:id    app-id
                    :instances (:instances (:value op))
                    :cmd   "sleep 1000"
                    :cpus  0.001
                    :mem   10.0
                    :constraints [["hostname", "UNIQUE"]]}
    :query-params {"force" "true"}
    :content-type   :json}
   op
   node
   app-id
   [[store-mcli-logs test (str "app /" app-id) (str "mcli-app-details-update-" (:instances (:value op)) ".log") "append"]]))

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

  (handle-response
   http/post
   (str "http://" node ":8080/v2/pods")
   {:form-params   {:id    (str "/" pod-id)
                    :scaling {:kind "fixed", :instances 1}
                    :containers [{:name (str pod-id "-container")
                                  :exec {:command {:shell "sleep 1000"}}
                                  :resources {:cpus 0.001
                                              :mem 10.0}}]
                    :networks [{:mode "host"}]}
    :content-type   :json}
   op
   node
   pod-id
   nil))

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
      :add-app            (timeout 20000 (assoc op :type :info, :value :timed-out)
                                   (do (info "Adding app:" (:id (:value op)))
                                       (add-app! node (:id (:value op)) op)))
      :update-app         (timeout 20000 (assoc op :type :info, :value :timed-out)
                                   (do (info "Updating app:" (:id (:value op)))
                                       (update-app! node (:id (:value op)) op test)))
      :add-pod            (timeout 20000 (assoc op :type :info, :value :timed-out)
                                   (do (info "Adding pod:" (:id (:value op)))
                                       (add-pod! node (:id (:value op)) op)))
      :add-group          (timeout 20000 (assoc op :type :info, :value :timed-out)
                                   (do (info "Adding group:" (:id (:value op)))
                                       (add-group! node (:id (:value op)) op)))
      :check-status       (timeout 50000 (assoc op :type :info, :value :timed-out)
                                   (do
                                     (check-status! test op (:id (:value op)))))
      :check-pod-status   (timeout 50000 (assoc op :type :info, :value :timed-out)
                                   (do
                                     (check-pod-status! test op (:id (:value op)))))
      :check-group-status (timeout 50000 (assoc op :type :info, :value :timed-out)
                                   (do
                                     (check-group-status! test op (:id (:value op)))))))

  (teardown! [_ test]))
