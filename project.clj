(defproject clojureclient "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [jepsen "0.2.1"]

                 [io.grpc/grpc-netty-shaded "1.31.1"]
                 [io.grpc/grpc-protobuf "1.31.1"]
                 [io.grpc/grpc-stub "1.31.1"]
                 [org.apache.tomcat/annotations-api "6.0.53"]
                 [slingshot "0.12.2"]
                 ]
  :java-source-paths ["src/java"]
  :main jepsen.lattistore
  ;:main runcl
  :repl-options {:init-ns jepsen.lattistore})
