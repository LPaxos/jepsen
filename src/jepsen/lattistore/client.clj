(ns jepsen.lattistore.client
  "Client wrapper"
  (:require [clojure.tools.logging :refer [info]]
            [clojure.string :refer [split join]]
            [jepsen.lattistore.support :as s]
            [jepsen.util :as u]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (java.lang AutoCloseable)
           (client Client
                   Client$RedirectException
                   Client$NodesUnreachableException
                   Client$LostLeadershipException
                   Client$ClosingException
                   Client$TimeoutException
                   Client$TooManyRequestsException
                   Client$CompileErrorException
                   Client$BadResponseFormatException)
           (io.grpc Status$Code
                    StatusRuntimeException)))

(defn ^Client client
  [node timeout]
  (Client. (s/client-url node) timeout))

(defn close!
  [^AutoCloseable c]
  (.close c))

(defn original-cause
  "Unwrap throwable to return its original cause"
  [^Throwable t]
  (if-let [cause (.getCause t)]
    (recur cause)
    t))

(defmacro unwrap-exceptions
  "Unwrap exceptions wrapped by GRPC into ExecutionExceptions"
  [& body]
  `(try ~@body
        (catch java.util.concurrent.ExecutionException e#
          (throw (original-cause e#)))))

(defmacro remap-errors
  "Convert (some) errors to Slingshot maps with fields:
  
    :type
    :description
    :definite? Is this error definitely a failure, or is it unknown?"
  [& body]
  `(try+ (unwrap-exceptions ~@body)
        (catch StatusRuntimeException e#
          (throw+
            (let [status# (.getStatus e#)
                  desc# (.getDescription status#)]
              (condp = (.getCode status#)
                Status$Code/UNAVAILABLE
                {:definite? false, :type :unavailable, :description desc#}

                Status$Code/UNKNOWN
                (do (info "code=UNKNOWN, description" (pr-str desc#))
                    e#)

                (do (info "Unknown error status code" (.getCode status#)
                          "-" status# "-" e#)
                    e#)))))

        (catch Client$RedirectException e#
          (throw+ {:definite? true, :type :redirect, :description (.getMessage e#)}))

        (catch Client$NodesUnreachableException e#
          (throw+ {:definite? false, :type :nodes-unreachable, :description (.getMessage e#)}))

        (catch Client$LostLeadershipException e#
          (throw+ {:definite? (not (.couldHandle e#)), :type :lost-leadership, :description (.getMessage e#)}))

        (catch Client$ClosingException e#
          (throw+ {:definite? false, :type :closing, :description (.getMessage e#)}))

        (catch Client$TimeoutException e#
          (throw+ {:definite? false, :type :timeout, :description (.getMessage e#)}))

        (catch Client$TooManyRequestsException e#
          (throw+ {:definite? true, :type :too-many-requests, :description (.getMessage e#)}))

        (catch Client$CompileErrorException e#
          (throw+ {:definite? true, :type :compile-error, :description (.error e#)}))

        (catch Client$BadResponseFormatException e#
          (throw+ {:definite? false, :type :bad-response-format, :description (.getMessage e#)}))

        (catch java.net.ConnectException e#
          (throw+ {:definite? true
                   :type :connect-timeout
                   :description (.getMessage e#)}))

        (catch java.io.IOException e#
          (throw+
            (condp re-find (.getMessage e#)
              #"Connection reset by peer"
              {:definite? false, :type :connection-reset}
              e#)))))

(defn client-error?
  [m]
  (and (map? m) (contains? m :definite?)))

(defmacro with-errors
  [op read-types & body]
  `(try+ (remap-errors ~@body)
         (catch client-error? e#
           (assoc ~op
                  :type (if (or (:definite? e#) (~read-types (:f ~op))) :fail :info)
                  :error [(:type e#) (:description e#)]))))

(def def-val "")

(defn put!
  [c k v]
  (.execute c (str "put \"" k "\" \"" v "\";")))

(defn get!
  [c k]
  (-> (.execute c (str "get \"" k "\";"))
      (.getOrDefault k def-val)))

(defn cas!
  [c k v v']
  (-> (.execute c (str "if get \"" k "\" == \"" v "\" { put \"" k "\" \"" v' "\"; }"))
      (.getOrDefault k def-val)
      (= v)))

(defn to-long-seq
  "Converts list of the form v1,v2,v3,...,v4,
   where vi are integers into a seq of longs"
  [s]
  (map u/parse-long (filter (partial not= "") (split s #","))))

(defn append!
  "Assumes each accessed key is a string of the form `v1,v2,v3...,vn,`,
   where v1, ..., vn are integers.
   For each given [k v] pair, appends `v,` to the string under k.
   Returns the state of each accessed list before the operation
   as a map from integers to seqs of integers.
   A key may appear multiple times in the list, the effect will be
   appending the value from each appearance.

    :ops list of [k v] pairs for appending
    :rs additional keys for reading"
  [c ops rs]
  (let [app (map (fn [[k v]] (str "put \"" k "\" get \"" k "\" + \"" v ",\";")) ops)
        rd (map (fn [k] (str "get \"" k "\";")) rs)
        cmd (join " " (concat app rd))
        all-keys (->> ops
                      (map (fn [[k v]] k))
                      (concat rs)
                      ((comp seq set)))
        res (.execute c cmd)]
    (zipmap all-keys
            (map #(to-long-seq (.getOrDefault res % ""))
                 (map str all-keys)))))
