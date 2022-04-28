(ns jepsen.crdt-redis.support
  (:require [clojure.string :as str]
            [taoensso.carmine :as car :refer (wcar)]))

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
  (str/split (str/join " " (map #(str % " " port) (keys host_map))) #" "))

(defn parse-number [s]
  (if (nil? s)
    nil
    (Integer/parseInt s)))