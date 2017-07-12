(ns jepsen.os.ubuntu
  (:use clojure.tools.logging)
  (:require [clojure.set :as set]
            [jepsen.util :refer [meh]]
            [jepsen.os :as os]
            [jepsen.os.debian :as debian]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.net :as net]
            [clojure.string :as str]))

(def os
  (reify os/OS
    (setup! [_ test node]
      (info node "setting up ubuntu")

      (debian/setup-hostfile!)

      (debian/maybe-update!)

      (c/su
        ; Packages!
       (debian/install [:wget
                        :curl
                        :vim
                        :man-db
                        :faketime
                        :ntpdate
                        :unzip
                        :iptables
                        :psmisc
                        :tar
                        :bzip2
                        :lbzip2
                        :iputils-ping
                        :iproute
                        :rsyslog
                        :logrotate]))

      (meh (net/heal! (:net test) test)))

    (teardown! [_ test node])))
