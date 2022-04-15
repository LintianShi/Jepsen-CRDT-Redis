(ns jepsen.crdt-redis
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.ubuntu :as ubuntu]))

(def script_path "/home/shilintian/Redis-CRDT-Experiment/experiment/redis_test/")
(def start_server "./server.sh")
(def shutdown_server "./shutdown.sh")
(def clean "./clean.sh")
(def replicate "../../redis-6.0.5/src/redis-cli")
(def host_map {"172.24.81.136" 0 
      "172.24.81.137" 1 
      "172.24.81.132" 2})
(def port "6379")
(def local_host "127.0.0.1")
(defn all_host_port []
  (clojure.string/split (clojure.string/join " " (map #(str % " " port ) (keys host_map))) #" "))

;; ../../redis-6.0.5/src/redis-cli -h 127.0.0.1 -p 6379 REPLICATE 3 0 AUTOMAT 172.24.81.132 6379 172.24.81.136 6379 172.24.81.137 6379

(def counter (atom -1))

(defn next-value []
  (swap! counter inc))

(defn db
  "CRDT-Redis for a particular version."
  []
  (reify db/DB
    (setup! [_ test node]
      (info node "starting crdt-redis")
      (info (c/exec "cd" script_path c/&& start_server port))
      (Thread/sleep 1000)
      (info (c/exec "cd" script_path c/&& replicate "-h" local_host "-p" port "REPLICATE" (count host_map) (get host_map (str node)) "AUTOMAT" (all_host_port)))
      (Thread/sleep 1000))

    (teardown! [_ test node]
      (info node "tearing down crdt-redis")
      (info (c/exec "cd" script_path c/&& shutdown_server port))
      (Thread/sleep 1000)
      (info (c/exec "cd" script_path c/&& clean port))
      (Thread/sleep 1000))))

(defn crdt-redis-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "crdt-redis"
          :os   ubuntu/os
          :db   (db)
          :pure-generators true}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn crdt-redis-test})
            args))

;; lein run test --nodes-file nodes --password 123456 --username root