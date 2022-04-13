(ns jepsen.crdt-redis
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.ubuntu :as ubuntu]))

(defn db
  "CRDT-Redis for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing crdt-redis" version))

    (teardown! [_ test node]
      (info node "tearing down crdt-redis"))))

(defn crdt-redis-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "crdt-redis"
          :os   ubuntu/os
          :db   (db "v3.1.5")
          :pure-generators true}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn crdt-redis-test})
            args))

;; lein run test --nodes-file nodes --password 123456 --username root