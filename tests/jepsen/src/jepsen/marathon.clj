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
            [jepsen.marathon-utils :refer :all]
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
(def verify-check256sum-download
  (str
   "bash resources/download-checked.sh \\
   https://github.com/timcharper/mcli/archive/v0.2.tar.gz \\
   $(pwd)/v0.2.tar.gz 939b6360a1f5ce93daf654f19c97bc4290227a72ec590b21c5f84fd2165752ba"))

(defn install!
  [test node]
  (c/su
   (info node "Fetching Marathon Snapshot")
   (cu/install-archive!
    "https://s3.amazonaws.com/downloads.mesosphere.io/marathon/snapshots/marathon-1.5.0-SNAPSHOT-713-g14280a6.tgz"
    marathon-home)
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