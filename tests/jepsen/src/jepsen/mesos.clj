(ns jepsen.mesos
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.cli :as cli]
            [jepsen.tests :as tests]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.util :as util :refer [meh timeout]]))

(def master-pidfile "/var/run/mesos/master.pid")
(def agent-pidfile  "/var/run/mesos/agent.pid")
(def master-dir     "/usr/sbin/")
(def master-bin     "mesos-master")
(def agent-dir      "/usr/sbin/")
(def agent-bin      "mesos-agent")
(def master-log-dir "~/master.log")
(def agent-log-dir  "~/agent.log")

(defn install!
  [test node version]
  (c/su
   (debian/install-jdk8!)
   (debian/add-repo! :mesosphere "deb http://repos.mesosphere.com/ubuntu trusty main" "keyserver.ubuntu.com" "E56151BF")
   (debian/install ["mesos"])
   (c/exec :mkdir :-p "/var/run/mesos")))

(defn configure
  [test node version]
  (c/su
   (c/exec :export "MESOS_NATIVE_JAVA_LIBRARY=/usr/local/lib/libmesos.so")))

(defn uninstall!
  [test node version]
  (info node "Code for uninstalling mesos goes here"))

(defn start-master!
  [test node]
  (c/su
   (cu/start-daemon! {:logfile        master-log-dir
                      :make-pidfile?  true
                      :pidfile        master-pidfile
                      :chdir          master-dir}
                     master-bin
                     (str "--cluster=marathon-dev")
                     (str "--hostname=" node)
                     (str "--ip=" node)
                     (str "--port=5050")
                     (str "--registry=in_memory")
                     (str "--zk=zk://" node ":2181/mesos")
                     (str "--work_dir=\"${data_dir}\""))))

(defn start-agent!
  [test node]
  (c/su
   (cu/start-daemon! {:logfile agent-log-dir
                      :make-pidfile? true
                      :pidfile agent-pidfile
                      :chdir agent-dir}
                     agent-bin
                     (str "--containerizers=mesos")
                     (str "--hostname=" node)
                     (str "--ip=" node)
                     (str "--master=zk://" node ":2181/mesos")
                     (str "--port=5051")
                     (str "--work_dir=\"${data_dir}\""))))

(defn stop-master!
  [node]
  (info node "stopping mesos-master")
  (meh (c/exec :killall :-9 :mesos-master))
  (meh (c/exec :rm :-rf master-pidfile)))

(defn stop-slave!
  [node]
  (info node "stopping mesos-agent")
  (meh (c/exec :killall :-9 :mesos-agent))
  (meh (c/exec :rm :-rf agent-pidfile)))

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
      (stop-slave! node)
      (stop-master! node)
      (uninstall! test node version))))
