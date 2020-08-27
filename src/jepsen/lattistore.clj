(ns jepsen.lattistore
  (:require [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :refer [parse-long]]]
            [jepsen.nemesis.combined :as nc]
            [jepsen.ubuntu20 :as ubuntu]
            [jepsen.lattistore [db :as db]
                               [append :as append]
                               [register :as register]]))

(def workloads
  "workload names to workload constructors"
  {:register register/workload
   :register012 register/workload012
   :append append/workload})

(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none []
   :all  [:pause :partition]})

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and returns a collection of keyword
  faults."
  [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))

(defn lattistore-test
  "options map -> test map. Special opts:

    :rate     Approx number of requests per second, per thread
    :workload Name of workload to run
    :timeout  Duration after which client gives up waiting for a response, in millis"
  [opts]
  (info "Test opts\n" (with-out-str (pprint opts)))
  (let [workload-name (:workload opts)
        workload ((workloads workload-name) opts)
        db (db/db)
        nemesis (nc/nemesis-package
                  {:db db
                   :nodes (:nodes opts)
                   :faults (:nemesis opts)
                   :partition {:targets [:majority :majorities-ring]}
                   :pause {:targets [:one]}
                   :interval 5})]
    (merge tests/noop-test
           opts
           {:name (str "lattistore " (name workload-name))
            :pure-generators true
            :checker (checker/compose
                       {:perf (checker/perf {:nemeses (:perf nemesis)})
                        :clock (checker/clock-plot)
                        :stats (checker/stats)
                        :exceptions (checker/unhandled-exceptions)
                        :workload (:checker workload)})
            :os ubuntu/os
            :db db
            :members (:nodes opts)
            :nemesis (:nemesis nemesis)
            :timeout (:timeout opts)
            :client (:client workload)
            :generator (->>
                            (:generator workload)
                            ;(gen/trace "TRACE")
                            (gen/stagger (/ (:rate opts)))
                            (gen/nemesis (:generator nemesis))
                            (gen/time-limit (:time-limit opts))
                            )})))

(defn pos-num [x] (and (number? x) (pos? x)))

(def cli-opts
  "Cmdline options"
  [["-w" "--workload NAME" "What workload?"
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]
   ["-r" "--rate HZ" "Approx number of requests per second, per thread"
    :default 10
    :parse-fn read-string
    :validate [pos-num "Must be a positive number"]]
   [nil "--timeout NUM" "Duration after which client gives up waiting for a response, in millis"
    :default 5000
    :parse-fn parse-long
    :validate [pos-num "Must be a positive number"]]
   [nil "--ops-per-key NUM" "Maximum number of ops on any given key"
    :default 200
    :parse-fn parse-long
    :validate [pos-num "Must be a positive number"]]
   [nil "--nemesis FAULTS" "comma-separated list of nemesis faults to enable"
    :parse-fn parse-nemesis-spec
    :validate [(partial every? #{:pause :partition})
               "Faults must be pause, partition, all, or none"]]])

(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn lattistore-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
