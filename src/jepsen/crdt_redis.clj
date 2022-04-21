(ns jepsen.crdt-redis
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
                    [checker :as checker]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [tests :as tests]]
            [knossos.model :as model]
            [jepsen.control.util :as cu]
            [jepsen.os.ubuntu :as ubuntu]
            [taoensso.carmine :as car :refer (wcar)])
    (:use [slingshot.slingshot :only [try+]])
    (:import [checking JChecker]))

(def script_path "/home/shilintian/Redis-CRDT-Experiment/experiment/redis_test/")
(def start_server "./server.sh")
(def shutdown_server "./shutdown.sh")
(def clean "./clean.sh")
(def repl "../../redis-6.0.5/src/redis-cli")
(def host_map {"172.24.81.136" 0 
      "172.24.81.137" 1 
      "172.24.81.132" 2})
(def port "6379")
(def local_host "127.0.0.1")
(defn all_host_port []
  (str/split (str/join " " (map #(str % " " port ) (keys host_map))) #" "))

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
      (info (c/exec "cd" script_path c/&& repl "-h" local_host "-p" port "REPLICATE" (count host_map) (get host_map (str node)) "AUTOMAT" (all_host_port)))
      (Thread/sleep 1000))

    (teardown! [_ test node]
      (info node "tearing down crdt-redis")
      (info (c/exec "cd" script_path c/&& shutdown_server port))
      (Thread/sleep 1000)
      (info (c/exec "cd" script_path c/&& clean port))
      (Thread/sleep 1000))))

;; rwf-pq
(defn rwfzadd   [_ _] {:type :invoke, :f :add, :value ["rwfzadd" "default" (rand-int 5) (rand-int 100)]})
(defn rwfzincrby   [_ _] {:type :invoke, :f :incrby, :value ["rwfzincrby" "default" (rand-int 5) (- (rand-int 200) 100)]})
(defn rwfzrem   [_ _] {:type :invoke, :f :rem, :value ["rwfzrem" "default" (rand-int 5)]})
(defn rwfzscore   [_ _] {:type :invoke, :f :score, :value ["rwfzscore" "default" (rand-int 5)]})
(defn rwfzmax   [_ _] {:type :invoke, :f :max, :value ["rwfzmax" "default"]})

;; add-win-pq
(defn ozadd   [_ _] {:type :invoke, :f :add, :value ["ozadd" "default" (rand-int 5) (rand-int 100)]})
(defn ozincrby   [_ _] {:type :invoke, :f :incrby, :value ["ozincrby" "default" (rand-int 5) (- (rand-int 200) 100)]})
(defn ozrem   [_ _] {:type :invoke, :f :rem, :value ["ozrem" "default" (rand-int 5)]})
(defn ozscore   [_ _] {:type :invoke, :f :score, :value ["ozscore" "default" (rand-int 5)]})
(defn ozmax   [_ _] {:type :invoke, :f :max, :value ["ozmax" "default"]})

;; remove-win-pq
(defn rzadd   [_ _] {:type :invoke, :f :add, :value ["rzadd" "default" (rand-int 5) (rand-int 100)]})
(defn rzincrby   [_ _] {:type :invoke, :f :incrby, :value ["rzincrby" "default" (rand-int 5) (- (rand-int 200) 100)]})
(defn rzrem   [_ _] {:type :invoke, :f :rem, :value ["rzrem" "default" (rand-int 5)]})
(defn rzscore   [_ _] {:type :invoke, :f :score, :value ["rwfzscore" "default" (rand-int 5)]})
(defn rzmax   [_ _] {:type :invoke, :f :max, :value ["rzmax" "default"]})

;; rwf-set
(defn rwfsadd   [_ _] {:type :invoke, :f :add, :value ["rwfsadd" "default" (rand-int 5) (rand-int 100)]})
(defn rwfsrem   [_ _] {:type :invoke, :f :rem, :value ["rwfsrem" "default" (rand-int 5)]})
(defn rwfscontains   [_ _] {:type :invoke, :f :contains, :value ["rwfscontains" "default" (rand-int 5)]})
(defn rwfssize   [_ _] {:type :invoke, :f :size, :value ["rwfssize" "default"]})

;; add-win-set
(defn osadd   [_ _] {:type :invoke, :f :add, :value ["osadd" "default" (rand-int 5) (rand-int 100)]})
(defn osrem   [_ _] {:type :invoke, :f :rem, :value ["osrem" "default" (rand-int 5)]})
(defn oscontains   [_ _] {:type :invoke, :f :contains, :value ["oscontains" "default" (rand-int 5)]})
(defn ossize   [_ _] {:type :invoke, :f :size, :value ["ossize" "default"]})

;; remove-win-set
(defn rsadd   [_ _] {:type :invoke, :f :add, :value ["rsadd" "default" (rand-int 5) (rand-int 100)]})
(defn rsrem   [_ _] {:type :invoke, :f :rem, :value ["rsrem" "default" (rand-int 5)]})
(defn rscontains   [_ _] {:type :invoke, :f :contains, :value ["rscontains" "default" (rand-int 5)]})
(defn rssize   [_ _] {:type :invoke, :f :size, :value ["rssize" "default"]})


(defmacro wcar* [node & body] `(car/wcar {:pool {:host node :port 6379} :spec {}} ~@body))
(def tkey (partial car/key :carmine :temp :test))

(defrecord PQClient [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn node))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
        :add (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op)))
                 (assoc op :type :ok))
              (catch [] ex
                  (assoc op :type :ok)))
        :incrby (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op)))
                    (assoc op :type :ok))
                (catch [] ex
                  (assoc op :type :ok)))
        :rem (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op)))
                 (assoc op :type :ok))
              (catch [] ex
                  (assoc op :type :ok)))
        :score (assoc op :type :ok, :value (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op))))
        :max (assoc op :type :ok, :value (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op))))))

  (teardown! [this test])

  (close! [_ test]))

(defrecord SetClient [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn node))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
        :add (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op)))
                 (assoc op :type :ok))
              (catch [] ex
                  (assoc op :type :ok)))
        :rem (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op)))
                 (assoc op :type :ok))
              (catch [] ex
                  (assoc op :type :ok)))
        :contains (assoc op :type :ok, :value (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op))))
        :size (assoc op :type :ok, :value (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (:value op))))))

  (teardown! [this test])

  (close! [_ test]))



(defn crdt-redis-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "crdt-redis"
          :os   ubuntu/os
          :db   (db)
          :pure-generators true
          :client (PQClient. nil)
          :checker         (checker/visearch-checker "rpq")
          :generator       (->> (gen/mix [rwfzadd rwfzincrby rwfzrem rwfzscore rwfzmax])
                                (gen/stagger 1)
                                (gen/nemesis nil)
                                (gen/time-limit 30))}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn crdt-redis-test})
            args))

;; lein run test --nodes-file nodes --password 123456 --username root