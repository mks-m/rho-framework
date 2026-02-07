(ns rho.pedestal.html
  "HTML rendering helpers for Rho using Hiccup templates."
  (:require [hiccup.core :as hiccup]
            [rho.pedestal.public :as public]))

(def ^:private default-title "Rho App")

(defn- normalize-nodes [nodes]
  (cond
    (nil? nodes) []
    (sequential? nodes) nodes
    :else [nodes]))

(defn- hiccup-element?
  [value]
  (and (vector? value)
       (or (keyword? (first value))
           (symbol? (first value))
           (string? (first value)))))

(defn default-template
  "Render a full HTML document from a view map with optional `:title`,
  `:head`, `:body`, `:html-attrs`, and `:body-attrs`."
  [{:keys [title head body html-attrs body-attrs]}]
  (let [head-nodes (normalize-nodes head)
        body-nodes (normalize-nodes body)]
    [:html (or html-attrs {})
     (into [:head
            [:meta {:charset "utf-8"}]
            [:meta {:name "viewport"
                    :content "width=device-width, initial-scale=1"}]
            [:title (or title default-title)]
            [:link {:rel "stylesheet"
                    :href (public/public-url "css/site.css")}]]
           head-nodes)
     (into [:body (or body-attrs {})] body-nodes)]))

(defn- fragment-template
  [{:keys [body]}]
  (cond
    (hiccup-element? body) body
    (sequential? body) (seq body)
    (nil? body) []
    :else body))

(def default-templates
  {:default default-template
   :fragment fragment-template})

(defn- resolve-template
  [opts view]
  (let [template-key (or (:template opts) (:template view) :default)
        templates (merge default-templates (:templates opts))]
    (cond
      (fn? template-key) template-key
      (keyword? template-key) (or (get templates template-key)
                                  (get templates :default))
      :else template-key)))

(defn render
  "Render a view to an HTML string. Supports `:template` or `:templates`
  overrides in `opts`."
  ([view] (render {} view))
  ([opts view]
   (let [template-fn (resolve-template opts view)
         model (if (map? view) view {:body view})]
     (hiccup/html (template-fn model)))))

(defn response
  "Return a Ring-style HTML response for the given view and options."
  ([view] (response {} view))
  ([opts view]
   {:status 200
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body (render opts view)}))
