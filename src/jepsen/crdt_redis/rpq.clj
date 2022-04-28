(ns jepsen.crdt-redis.rpq
  (:require [clojure.tools.logging :refer :all]
            [jepsen.crdt-redis.support :as spt]
            [jepsen [client :as client]
                    [generator :as gen]]
            [taoensso.carmine :as car])
  (:use [slingshot.slingshot :only [try+]]))

;; pq
(defn zadd   [_ _] {:type :invoke, :f :add, :value [(rand-int 5) (rand-int 100)]})

(defn zincrby   [_ _] {:type :invoke, :f :incrby, :value [(rand-int 5) (- (rand-int 200) 100)]})

(defn zrem   [_ _] {:type :invoke, :f :rem, :value [(rand-int 5)]})

(defn zscore   [_ _] {:type :invoke, :f :score, :value [(rand-int 5)]})

(defn zmax   [_ _] {:type :invoke, :f :max, :value []})

(defn workload []
  (gen/mix [zadd zincrby zrem zscore zmax]))

(defrecord PQClient [conn type]
  client/Client
  (open! [this test node]
    (assoc this :conn node))

  (setup! [this test])

  (invoke! [_ test op]
    ;; (info conn)
    (case (:f op)
        :add (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (into [(str type "zadd") "default"] (:value op))))
                 (assoc op :type :ok, :value nil))
              (catch [] ex
                  (assoc op :type :fail, :value nil)))
        :incrby (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (into [(str type "zincrby") "default"] (:value op))))
                    (assoc op :type :ok, :value nil))
                (catch [] ex
                  (assoc op :type :fail, :value nil)))
        :rem (try+ (do (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (into [(str type "zrem") "default"] (:value op))))
                 (assoc op :type :ok, :value nil))
              (catch [] ex
                  (assoc op :type :fail, :value nil)))
        :score (assoc op :type :ok, :value (spt/parse-number (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (into [(str type "zscore") "default"] (:value op))))))
        :max (assoc op :type :ok, :value (vec (map spt/parse-number (car/wcar {:pool {} :spec {:host conn :port 6379}} (car/redis-call (into [(str type "zmax") "default"] (:value op)))))))))

  (teardown! [this test])

  (close! [_ test]))