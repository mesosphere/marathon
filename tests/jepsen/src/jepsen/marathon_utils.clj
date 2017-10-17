(ns jepsen.marathon-utils
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.tools.logging :refer :all]
            [jepsen.store :as store]))

(defn read-nodes-file
  [file]
  (if-let [f file]
    (let [nodes (str/split (slurp f) #"\s*\n\s*")]
      (do nodes))))

(defn store-mcli-logs
  ([test cmd logfile]
   (let [folder (.getCanonicalPath (store/path! test))]
     (info "Retrieving the logs for cmd: " cmd "and storing it as " folder "/" logfile)
     (shell/sh "sh" "-c" (str "mcli-0.2/marathon-cli " cmd ">" folder "/" logfile))))

  ([test cmd logfile fileop]
   (when (= fileop "append")
     (let [folder (.getCanonicalPath (store/path! test))]
       (info "Retrieving the logs for cmd: " cmd "and storing (append mode) it as " folder "/" logfile)
       (shell/sh "sh" "-c" (str "mcli-0.2/marathon-cli " cmd ">>" folder "/" logfile))))))
