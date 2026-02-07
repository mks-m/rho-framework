(ns ^:rho/module todo
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [rho.htmx :as htmx]
            [rho.pedestal.html :as html]
            [rho.pedestal.public :as public]))

(defn- fetch-todos [db]
  (jdbc/execute! db
                 ["select id, title, completed_at, created_at from todos order by created_at desc"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- fetch-todo [db id]
  (first
   (jdbc/execute! db
                  ["select id, title, completed_at from todos where id = ?" id]
                  {:builder-fn rs/as-unqualified-lower-maps})))

(defn- request-id [request] (parse-long (get-in request [:path-params :id])))

(defn- todo-timestamp [value] (if (some? value) (str value) ""))

(defn- todo-item [{:keys [id title completed_at created_at]}]
  [:li {:class (str "todo-card" (when completed_at " is-complete"))
        :data-id id}
   [:div.todo-main
    [:form.toggle-form {:hx-post (str "/toggle/" id)
                        :hx-target "#todo-list"
                        :hx-swap "outerHTML"}
     [:button.toggle-btn {:type "submit"
                          :aria-label (if completed_at "Mark as incomplete" "Mark as complete")}]]
    [:div.todo-text
     [:div.todo-title {:id (str "todo-title-" id)} title]
     [:div.todo-meta (todo-timestamp created_at)]]]
   [:div {:class "todo-actions"}
    [:form.icon-form {:hx-post (str "/delete/" id)
                      :hx-target "#todo-list"
                      :hx-swap "outerHTML"}
     [:button.icon-button.is-delete {:type "submit" :title "Delete"} "Del"]]
    [:button.icon-button.is-edit {:title "Edit"
                                  :hx-get (str "/edit/" id)
                                  :hx-target (str "#todo-title-" id)
                                  :hx-swap "outerHTML"} "Edit"]]])

(defn- todo-list [todos]
  [:div#todo-list.todo-list
   (if (seq todos)
     [:ul {:class "todo-items"} (map todo-item todos)]
     [:p {:class "todo-empty"} "No todos yet."])])

(defn index [{{:keys [db]} :components}]
  (let [todos (fetch-todos db)]
    (html/response
     {:title "Todos"
      :head [[:link {:rel "stylesheet"
                     :href (public/public-url "todo.css")}]
             (htmx/script-tag)]
      :body [[:main.todo-shell
              [:header.page-header [:h1.page-title "Todo List"]]
              [:section.todo-toolbar
               [:form.todo-form {:hx-post "/add"
                                 :hx-target "#todo-list"
                                 :hx-swap "outerHTML"}
                [:label.sr-only {:for "todo-title"} "New todo"]
                [:input {:id "todo-title"
                         :name "title"
                         :type "text"
                         :placeholder "Add a new task"
                         :required true}]
                [:button {:type "submit"} "Add Task"]]]
              (todo-list todos)]]})))

(defn add [{{:keys [db]} :components :as request}]
  (when-let [title (some-> request :form-params :title str not-empty)]
    (jdbc/execute! db ["insert into todos (title) values (?)" title]))
  (htmx/response request {:body (todo-list (fetch-todos db))}))

(defn delete [{{:keys [db]} :components :as request}]
  (when-let [id (request-id request)]
    (jdbc/execute! db ["delete from todos where id = ?" id]))
  (htmx/response request {:body (todo-list (fetch-todos db))}))

(defn toggle [{{:keys [db]} :components :as request}]
  (when-let [id (request-id request)]
    (jdbc/execute! db
                   ["update todos set completed_at = case when completed_at is null then current_timestamp else null end, updated_at = current_timestamp where id = ?" id]))
  (htmx/response request {:body (todo-list (fetch-todos db))}))

(defn edit-get [{{:keys [db]} :components :as request}]
  (if-let [todo (some->> (request-id request) (fetch-todo db))]
    (htmx/response
     request
     {:body (let [{:keys [id title]} todo]
              [:form.edit-form {:id (str "todo-title-" id)
                                :hx-post (str "/edit/" id)
                                :hx-target "#todo-list"
                                :hx-swap "outerHTML"}
               [:label.sr-only {:for (str "todo-edit-" id)} "Edit todo"]
               [:input {:id (str "todo-edit-" id)
                        :name "title"
                        :required true
                        :value title}]
               [:button.btn-save {:type "submit"} "Save"]])})
    {:status 404
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Todo not found."}))

(defn edit-post [{{:keys [db]} :components :as request}]
  (when-let [id (request-id request)]
    (when-let [title (some-> request :form-params :title str not-empty)]
      (jdbc/execute! db ["update todos set title = ?, updated_at = current_timestamp where id = ?" title id])))
  (htmx/response request {:body (todo-list (fetch-todos db))}))

(def module
  {:rho-pedestal/routes [["/" :get #'index]
                         ["/add" :post #'add]
                         ["/delete/:id" :post #'delete]
                         ["/toggle/:id" :post #'toggle]
                         ["/edit/:id" :get #'edit-get]
                         ["/edit/:id" :post #'edit-post]]})
