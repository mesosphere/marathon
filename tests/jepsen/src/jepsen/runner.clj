(ns jepsen.runner
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [jepsen.core :as jepsen]
            [jepsen.cli :as cli]
            [jepsen.marathon :as marathon]))

(def tests
  "A map of test names to test constructors."
  {"app-instance-test"       marathon/app-instance-test
   "basic-app-test"          marathon/basic-app-test
   "basic-pod-test"          marathon/basic-pod-test
   "leader-abdication-test"  marathon/leader-abdication-test
   "scale-test"              marathon/scale-test})

(def opt-spec
  [(cli/repeated-opt "-t" "--test NAME" "Test(s) to run" (keys tests) tests)])

(defn test-cmd
  []
  {"test" {:opt-spec (into cli/test-opt-spec opt-spec)
           :opt-fn   (fn [parsed]
                       (-> parsed
                           cli/test-opt-fn
                           (cli/rename-options {:test :test-fns})))
           :run      (fn [{:keys [options]}]
                       (pprint options)
                       (doseq [i (range (:test-count options))
                               test-fn (:test-fns options)]
                         test (-> options
                                  (dissoc :test-fns)
                                  test-fn
                                  jepsen/run!)))}})

(defn -main
  [& args]
  (cli/run! (test-cmd)
            args))
