(ns jepsen.crdt-redis
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clojure.set :as cljset]
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
    (:import [checking JChecker]
             [datatype AbstractDataType]
             [datatype DataTypeCreator]
             [history Invocation]))

;; (defrecord TestSet [data]
;;   model/Model
;;   (step [r op]
;;     (condp = (:f op)
;;       :add (TestSet. (conj (:data r) (:value op)))
;;       :remove   (TestSet. (disj (:data r) (:value op)))
;;       :contains  (if (contains? (:data r) (:value op))
;;                    (TestSet. (:data r))
;;                    nil))))

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

(defn test-model
  "Some docstring about what this specific implementation of Doer
  does differently than the other ones. For example, this one does
  not actually do anything but print the given string to stdout."
  []
  (let [ctx (atom #{})]
    (reify
      AbstractDataType
      ;; (init [this context] (reset! ctx context))
      (step [this invocation] (cond
                                (= "add" (.getMethodName invocation)) (some? (swap! ctx conj (int (.get (.getArguments invocation) 0))))
                                (= "remove" (.getMethodName invocation)) (some? (swap! ctx disj (int (.get (.getArguments invocation) 0))))
                                (= "contains" (.getMethodName invocation)) (= (contains? @ctx (int (.get (.getArguments invocation) 0))) (= 1 (int (.get (.getRetValues invocation) 0))))
                                (= "size" (.getMethodName invocation)) (= (count @ctx) (int (.get (.getRetValues invocation) 0)))))
      (reset [this] (reset! ctx #{})))))

(defn test-creator
  []
  (reify
    DataTypeCreator
    (createDataType [this] (test-model))))

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
          :checker         (checker/visearch-checker (test-creator))
          :generator       (->> workload
                                (gen/stagger 1/2)
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
