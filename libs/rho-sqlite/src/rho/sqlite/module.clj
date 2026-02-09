(ns ^:rho/module rho.sqlite.module
  "SQLite module for Rho. Provides a pooled datasource, optional migrations,
  and optional seed data execution."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [rho.sqlite.schema :as schema])
  (:import [com.zaxxer.hikari HikariDataSource]))

(defn- require-jdbc-url
  [config]
  (let [jdbc-url (:rho/sqlite-url config)]
    (when-not (string? jdbc-url)
      (throw (ex-info "Missing required :rho/sqlite-url configuration."
                      {:key :rho/sqlite-url})))
    jdbc-url))

(defn- sqlite-opts
  [config]
  (or (:rho/sqlite-opts config) {}))

(defn- set-if
  [value setter]
  (when (some? value)
    (setter value)))

(defn- configure-pool!
  [^HikariDataSource datasource pool]
  (let [pool (or pool {})]
    (set-if (:pool-name pool) #(.setPoolName datasource %))
    (set-if (:auto-commit pool) #(.setAutoCommit datasource (boolean %)))
    (set-if (:maximum-pool-size pool) #(.setMaximumPoolSize datasource (int %)))
    (set-if (:minimum-idle pool) #(.setMinimumIdle datasource (int %)))
    (set-if (:connection-timeout-ms pool) #(.setConnectionTimeout datasource (long %)))
    (set-if (:idle-timeout-ms pool) #(.setIdleTimeout datasource (long %)))
    (set-if (:max-lifetime-ms pool) #(.setMaxLifetime datasource (long %)))))

(defn- migratus-config
  [sqlite-opts datasource]
  (let [base {:store :database
              :migration-dir "migrations"
              :db {:datasource datasource}}
        user (or (:migratus sqlite-opts) {})]
    (merge base user)))

(defn- migration-dirs
  [config]
  (let [dir (:migration-dir config)]
    (cond
      (string? dir) [dir]
      (sequential? dir) (vec (filter string? dir))
      :else [])))

(defn- available-migration-dirs
  [config]
  (->> (migration-dirs config)
       (filter io/resource)
       vec))

(defn- update-migration-dirs
  [config dirs]
  (assoc config :migration-dir (if (= 1 (count dirs))
                                 (first dirs)
                                 (vec dirs))))

(defn- run-migrations!
  [datasource sqlite-opts]
  (let [config (migratus-config sqlite-opts datasource)
        dirs (available-migration-dirs config)]
    (if (empty? dirs)
      (log/info "SQLite migrations skipped (no migration resources found).")
      (do
        (log/info "SQLite migrations starting.")
        (migratus/migrate (update-migration-dirs config dirs))
        (schema/write-schema! datasource)))))

(defn- read-seed-resource
  [path]
  (when (seq path)
    (when-let [resource (io/resource path)]
      (edn/read-string (slurp resource)))))

(defn- normalize-seed-statement
  [statement]
  (cond
    (string? statement) [statement]
    (and (map? statement) (string? (:sql statement)))
    (into [(:sql statement)] (or (:params statement) []))
    :else
    (throw (ex-info "Invalid seed statement format."
                    {:statement statement}))))

(defn- run-seed!
  [datasource sqlite-opts]
  (let [resource (or (:seed-resource sqlite-opts) "rho/sqlite/seed.edn")
        statements (read-seed-resource resource)]
    (cond
      (nil? statements)
      (log/info "SQLite seed skipped (resource not found)." resource)

      (not (sequential? statements))
      (throw (ex-info "Seed resource must contain a sequential collection."
                      {:path resource}))

      :else
      (do
        (log/info "SQLite seed starting with" (count statements) "statement(s).")
        (jdbc/with-transaction [tx datasource]
          (doseq [statement statements]
            (jdbc/execute! tx (normalize-seed-statement statement))))))))

(defmethod ig/init-key :rho-sqlite/datasource
  [_ {:keys [config]}]
  (let [sqlite-opts (sqlite-opts config)
        jdbc-url (require-jdbc-url config)
        datasource (doto (HikariDataSource.)
                     (.setJdbcUrl jdbc-url))]
    (configure-pool! datasource (:pool sqlite-opts))
    (log/info "SQLite datasource connected." {:jdbc-url jdbc-url})
    (when (:migrate? sqlite-opts)
      (run-migrations! datasource sqlite-opts))
    (when (:seed? sqlite-opts)
      (run-seed! datasource sqlite-opts))
    datasource))

(defmethod ig/halt-key! :rho-sqlite/datasource
  [_ datasource]
  (when datasource
    (.close ^HikariDataSource datasource))
  nil)

(def module
  {:rho/system {:rho-sqlite/datasource {:config (ig/ref :rho/config)}}
   :rho/components {:db (ig/ref :rho-sqlite/datasource)}})
