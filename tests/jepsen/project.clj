(defproject jepsen.marathon "0.1.0-SNAPSHOT"
  :description "Jepsen project to automate the testing of Marathon operations in the presence of network-splits."
  :url "https://github.com/mesosphere/marathon"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [jepsen "0.1.6-SNAPSHOT"]
                 [clj-http "3.6.1"]
                 [cheshire "5.7.1"]
                 [clj-time "0.13.0"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [slingshot "0.12.2"]]
  :resource-paths ["resources"]
  :plugins [[lein-cljfmt "0.5.6"]]
  :cljfmt {})
