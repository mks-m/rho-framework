(ns rho.core.jarlister
  "JAR inspection helpers for module discovery."
  (:require [clojure.string :as str]
            [rho.core.classpath :as cp])
  (:import [java.util.jar JarFile]
           [java.io File]))

(defn list-jar-namespaces
  "Lists all namespaces in the given JAR file that are under the specified base namespace."
  [^File jar-path base-ns]
  (with-open [jar-file (JarFile. jar-path)]
    (let [base-path (str/replace (name base-ns) "." "/")
          entries   (cp/jar-entries jar-file)]
      (->> entries
           (map #(.getName ^java.util.jar.JarEntry %))
           (filter #(and (str/starts-with? % base-path)
                         (re-find #"\.(clj|cljc)$" %)))
           (map #(-> %
                     (str/replace #"\.(clj|cljc)$" "")
                     (str/replace "/" ".")))
           (map symbol)))))
