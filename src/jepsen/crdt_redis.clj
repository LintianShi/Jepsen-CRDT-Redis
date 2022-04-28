(ns jepsen.crdt-redis
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen.crdt-redis [support :as spt]
                               [set :as set]
                               [rpq :as rpq]]
            [jepsen [cli :as cli]
                    [checker :as checker]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [tests :as tests]]
            [knossos.model :as model]
            [jepsen.control.util :as cu]
            [jepsen.os.ubuntu :as ubuntu])
    (:use [slingshot.slingshot :only [try+]])
    (:import [checking JChecker]))

;; ../../redis-6.0.5/src/redis-cli -h 127.0.0.1 -p 6379 REPLICATE 3 0 AUTOMAT 172.24.81.132 6379 172.24.81.136 6379 172.24.81.137 6379

(defn db
  "CRDT-Redis for a particular version."
  []
  (reify db/DB
    (setup! [_ test node]
      (info node "starting crdt-redis")
      (info (c/exec "cd" spt/script_path c/&& spt/start_server spt/port))
      (Thread/sleep 1000)
      (info (c/exec "cd" spt/script_path c/&& spt/repl "-h" spt/local_host "-p" spt/port "REPLICATE" (count spt/host_map) (get spt/host_map (str node)) "AUTOMAT" (spt/all_host_port)))
      (Thread/sleep 1000))

    (teardown! [_ test node]
      (info node "tearing down crdt-redis")
      (info (c/exec "cd" spt/script_path c/&& spt/shutdown_server spt/port))
      (Thread/sleep 1000)
      (info (c/exec "cd" spt/script_path c/&& spt/clean spt/port))
      (Thread/sleep 1000))))

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {"set"      set/workload
   "rpq"      rpq/workload})

(defn crdt-redis-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (let [cl (cond (= (:workload opts) "rpq") (rpq/->PQClient nil (:type opts))
                 (= (:workload opts) "set") (set/->SetClient nil (:type opts)))
        workload (get workloads (:workload opts))]
  (merge tests/noop-test
         opts
         {:name "crdt-redis"
          :os   ubuntu/os
          :db   (db)
          :pure-generators true
          :client cl
          :checker         (checker/visearch-checker (:workload opts))
          :generator       (->> workload
                                (gen/stagger 1/5)
                                (gen/nemesis nil)
                                (gen/time-limit 10))})))

(def cli-opts
  "Additional command line options."
  [["-w" "--workload NAME" "Workloads for test."]
   ["-t" "--type NAME" "Type for workloads/."]])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  crdt-redis-test
                                        :opt-spec cli-opts})
                  (cli/serve-cmd))
            args))
  ;; (info (type (rwfzadd 1 1))))

;; lein run test --nodes-file nodes --password 123456 --username root
