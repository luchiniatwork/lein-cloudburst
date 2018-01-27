(ns lein-cloudburst.aws
  (:require [aws-sig4.middleware :as aws-sig4]
            [clj-http.client :as http]
            [clojure.data.codec.base64 :as b64]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [environ.core :as env]))

;; --------------------
;; Basic Settings
;; --------------------

(defn ^:private region []
  (or (env/env :aws-default-region) "us-east-1"))

(defn ^:private access-key []
  (if-let [out (env/env :aws-access-key-id)]
    out
    (throw (Exception. "Must set environment variable AWS_ACCESS_KEY_ID"))))

(defn ^:private secret-key []
  (if-let [out (env/env :aws-secret-access-key)]
    out
    (throw (Exception. "Must set environment variable AWS_SECRET_ACCESS_KEY"))))

(defn ^:private base-url [service]
  (str "https://" service "." (region) ".amazonaws.com"))

(defn ^:private service-wrapper [service]
  (aws-sig4/build-wrap-aws-auth {:region (region)
                                 :service service
                                 :access-key (access-key)
                                 :secret-key (secret-key)}))

(def ^:private wrappers
  {:lambda (service-wrapper "lambda")
   :apigateway (service-wrapper "apigateway")})

(def ^:private base-urls
  {:lambda (base-url "lambda")
   :apigateway (base-url "apigateway")})

;; --------------------
;; Utils
;; --------------------

(defn ^:private byte-buffer-zip-file [source-file]
  (let [input (io/input-stream source-file)
        baos (java.io.ByteArrayOutputStream.)
        zos  (java.util.zip.ZipOutputStream. baos)]
    (.putNextEntry zos (java.util.zip.ZipEntry. source-file))
    (io/copy input zos)
    (.closeEntry zos)
    (flush)
    (.finish zos)
    (.toByteArray baos)))

(defn ^:private byte-buffer-file [source-file]
  (let [input (io/input-stream source-file)
        output (java.io.ByteArrayOutputStream.)]
    (io/copy input output)
    (.toByteArray output)))

;; --------------------
;; AWS Requests
;; --------------------

(defn ^:private aws-base [fverb url wrapper]
  (let [res (http/with-additional-middleware
              [wrapper aws-sig4/wrap-aws-date]
              (fverb url))
        body (:body res)]
    (assoc res :body (json/parse-string body true))))

(defn ^:private aws-get [url wrapper]
  (aws-base http/get url wrapper))

(defn ^:private aws-delete [url wrapper]
  (aws-base http/delete url wrapper))

(defn ^:private aws-post [url wrapper body]
  (let [res (http/with-additional-middleware
              [wrapper aws-sig4/wrap-aws-date]
              (http/post url {:body (json/generate-string body)}))
        body (:body res)]
    (assoc res :body (json/parse-string body true))))

;; --------------------
;; Public-facing functions
;; --------------------

(defn list-functions []
  (let [url (str (:lambda base-urls) "/2015-03-31/functions/")]
    (:body (aws-get url (:lambda wrappers)))))

(defn delete-function [fname]
  (let [url (str (:lambda base-urls) "/2015-03-31/functions/" fname)]
    (:body (aws-delete url (:lambda wrappers)))))

(defn create-function [cloud-fn-meta jar-path]
  (let [{fname :cloudburst/name} cloud-fn-meta
        {:keys [:cloudburst/runtime
                :cloudburst/memory
                :cloudburst/role]} cloud-fn-meta
        handler (str (:ns cloud-fn-meta) "." (:name cloud-fn-meta))
        url (str (:lambda base-urls) "/2015-03-31/functions/")
        body {:Code
              {:ZipFile
               (String.
                (b64/encode
                 (byte-buffer-file jar-path)))}
              :FunctionName fname
              :Handler handler
              :Role role
              :Runtime (name runtime)
              :MemorySize memory}
        res (aws-post url (:lambda wrappers) body)]
    (if (= 201 (:status res))
      (:body res)
      (throw (ex-info "Failure uploading." res)))))
