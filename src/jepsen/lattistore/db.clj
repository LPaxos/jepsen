(ns jepsen.lattistore.db
  (:require ;[clojure.java.io :as io]
            [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [jepsen [control :as c]
                    [db :as db]]
            [jepsen.control.util :as cu]
            [jepsen.lattistore.support :as s]))

(def dir "/opt/lattistore")
(def binary "server")
(def logfile (str dir "/server.log"))
(def pidfile (str dir "/server.pid"))

(defn start!
  [node opts]
  (c/su
    (cu/start-daemon!
      {:logfile logfile
       :pidfile pidfile
       :chdir dir}
      binary
      :--my-ip (s/node-ip node)
      :--node-ips (s/node-ips (:nodes opts))
      )))

(defn db
  []
  (reify
    db/Pause
    (pause! [_ test node] (c/su (cu/grepkill! :stop "server")))
    (resume! [_ test node] (c/su (cu/grepkill! :cont "server")))

    db/DB
    (setup! [db test node]
      (info node "installing")
      (c/su
        (c/exec :mkdir dir)
        (c/exec :cp "/root/server" dir))

      (info node "starting node")
      (start! node {:nodes (:members test)})

      (info node "waiting 1s") ; todo: not very elegant
      (Thread/sleep 1000))

    (teardown! [db test node]
      (info node "teardown")
      (cu/stop-daemon! binary pidfile)
      (c/su (c/exec :rm :-rf dir)))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))
