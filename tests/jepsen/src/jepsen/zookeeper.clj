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

(def zookeeper-bin "/usr/share/zookeeper/bin/zkServer.sh")
(def zookeeper-lib "/usr/lib/zookeeper")

(defn install!
  [test node version]
  (c/su
   (debian/update!)
   (debian/install-jdk8!)
   (debian/install ["zookeeper"])))

(defn start-zookeeper!
  [test node]
  (info "Starting Zookeeper..")
  (c/su
   (c/exec
    zookeeper-bin :start)))

(defn stop-zookeeper!
  [test node]
  (info "Stopping Zookeeper..")
  (c/su
   (c/exec
    zookeeper-bin :stop)))

(defn uninstall!
  [test node version]
  (c/su
   (debian/uninstall! ["zookeeper"])
   (c/exec :rm :-rf
           (c/lit "/usr/lib/zookeeper"))))

(defn db
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up zookeeper..")
      (install! test node version)
      (start-zookeeper! test node))
    (teardown! [_ test node]
      (info node "tearing down zookeeper..")
      (stop-zookeeper! test node)
      (uninstall! test node version))))
