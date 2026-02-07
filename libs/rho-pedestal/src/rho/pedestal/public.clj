(ns rho.pedestal.public
  "Static public asset discovery and routing for Rho Pedestal apps."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [rho.core.classpath :as cp])
  (:import [java.net URLConnection]
           [java.util.jar JarFile]))

(defn- normalize-path
  [path]
  (-> path
      (str/replace #"^/+" "")
      (str/replace #"\.\." "")))

(defn public-url
  "Return a normalized public URL for a resource path under `public/`."
  [path]
  (str "/" (normalize-path path)))

(defn- public-resource
  [path]
  (io/resource (str "public/" (normalize-path path))))

(defn- public-files-in-dir
  [^java.io.File root-dir]
  (let [public-dir (io/file root-dir "public")]
    (when (.isDirectory public-dir)
      (let [files (->> (file-seq public-dir)
                       (filter #(.isFile ^java.io.File %))
                       vec)]
        (log/info "Public directory scan:" (.getPath public-dir) "-" (count files) "files")
        (map (fn [^java.io.File f]
               (-> (str (.relativize (.toURI public-dir) (.toURI f)))
                   normalize-path))
             files)))))

(defn- public-files-in-jar
  [^java.io.File jar-path]
  (with-open [jar-file (JarFile. jar-path)]
    (let [entries (cp/jar-entries jar-file)
          public-entries (->> entries
                              (map #(.getName ^java.util.jar.JarEntry %))
                              (filter #(and (str/starts-with? % "public/")
                                            (not (str/ends-with? % "/")))))]
      (log/debug "Public JAR scan:" (.getPath jar-path)
                 "-" (count entries) "entries,"
                 (count public-entries) "public files")
      (->> public-entries
           (map #(subs % (count "public/")))
           (map normalize-path)))))

(defn discover-public-paths
  "Scan the classpath for files under `public/` and return a sorted vector
  of normalized paths."
  []
  (let [start (System/nanoTime)
        entries (vec (cp/classpath-entries))
        dir-count (count (filter #(.isDirectory ^java.io.File %) entries))
        jar-count (count (filter #(and (.isFile ^java.io.File %)
                                       (str/ends-with? (.getName ^java.io.File %) ".jar"))
                                 entries))]
    (log/debug "Discovering public paths from classpath entries:"
               (count entries) "(" dir-count "dirs," jar-count "jars)")
    (let [paths (->> entries
                     (mapcat (fn [^java.io.File entry]
                               (cond
                                 (.isDirectory entry) (public-files-in-dir entry)

                                 (and (.isFile entry)
                                      (str/ends-with? (.getName entry) ".jar"))
                                 (public-files-in-jar entry)

                                 :else [])))
                     (remove str/blank?)
                     distinct
                     sort
                     vec)
          elapsed-ms (/ (double (- (System/nanoTime) start)) 1000000.0)]
      (log/info "Discovered" (count paths) "public paths in"
                (format "%.2fms" elapsed-ms))
      paths)))

(defn- content-type
  [path]
  (or (URLConnection/guessContentTypeFromName path)
      "application/octet-stream"))

(defn- handle-public
  [{:keys [path-params path-info uri]}]
  (let [raw-path (or (:path path-params) path-info uri "")
        path (normalize-path raw-path)
        resource (public-resource path)]
    (if resource
      {:status 200
       :headers {"Content-Type" (content-type path)}
       :body (io/input-stream resource)}
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Not Found"})))

(defn routes
  "Build Pedestal routes for the given `public-paths`."
  [public-paths]
  (->> (or public-paths [])
       (remove str/blank?)
       (map (fn [path]
              [(str "/" path) :get handle-public
               :route-name (keyword "public" path)]))
       set))
