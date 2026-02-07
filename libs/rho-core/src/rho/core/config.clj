(ns rho.core.config
  "Configuration loading and merging for Rho. Reads `config.edn` and
  profile-specific overrides, then merges module configs for Integrant."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [rho.core.classpath :as cp]))

(defn- normalize-profile
  [profile]
  (let [s (cond
            (nil? profile) nil
            (keyword? profile) (name profile)
            (string? profile) profile
            :else (str profile))]
    (some-> s
            str/trim
            not-empty
            str/lower-case
            keyword)))

(defn- resolve-profile
  [opts]
  (let [env-profile (some-> (System/getenv "RHO_ENV") str/trim not-empty)]
    (or (normalize-profile env-profile)
        (normalize-profile (:rho/profile opts))
        :dev)))

(defn- read-config
  [resource profile]
  (aero/read-config resource {:profile profile}))

(defn- read-config!
  [resource profile path]
  (try
    (read-config resource profile)
    (catch Exception t
      (throw (ex-info (str "Failed to read config: " path)
                      {:path path
                       :profile profile}
                      t)))))

(defn- path-prefix?
  [prefix path]
  (or (= prefix path)
      (str/starts-with? path (str prefix java.io.File/separator))))

(defn- local-resource?
  [resource local-roots]
  (when (= "file" (.getProtocol resource))
    (let [file (io/as-file resource)
          path (.getCanonicalPath ^java.io.File file)]
      (some #(path-prefix? % path) local-roots))))

(defn ordered-resource-urls
  "Return a vector of classpath URLs for `path` in deterministic order.
  If `local-roots` are provided, local resources are placed last so they
  override dependency resources. Without `local-roots`, the primary
  `io/resource` (if any) is placed last."
  ([path] (ordered-resource-urls path nil))
  ([path local-roots]
   (let [resources (vec (or (cp/resource-urls path) []))
         resources (if (seq local-roots)
                     (let [[deps locals]
                           (reduce (fn [[deps locals] resource]
                                     (if (local-resource? resource local-roots)
                                       [deps (conj locals resource)]
                                       [(conj deps resource) locals]))
                                   [[] []]
                                   resources)]
                       (concat deps locals))
                     (let [primary (io/resource path)
                           primary-id (some-> primary str)]
                       (if primary-id
                         (concat (remove #(= (str %) primary-id) resources) [primary])
                         resources)))]
     (->> resources distinct vec))))

(defn merge-resource-maps
  "Merge config maps from `resources` using `read-fn`. Skips nils and
  merges left-to-right so later resources win."
  [resources read-fn]
  (reduce (fn [acc resource]
            (if-let [data (read-fn resource)]
              (merge acc data)
              acc))
          {}
          resources))

(defn- log-config-resources
  [path resources]
  (if (seq resources)
    (log/debug "Found config resources:" path "-" (count resources)
               (mapv str resources))
    (log/debug "No config resources found:" path)))

(defn- merge-config-resources
  [path profile resources]
  (log-config-resources path resources)
  (merge-resource-maps
   resources
   (fn [resource]
     (let [config (read-config! resource profile path)]
       (log/info "Merging config resource:" path "-" (str resource))
       config))))

(defn- read-required-config
  [path profile]
  (let [resources (ordered-resource-urls path)]
    (when (empty? resources)
      (throw (ex-info (str "Missing config resource: " path)
                      {:path path})))
    (merge-config-resources path profile resources)))

(defn- read-optional-config
  [path profile]
  (let [resources (ordered-resource-urls path)]
    (when (seq resources)
      (merge-config-resources path profile resources))))

(defn- module-base-path
  [module-ns]
  (let [ns-name (cond
                  (symbol? module-ns) (name module-ns)
                  (keyword? module-ns) (name module-ns)
                  (string? module-ns) module-ns
                  :else (str module-ns))
        ns-name (if (str/ends-with? ns-name ".module")
                  (subs ns-name 0 (- (count ns-name) (count ".module")))
                  ns-name)]
    (str/replace ns-name "." "/")))

(defn load-config
  "Load the base `config.edn` and optional `config.<profile>.edn`, merging
  them with `opts`. Resolves profile from `RHO_ENV` or `:rho/profile`, and
  returns a map with `:rho/profile` set."
  [opts]
  (let [profile (resolve-profile opts)
        base-path "config.edn"
        base-config (read-required-config base-path profile)
        env-path (format "config.%s.edn" (name profile))
        env-config (read-optional-config env-path profile)]
    (-> (merge base-config
               env-config
               (dissoc opts :rho/profile :rho/config-loaded?))
        (assoc :rho/profile profile))))

(defn load-module-configs
  "Load and merge per-module config files for `modules` and `profile`.
  Each module may provide `config.edn` and `config.<profile>.edn` under its
  namespace path."
  [modules profile]
  (reduce (fn [acc module]
            (if-let [module-ns (:rho/module-ns module)]
              (let [base-dir (module-base-path module-ns)
                    base-path (str base-dir "/config.edn")
                    profile-path (str base-dir "/config." (name profile) ".edn")
                    base-config (read-optional-config base-path profile)
                    profile-config (read-optional-config profile-path profile)]
                (merge acc base-config profile-config))
              acc))
          {}
          modules))

(defmethod ig/init-key :rho/config
  [_ opts]
  (if (:rho/config-loaded? opts)
    (dissoc opts :rho/config-loaded?)
    (load-config opts)))
