(ns rho.core.classpath
  "Classpath utilities used by Rho for module discovery and config loading."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util.jar JarFile]))

(defn classpath-entries
  "Return a seq of all classpath entries (directories and jar files).
  Portable across OSes by using `java.io.File/pathSeparator`."
  []
  (let [cp  (System/getProperty "java.class.path")
        sep File/pathSeparator]
    (->> (str/split cp (re-pattern (java.util.regex.Pattern/quote sep)))
         (map io/file))))

(defn resource-urls
  "Return a seq of URLs for all classpath resources matching `name`."
  [name]
  (let [^ClassLoader loader (clojure.lang.RT/baseLoader)
        ^java.util.Enumeration urls (.getResources loader name)]
    (when urls
      (->> (enumeration-seq urls)
           (map io/as-url)
           seq))))

(defn read-module-indices
  "Read `rho/modules.edn` resources and return a vector of module symbols."
  []
  (when-let [resources (resource-urls "rho/modules.edn")]
    (->> resources
         (mapcat (fn [res]
                   (let [data (edn/read-string (slurp res))]
                     (when-not (sequential? data)
                       (throw (ex-info "Module index must be a sequential collection."
                                       {:resource (str res)})))
                     (map #(if (symbol? %) % (symbol (str %))) data))))
         distinct
         vec)))

(defn jar-entries
  "Returns a sequence of entries in the given JAR file."
  [^JarFile jar-file]
  (vec (enumeration-seq (.entries jar-file))))
