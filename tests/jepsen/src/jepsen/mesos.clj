(ns jepsen.mesos
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clostache.parser :as parser]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.cli :as cli]
            [jepsen.tests :as tests]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.util :as util :refer [meh timeout]]
            [jepsen.zookeeper :as zk]))

(def mesos-data-dir       "/var/lib/mesos/data")
(def mesos-master-config  "/etc/mesos-master")
(def mesos-agent-config   "/etc/mesos-slave")
(def mesos-zookeeper      "/etc/mesos/zk")

(defn calculate_quorum
  [test]
  (+
   1
   (int (Math/floor (/ (count (:nodes test)) 2)))))

(defn install!
  [test node version]
  (c/su
   (debian/add-repo! :mesosphere "deb http://repos.mesosphere.com/ubuntu xenial main" "keyserver.ubuntu.com" "E56151BF")
   (debian/install ["mesos"])
   (c/exec :mkdir :-p "/var/run/mesos")))

(defn configure
  [test node version]
  (c/su
   (c/exec :echo (str "zk://" (zk/zk-url test) "/mesos") :| :tee mesos-zookeeper)
   (c/exec :echo :marathon-dev :| :tee (str mesos-master-config "/cluster"))
   (c/exec :echo node :| :tee (str mesos-master-config "/hostname"))
   (c/exec :echo node :| :tee (str mesos-master-config "/ip"))
   (c/exec :echo :in_memory :| :tee (str mesos-master-config "/registry"))
   (c/exec :echo mesos-data-dir :| :tee (str mesos-master-config "/work_dir"))
   (c/exec :echo (str (calculate_quorum test)) :| :tee (str mesos-master-config "/quorum"))

   (c/exec :echo :mesos :| :tee (str mesos-agent-config "/containerizers"))
   (c/exec :echo node :| :tee (str mesos-agent-config "/hostname"))
   (c/exec :echo node :| :tee (str mesos-agent-config "/ip"))
   (c/exec :echo :5051 :| :tee (str mesos-agent-config "/port"))
   (c/exec :echo mesos-data-dir :| :tee (str mesos-agent-config "/work_dir"))))

(defn uninstall!
  [test node version]
  (info node "Uninstalling Mesos")
  (c/su
   (debian/uninstall! ["mesos"])
   (c/exec :rm :-rf
           (c/lit "var/lib/mesos"))
   (c/exec :rm :-rf
           (c/lit "var/run/mesos"))
   (c/exec :rm :-rf
           (c/lit mesos-master-config))
   (c/exec :rm :-rf
           (c/lit mesos-agent-config))))

(defn start-master!
  [test node]
  (info node "Starting Mesos Master")
  (c/su
   (c/exec
    :systemctl :start :mesos-master.service)))

(defn stop-master!
  [node]
  (info node "Stopping Mesos Master")
  (c/su
   (c/exec
    :systemctl :stop :mesos-master.service)))

(defn start-agent!
  [test node]
  (info node "Starting Mesos Agent")
  (c/su
   (c/exec
    :systemctl :start :mesos-slave.service)))

(defn stop-agent!
  [node]
  (info node "Stopping Mesos Agent")
  (c/su
   (c/exec
    :systemctl :stop :mesos-slave.service)))

(defn db
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up mesos cluster..")
      (install! test node version)
      (configure test node version)
      (start-master! test node)
      (start-agent! test node))
    (teardown! [_ test node]
      (info node "tearing down mesos cluster..")
      (stop-agent! node)
      (stop-master! node)
      (uninstall! test node version))))
