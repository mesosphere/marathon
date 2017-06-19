(ns jepsen.mesos
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
   (debian/install-jdk8!)
   (debian/add-repo! :mesosphere "deb http://repos.mesosphere.com/ubuntu trusty main" "keyserver.ubuntu.com" "E56151BF")
   (debian/install ["mesos"])))

(defn configure
  [test node version]
  (c/su
   (c/exec :export "MESOS_NATIVE_JAVA_LIBRARY=/usr/local/lib/libmesos.so")))

(defn uninstall!
  [test node version]
  (info node "Code for uninstalling mesos goes here"))

(defn db
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up mesos cluster..")
      (install! test node version)
      (configure test node version))
    (teardown! [_ test node]
      (info node "tearing down mesos cluster..")
      (uninstall! test node version))))
