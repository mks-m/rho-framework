(ns rho.htmx
  "HTMX integration helpers for Rho."
  (:require [clojure.string :as str]
            [rho.pedestal.html :as html]
            [rho.pedestal.public :as public]))

(def ^:private htmx-path "js/htmx.min.js")

(defn script-tag
  "Return a Hiccup script tag that loads the bundled `htmx.min.js`."
  ([] (script-tag {}))
  ([attrs]
   [:script (merge {:src (public/public-url htmx-path)
                    :defer true}
                   attrs)]))

(defn- header
  [request name]
  (let [headers (:headers request)
        lower (str/lower-case name)]
    (get headers lower)))

(defn htmx-request?
  "Return true when the request has `HX-Request: true`."
  [request]
  (= "true" (some-> (header request "HX-Request")
                    str/lower-case)))

(defn- vary-header
  [headers]
  (let [headers (or headers {})
        existing (get headers "Vary")
        existing-lower (some-> existing str/lower-case)
        include? (not (and existing-lower
                           (str/includes? existing-lower "hx-request")))]
    (assoc headers "Vary"
           (cond
             (and existing include?) (str existing ", HX-Request")
             existing existing
             :else "HX-Request"))))

(defn response
  "Return an HTML response for `view`, using a fragment template for HTMX
  requests and adding `Vary: HX-Request` when needed."
  ([request view] (response request {} view))
  ([request opts view]
   (let [htmx? (htmx-request? request)
         opts (if htmx? (assoc opts :template :fragment) opts)
         resp (html/response opts view)]
     (if htmx?
       (update resp :headers vary-header)
       resp))))
