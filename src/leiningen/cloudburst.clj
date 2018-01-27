(ns leiningen.cloudburst
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer [chan <! >! >!! <!! go]]
            [cloudburst.core :as cb]
            [leiningen.core.eval :as lein-eval]
            [leiningen.uberjar :as lein-uberjar]
            [lein-cloudburst.aws :as aws])
  (:import (java.net Socket ServerSocket)))

(def ^:private port 9471)

(defn ^:private create-server[port]
  (ServerSocket. port))

(defn ^:private listen [server-socket]
  (.accept server-socket))

(defn ^:private server-async-read [server ch]
  (go
    (let [conn (<! (go (listen server)))]
      (>! ch (-> conn
                 io/reader
                 line-seq)))))

(defn ^:private stream-meta-out* [port meta-sym]
  `(let [~'socket (java.net.Socket. "localhost" ~port)
         ~'stream (clojure.java.io/writer ~'socket)]
     (.write ~'stream (pr-str ~meta-sym))
     (.flush ~'stream)
     (.close ~'socket)))

(def ^:private cloud-fn-metas*
  '(reduce (fn [a [sym var]]
             (let [this-meta (meta var)]
               (if (:cloudburst/deployable this-meta)
                 (conj a this-meta)
                 a)))
           [] (ns-publics this-ns)))

(defn ^:private eval-code* []
  `(do
     (let [~'all-metas (flatten
                        (reduce (fn [~'c ~'this-ns]
                                  (let [~'cloud-fn-metas ~cloud-fn-metas*]
                                    (if (> (count ~'cloud-fn-metas) 0)
                                      (conj ~'c ~'cloud-fn-metas)
                                      ~'c)))
                                []
                                (all-ns)))]
       ~(stream-meta-out* port 'all-metas))))

(defn ^:private run-eval [project]
  (go (lein-eval/eval-in-project
       project
       (eval-code*)
       '(require 'lambda-test.services.hello.core 'clojure.pprint))))

(defn ^:private object-reader [i]
  (-> i last symbol))

(def ^:private read-edn-string
  (partial edn/read-string {:readers {'object object-reader}}))

(defn cloudburst
  [project & args]
  (let [jar-filename (lein-uberjar/uberjar project)
        server (create-server port)
        from-subprocess (chan)]
    (server-async-read server from-subprocess)
    (println "Inspecting for cloud functions")
    (run-eval project)
    (let [cloud-fns (-> (<!! from-subprocess)
                        first
                        read-edn-string)]
      (doseq [cloud-fn cloud-fns]
        (println "Deploying" (str (:ns cloud-fn) (:name cloud-fn)))
        (println cloud-fn)))
    (println "Done!"))
  
  #_(clojure.pprint/pprint project)
  #_(let [jar-filename (lein-uberjar/uberjar project)]
      (clojure.pprint/pprint (aws/create-function "lambda-test_services_hello_core_world2" nil)))
  #_(clojure.pprint/pprint (aws/delete-function "lambda-test_services_hello_core_world2"))
  #_(clojure.pprint/pprint (aws/list-functions)))
