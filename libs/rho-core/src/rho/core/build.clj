(ns rho.core.build
  "Build tooling for assembling Rho apps into an uberjar with merged configs
  and a module index. Intended to be invoked from `clojure -T:build`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [rho.core.config :as config]
            [rho.core.classpath :as cp])
  (:import [java.io File PushbackReader]
           [java.util.jar JarFile]))

(def default-class-dir "target/classes")
(def default-uber-file "target/app-standalone.jar")
(def default-src-dirs ["src" "resources"])
(def default-main 'rho.core.app)

(defn- log-step
  [msg]
  (println "[build]" msg))

(defn- module-ns-from-file
  [^java.io.File file]
  (when (and (.isFile file)
             (re-find #"\.(clj|cljc)$" (.getName file)))
    (with-open [r (java.io.PushbackReader. (io/reader file))]
      (binding [*read-eval* false]
        (let [form (read r nil nil)
              ns-sym (when (and (seq? form) (= 'ns (first form)))
                       (second form))]
          (when (and (symbol? ns-sym)
                     (-> ns-sym meta :rho/module))
            (symbol (str ns-sym))))))))

(defn- module-namespaces
  [src-dirs]
  (->> src-dirs
       (map io/file)
       (filter #(.exists ^java.io.File %))
       (mapcat file-seq)
       (keep module-ns-from-file)
       distinct
       (sort-by str)
       vec))

(def ^:private config-name-re #"^config(\.[^/]+)?\.edn$")

(defn- config-resource-name?
  [name]
  (boolean (re-matches config-name-re name)))

(defn- config-names-in-dir
  [^File root-dir]
  (let [files (.listFiles root-dir)]
    (when files
      (->> files
           (filter #(.isFile ^File %))
           (map #(.getName ^File %))
           (filter config-resource-name?)))))

(defn- config-names-in-jar
  [^File jar-path]
  (with-open [jar-file (JarFile. jar-path)]
    (->> (cp/jar-entries jar-file)
         (map #(.getName ^java.util.jar.JarEntry %))
         (filter config-resource-name?))))

(defn- config-resource-names
  []
  (->> (cp/classpath-entries)
       (mapcat (fn [^File entry]
                 (cond
                   (.isDirectory entry)
                   (config-names-in-dir entry)

                   (and (.isFile entry)
                        (str/ends-with? (.getName entry) ".jar"))
                   (config-names-in-jar entry)

                   :else [])))
       distinct
       sort
       vec))

(defn- read-edn
  [resource]
  (with-open [r (io/reader resource)]
    (edn/read {:eof nil
               :default tagged-literal}
              (PushbackReader. r))))

(defn- ensure-map!
  [name data]
  (when-not (map? data)
    (throw (ex-info "Config resource must be a map."
                    {:resource name
                     :type (type data)})))
  data)

(defn- merge-root-configs!
  [class-dir src-dirs]
  (let [names (config-resource-names)
        local-roots (->> src-dirs
                         (map io/file)
                         (filter #(.exists ^File %))
                         (map #(.getCanonicalPath ^File %))
                         vec)]
    (when (seq names)
      (log-step (str "Merging root config resources (" (count names) " files)."))
      (doseq [name names]
        (let [ordered (config/ordered-resource-urls name local-roots)]
          (when (seq ordered)
            (log-step (str "Merging " name " from " (count ordered) " resources."))
            (doseq [resource ordered]
              (log-step (str " - " resource)))
            (let [merged (config/merge-resource-maps
                          ordered
                          (fn [resource]
                            (ensure-map! name (read-edn resource))))
                  out-file (io/file class-dir name)]
              (io/make-parents out-file)
              (spit out-file (pr-str merged))
              (log-step (str "Wrote merged " name " to " (.getPath out-file) ".")))))))))

(defn- write-module-index!
  [class-dir src-dirs]
  (let [indexed (cp/read-module-indices)
        modules (->> (concat (or indexed []) (module-namespaces src-dirs))
                     distinct
                     vec)
        out-file (io/file class-dir "rho" "modules.edn")]
    (io/make-parents out-file)
    (spit out-file (pr-str modules))
    (println "Wrote module index:" (.getPath out-file) "(" (count modules) "modules)")
    modules))

(defn uber
  "Build an uberjar while preserving source files so module discovery can scan
  `.clj`/`.cljc`. Options:
  `:class-dir`, `:uber-file`, `:src-dirs`, `:main`."
  [{:keys [class-dir uber-file src-dirs main]
    :or {class-dir default-class-dir
         uber-file default-uber-file
         src-dirs default-src-dirs
         main default-main}}]
  (log-step "Creating build basis from deps.edn.")
  (let [basis (b/create-basis {:project "deps.edn"})]
    (log-step "Cleaning target directory.")
    (b/delete {:path "target"})
    (log-step (str "Copying source directories to " class-dir "."))
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    (log-step "Writing module index.")
    (write-module-index! class-dir src-dirs)
    (log-step "Merging root config resources.")
    (merge-root-configs! class-dir src-dirs)
    (log-step (str "Compiling main namespace " main "."))
    (b/compile-clj {:basis basis
                    :class-dir class-dir
                    :ns-compile [main]})
    (log-step (str "Building uberjar at " uber-file "."))
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main main})
    (log-step "Uberjar build complete.")))
