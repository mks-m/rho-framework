# rho-ar

ActiveRecord-style helpers for Rho using Clojure records and protocols.

## Example usage

```clj
(ns my.app.todos
  (:require [rho.ar :as ar]
            [rho.pedestal.html :as html]))

(ar/defmodel Todo {:table :todos
                   :columns [:id :title :completed_at :created_at]})

(defn index [{{:keys [db]} :components}]
  (let [adapter (ar/next-jdbc-adapter db)
        todos (ar/all adapter todo-model {:order-by [[:created_at :desc]]})]
    (html/response
     {:title "Todos"
      :body [[:main
              [:h1 "Todos"]
              [:ul (for [{:keys [id title]} todos]
                     [:li {:data-id id} title])]]]})))

(defn add [{{:keys [db]} :components :as request}]
  (let [adapter (ar/next-jdbc-adapter db)
        title (some-> request :form-params :title str not-empty)]
    (when title
      (ar/create! adapter todo-model {:title title}))
    {:status 303
     :headers {"Location" "/"}}))
```

### Notes

- `next-jdbc-adapter` works with the `rho-sqlite` datasource component (`:db`).
- `defmodel` defines a record type and a `<name>-model` var (e.g. `todo-model`).
- Use `ar/find`, `ar/where`, `ar/update!`, `ar/delete!`, and `ar/save!` for basic CRUD.
