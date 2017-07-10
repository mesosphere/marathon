(ns jepsen.zookeeper
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.cli :as cli]
            [jepsen.tests :as tests]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def zookeeper-bin     "/usr/share/zookeeper/bin/zkServer.sh")
(def zookeeper-lib     "/usr/lib/zookeeper")
(def zookeeper-conf    "/etc/zookeeper/conf/zoo.cfg")
(def zookeeper-myid  "/var/lib/zookeeper/myid")

(defn zk-url
  [test]
  (str/join ","
            (map #(str % ":2181") (:nodes test))))

(defn install!
  [test node version]
  (c/su
   (debian/update!)
   (debian/install-jdk8!)
   (debian/install ["zookeeper"])
   (debian/install ["zookeeper-bin"])
   (debian/install ["zookeeperd"])))

(defn configure!
  [test node]
  (doseq [n (:nodes test)]
    (c/su
     (c/exec
      :echo (str "server." (+ 1 (.indexOf (:nodes test) n)) "=" n ":2888:3888")
      :|
      :tee :-a zookeeper-conf)))
  (c/su
   (c/exec :echo (str  (+ 1 (.indexOf (:nodes test) node)))
           :|
           :tee zookeeper-myid)))

(defn start-zookeeper!
  [test node]
  (info "Starting Zookeeper..")
  (c/su
   (c/exec :service :zookeeper :restart)))

(defn stop-zookeeper!
  [test node]
  (info "Stopping Zookeeper..")
  (c/su
   (c/exec
    :service :zookeeper :stop)))

(defn uninstall!
  [test node version]
  (info node "Uninstalling zookeeper")
  (c/su
   (debian/uninstall! ["zookeeper"])
   (debian/uninstall! ["zookeeper-bin"])))

(defn db
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up zookeeper..")
      (install! test node version)
      (configure! test node)
      (start-zookeeper! test node))
    (teardown! [_ test node]
      (info node "tearing down zookeeper..")
      (stop-zookeeper! test node)
      (uninstall! test node version))))
