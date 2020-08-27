(ns jepsen.lattistore.register
  (:require [jepsen [client :as client]
                    [checker :as checker]
                    [generator :as gen]
                    [independent :as independent]
                    [util :as u]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.lattistore [client :as c]]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]))

(defn ->str [x] (str x))
(defn str-> [x]
  (if (= x "") 0 (u/parse-long x)))

(defrecord Client [conn]
  client/Client
  
  (open! [this test node]
    (assoc this :conn (c/client node (:timeout test))))

  (setup! [this test])

  (invoke! [_ test op]
    (let [[k v] (:value op)]
      (c/with-errors op #{:read}
        (case (:f op)
          :read (let [r (str-> (c/get! conn (->str k)))]
                  (assoc op :type :ok, :value (independent/tuple k r)))
          :write (do (c/put! conn (->str k) (->str v))
                     (assoc op :type :ok))
          :cas (let [[old new] v
                     r (c/cas! conn (->str k) (->str old) (->str new))]
                 (assoc op :type (if r :ok :fail)))))))

  (teardown! [this test])

  (close! [_ test]
    (c/close! conn)))

(defn r [_ _] {:type :invoke, :f :read, :value nil})
(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn workload
  "test linearizable reads, writes, and cas ops on independent keys"
  [opts]
  {:client (Client. nil)
   :checker (independent/checker
              (checker/compose
                {:linear (checker/linearizable
                            {:model (model/cas-register 0)})
                 :timeline (timeline/html)}))
   :generator (independent/concurrent-generator
                10
                (range)
                (fn [k]
                  (->> (gen/mix [r w cas])
                       ;(gen/reserve 5 r)
                       (gen/limit (:ops-per-key opts)))))})

(defrecord Client0 [conn]
  client/Client

  (open! [this test node]
    (assoc this :conn (c/client node (:timeout test))))

  (setup! [this test])

  (invoke! [_ test op]
    (let [v (:value op)]
      (c/with-errors op #{:read}
        (case (:f op)
          :read (let [r (c/get! conn (->str 0))] (assoc op :type :ok, :value r))
          :write (do (c/put! conn (->str 0) (->str v)) (assoc op :type :ok))
          :cas (let [[old new] v
                     r (c/cas! conn (->str 0) (->str old) (->str new))]
                 (assoc op :type (if r :ok :fail)))))))

  (teardown! [this test])

  (close! [_ test]
    (c/close! conn)))

(defn w0 [_ _] {:type :invoke, :f :write, :value 0})
(defn cas01 [_ _] {:type :invoke, :f :cas, :value [0 1]})
(defn cas02 [_ _] {:type :invoke, :f :cas, :value [0 2]})

(defn workload012
  [opts]
  {:client (Client0. nil)
   :checker (checker/compose
              {:linear (checker/linearizable {:model (model/cas-register 0)})
               :timeline (timeline/html)})
   :generator (->> (gen/mix [w0 cas01 cas02])
                   (gen/limit (:ops-per-key opts)))})
