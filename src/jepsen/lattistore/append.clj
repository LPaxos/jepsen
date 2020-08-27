(ns jepsen.lattistore.append
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [jepsen [client :as client]
                    [util :as u]]
            [jepsen.lattistore [client :as c]]
            [jepsen.tests.cycle.append :as append]
            [slingshot.slingshot :refer [try+]]))

(defn rs [t]
  "Returns a seq containing all keys read by `t`, keys may repeat"
  (->> (:value t)
       (filter (comp #{:r} first))
       (map second)))

(defn ops [t]
  (->> (:value t)
       (filter (comp #{:append} first))
       (map (fn [[f k v]] [k v]))))

(defn apply! [conn t]
  (let [read-res (->> (c/append! conn (ops t) (rs t))
                      (fmap (partial into [])))]
    (->> (:value t)
         (reduce (fn [[st res] [f k v :as op]]
                   (case f
                     :r [st (conj res [f k (get st k)])]
                     :append [(assoc st k (conj (get st k) v)) (conj res op)]))
                 [read-res []])
         second
         (assoc t :type :ok :value))))

(defrecord Client [conn]
  client/Client
  
  (open! [this test node]
    (assoc this :conn (c/client node (:timeout test))))

  (setup! [this test])

  (invoke! [_ test op]
    (c/with-errors op #{}
      (apply! conn op)))

  (teardown! [this test])

  (close! [_ test]
    (c/close! conn)))

(defn workload
  "test linearizable reads, writes, and cas ops on independent keys"
  [opts]
  (assoc (append/test {:key-count 3
                       :max-txn-length 4
                       :consistency-models [:strict-serializable]})
         :client (Client. nil)))
