(ns jepsen.lattistore.support
  (:require [jepsen.control.net :as c.net]
            [clojure.string :as str]))

(defn node-ip
  [node]
  (name node))

(defn node-url
  [node port]
  (str (node-ip node) ":" port))

(defn client-url
  [node]
  (node-url node 50051))

(defn node-ips
  [nodes]
  (->> nodes
       (map node-ip)))
