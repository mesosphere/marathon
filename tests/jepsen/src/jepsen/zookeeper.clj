(ns jepsen.zookeeper
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [jepsen.cli :as cli]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as db]
            [jepsen.os.debian :as debian]
            [jepsen.tests :as tests]))

(def zookeeper-conf    "/etc/zookeeper/conf/zoo.cfg")
(def zookeeper-myid    "/var/lib/zookeeper/myid")
(def zookeeper-log     "/var/log/zookeeper/zookeeper.log")

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
   (c/exec :service :zookeeper :stop)))

(defn uninstall!
  [test node version]
  (info node "Uninstalling zookeeper")
  (c/su
   (debian/uninstall! ["zookeeper"])
   (debian/uninstall! ["zookeeper-bin"])
   (debian/uninstall! ["zookeeperd"])
   (c/exec :rm :-rf
           (c/lit "/tmp/hsperfdata_zookeeper"))))

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
      (uninstall! test node version))
    db/LogFiles
    (log-files [_ test node]
      [zookeeper-log])))
