(ns jepsen.marathon
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.cli :as cli]
            [jepsen.tests :as tests]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.mesos :as mesos]
            [jepsen.zookeeper :as zk]
            [jepsen.util :as util :refer [meh timeout]]))

(def marathon-pidfile "~/marathon/master.pid")

(defn install!
  [test node]
  (c/su
   (cu/install-archive! "https://downloads.mesosphere.io/marathon/snapshots/marathon-1.5.0-SNAPSHOT-586-g2a75b8e.tgz" "/home/vagrant/marathon")))

(defn configure
  [test node]
  (info node "Code for marathon configuration"))

(defn uninstall!
  [test node]
  (info node "Code for uninstalling marathon goes here"))

(defn start-marathon!
  [test node]
  (info node "Starting Marathon framework")
  (c/su
   (c/exec :start-stop-daemon :--start
           :--background
           :--make-pidfile
           :--pidfile        marathon-pidfile
           :--no-close
           :--oknodo
           :--exec           "~/marathon/bin"
           :--
           (str "--disable_ha")
           (str "--framework_name marathon-dev")
           (str "--hostname localhost")
           (str "--http_address 127.0.0.1")
           (str "--http_port 5051")
           (str "--https_address 127.0.0.1")
           (str "--https_port 8443")
           (str "--master zk://localhost:2181/mesos"))))

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
        (configure test node)
        (start-marathon! test node))
      (teardown! [_ test node]
        (info node "stopping mesos")
        (db/teardown! mesos test node)
        (db/teardown! zk test node)
        (stop-marathon! node)
        (uninstall! test node)))))

(defn marathon-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
   :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name "marathon"
          :os debian/os
          :db (db "1.2.0" "zookeeper-version")}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
   browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn marathon-test})
            args))
