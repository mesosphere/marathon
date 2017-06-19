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

(defn install!
  [test node version]
  (c/su
   (debian/install ["zookeeper"])))

(defn configure
  [test node version]
  (info node "Code for configuring zookeeper goes here"))

(defn uninstall!
  [test node version]
  (info node "Code for uninstalling zookeeper goes here"))

(defn db
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up zookeeper..")
      (install! test node version)
      (configure test node version))

    (teardown! [_ test node]
      (info node "tearing down zookeeper..")
      (uninstall! test node version))))
