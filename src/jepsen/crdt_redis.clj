(ns jepsen.crdt-redis
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [tests :as tests]]
            [jepsen.control.util :as cu]
            [taoensso.carmine :as car :refer (wcar)]
            [jepsen.os.ubuntu :as ubuntu]))

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


(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defmacro wcar* [node & body] `(car/wcar {:pool {:host node :port 6379} :spec {}} ~@body))
(def tkey (partial car/key :carmine :temp :test))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn node))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
        :read (assoc op :type :ok, :value (car/wcar {:pool {} :spec {:host conn :port 6379}} 
                                                    (car/get (tkey "foo") )))
        :write (do 
        (car/wcar 
        {:pool {} :spec {:host conn :port 6379}} 
                                           (car/set 
                                           (tkey "foo") 
                                           (:value op)))
                    (assoc op :type :ok))))

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
          :client (Client. nil)
          :generator       (->> (gen/mix [r w])
                                (gen/stagger 1)
                                (gen/nemesis nil)
                                (gen/time-limit 15))}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn crdt-redis-test})
            args))

;; lein run test --nodes-file nodes --password 123456 --username root