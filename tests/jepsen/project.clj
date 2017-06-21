(defproject jepsen.marathon "0.1.0-SNAPSHOT"
  :description "Jepsen project to automate the testing of Marathon operations in the presence of network-splits."
  :url "https://github.com/mesosphere/marathon"
  :main jepsen.marathon
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [jepsen "0.1.6-SNAPSHOT"]])
