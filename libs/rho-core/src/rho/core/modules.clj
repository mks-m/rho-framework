(ns rho.core.modules
  "Module discovery and loading for Rho. Scans the classpath for namespaces
  marked with `^:rho/module` and assembles their Integrant contributions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [rho.core.classpath :as cp]
            [rho.core.jarlister :as jarlister])
  (:import [java.io File]))

(defn- ns-meta-module?
  "Returns true if the namespace `ns-sym` has metadata `:rho/module true`.
  Requires the namespace before checking metadata."
  [ns-sym]
  (log/info "Checking namespace:" ns-sym)
  (require ns-sym)
  (when (-> (the-ns ns-sym) meta :rho/module)
    (log/info "Loaded module:" ns-sym)
    true))

(defn- candidates-in-dir
  "Find candidate namespace symbols under `base-ns` rooted at the classpath directory `root-dir`.
  Only returns candidates whose source files end with .clj or .cljc."
  [root-dir base-ns]
  (let [base-path (str/replace (name base-ns) "." "/")
        base-dir  (io/file root-dir base-path)
        base-file? (some (fn [^java.io.File f] (.isFile f))
                         [(io/file root-dir (str base-path ".clj"))
                          (io/file root-dir (str base-path ".cljc"))])]
    (concat
     (when base-file?
       [(symbol (name base-ns))])
     (when (.isDirectory base-dir)
       (let [files (->> (file-seq base-dir)
                        (filter #(.isFile ^java.io.File %))
                        (filter #(re-find #"\.(clj|cljc)$" (.getName ^java.io.File %))))]
         (map (fn [^java.io.File f]
                (let [rel (str (.relativize (.toURI base-dir) (.toURI f)))
                      ns-suffix (-> rel
                                    (str/replace #"\.(clj|cljc)$" "")
                                    (str/replace "/" "."))]
                  (symbol (str (name base-ns)
                               (when (seq ns-suffix) ".")
                               ns-suffix))))
              files))))))

(defn- find-modules [base-ns]
  (let [indexed (cp/read-module-indices)
        use-index? (seq indexed)
        entries (cp/classpath-entries)
        dir-entries (filter #(.isDirectory ^File %) entries)
        base-cands (if use-index?
                     (do
                       (log/info "Using module indices from rho/modules.edn ("
                                 (count indexed) "entries)")
                       (mapcat (fn [^File entry]
                                 (candidates-in-dir entry base-ns))
                               dir-entries))
                     (mapcat (fn [^File entry]
                               (cond
                                 (.isDirectory entry)
                                 (candidates-in-dir entry base-ns)

                                 (and (.isFile entry)
                                      (.endsWith (.getName entry) ".jar"))
                                 (jarlister/list-jar-namespaces entry base-ns)

                                 :else []))
                             entries))
        cands (->> (concat (or indexed []) base-cands)
                   distinct)
        modules (filter ns-meta-module? cands)]
    (vec modules)))

(defn- throw-no-app-ns
  "Raises when no application namespace is configured."
  []
  (throw (ex-info "Default application namespace could not be determined. Please specify :rho/app-ns in the configuration." {})))

(defn- module-order
  [ns-sym module]
  (let [order (:rho/order module)]
    (cond
      (nil? order) 0
      (number? order) order
      :else (throw (ex-info "Invalid :rho/order for module."
                            {:ns ns-sym
                             :order order})))))

(defn- resolve-module
  [ns-sym]
  (let [v (ns-resolve ns-sym 'module)]
    (when-not v
      (throw (ex-info (str "Namespace marked as module but no `module` var found: " ns-sym)
                      {:ns ns-sym})))
    (let [module (deref v)]
      (when-not (map? module)
        (throw (ex-info "Module var is not a map."
                        {:ns ns-sym
                         :type (type module)})))
      (let [order (module-order ns-sym module)
            module (-> module
                       (assoc :rho/module-ns ns-sym)
                       (assoc :rho/order order))]
        {:module module
         :order order
         :ns ns-sym}))))

(defn load-modules
  "Discover and load module maps under the configured app namespace.
  Returns a vector of module maps sorted by `:rho/order` and namespace."
  [config]
  (let [app-ns (or (:rho/app-ns config) (throw-no-app-ns))]
    (log/info "Loading modules for" app-ns)
    (->> (find-modules app-ns)
         (map resolve-module)
         (sort-by (fn [{:keys [order ns]}]
                    [order (str ns)]))
         (mapv :module))))

(defmethod ig/init-key :rho/modules
  [_ {:keys [config modules] :as opts}]
  (if (contains? opts :modules)
    (do
      (log/info "Using preloaded modules (" (count modules) ")")
      {:modules (vec modules)})
    {:modules (load-modules config)}))
