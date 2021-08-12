(ns com.github.bentomi.demo-test
  (:require [clojure.spec.alpha :as spec]
            [clojure.test :as test :refer [deftest is]])
  (:import (java.util.concurrent Callable Executors TimeoutException TimeUnit)))

(def modulus 97)

(def global (atom 0))

(defn sut []
  (let [c @global]
    (Thread/sleep (inc (rand-int 20)))
    (let [m (mod c modulus)
          result (if (pos? m)
                   (swap! global + (- modulus m))
                   c)]
      (when (pos? (mod @global modulus))
        (case (rand-int 200)
          0 (throw (ex-info "error detected" {}))
          1 (Thread/sleep 60000)
          nil))
      result)))

(test/use-fixtures :each (fn [f]
                           (reset! global (rand-int 10000))
                           (f)))

(defn sut-check []
  (is (zero? (mod (sut) modulus)))
  (is (nat-int? (sut))))

(deftest single-thread
  (sut-check))

(deftest multi-thread-naive
  (let [threads 8, tasks (* 2 threads)
        executor (Executors/newFixedThreadPool threads)]
    (try
      (->> (repeatedly tasks #(.submit executor ^Callable sut-check))
           doall
           (map #(try (.get % 1 TimeUnit/SECONDS)
                      (catch TimeoutException _ ::timeout)))
           (every? true?)
           is)
      (finally
        (.shutdown executor)))))

(deftest multi-thread-bound-fn
  (let [threads 8, tasks (* 2 threads)
        ^Callable sut-check (bound-fn* sut-check)
        executor (Executors/newFixedThreadPool threads)]
    (try
      (->> (repeatedly tasks #(.submit executor sut-check))
           doall
           (map #(try (.get % 1 TimeUnit/SECONDS)
                      (catch TimeoutException _ ::timeout)))
           (every? true?)
           is)
      (finally
        (.shutdown executor)))))

(deftest multi-thread-conveying-inline
  (let [threads 8, tasks (* 2 threads)
        executor (Executors/newFixedThreadPool threads)
        original-executor clojure.lang.Agent/soloExecutor]
    (set-agent-send-off-executor! executor)
    (try
      (->>
       (repeatedly tasks #(future-call sut-check))
       doall
       (map #(deref % 1000 ::timeout))
       (every? true?)
       is)
      (finally
        (set-agent-send-off-executor! original-executor)
        (.shutdown executor)))))

(spec/fdef with-send-off-executor
  :args (spec/cat :binding (spec/spec (spec/cat :name simple-symbol?
                                                :executor any?))
                  :body (spec/+ any?)))

(defmacro with-send-off-executor
  "Creates an ExecutorService by calling `executor`, sets it for the scope
  of the form as executor for `send-off`, `future`, etc. and executes `body`.
  The executor service created is bound to `name` and shut down after the
  execution of `body`."
  [[name executor] & body]
  `(let [~name ~executor
         original-executor# clojure.lang.Agent/soloExecutor]
     (set-agent-send-off-executor! ~name)
     (try
       ~@body
       (finally
         (set-agent-send-off-executor! original-executor#)
         (.shutdown ~name)))))

(deftest multi-thread-conveying
  (let [threads 8, tasks (* 2 threads)]
    (with-send-off-executor [_executor (Executors/newFixedThreadPool threads)]
      (->> (repeatedly tasks #(future-call sut-check))
           doall
           (map #(deref % 1000 ::timeout))
           (every? true?)
           is))))
