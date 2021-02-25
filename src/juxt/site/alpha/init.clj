;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.init
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [crux.api :as crux]
   [crypto.password.bcrypt :as password]
   [jsonista.core :as json]
   [juxt.site.alpha.util :as util])
  (:import (java.io DataInputStream FileInputStream)))

(alias 'http (create-ns 'juxt.http.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'site (create-ns 'juxt.site.alpha))

(defn slurp-file-as-bytes [dir f]
  (let [f (io/file dir f)]
    (.readAllBytes (FileInputStream. f))))

(defn put! [crux-node & ms]
  (->>
   (crux/submit-tx
    crux-node
    (for [m ms]
      [:crux.tx/put m]))
   (crux/await-tx crux-node)))

(defn create-webmaster!
  "Create the webmaster."
  [crux-node pw]
  (when pw
    (put!
     crux-node
     {:crux.db/id "https://home.juxt.site/_site/users/webmaster"
      ::http/methods #{:get :head :options}
      ::http/representations []
      ::pass/password-hash!! (password/encrypt pw)}

     ;; Add rule that allows the webmaster to do everything, at least during the
     ;; bootstrap phase of a deployment. This can be deleted after the initial
     ;; users/roles have been populated, if required.
     {:crux.db/id "https://home.juxt.site/_site/rules/webmaster-allow-read-all"
      :description "The webmaster has read access to everything"
      ::site/type "Rule"
      ::pass/target '[[subject :juxt.pass.alpha/username "webmaster"]]
      ::pass/effect ::pass/allow
      ::pass/allow-methods #{:get :head :options}})))

(defn allow-public-access-to-public-resources!
  "Resources classified as PUBLIC should be readable (but not writable). For
  example, a login page needs to be a PUBLIC resource."
  [crux-node]
  (put!
   crux-node
   {:crux.db/id "https://home.juxt.site/_site/rules/public-resources"
    ::site/type "Rule"
    ::site/description "PUBLIC resources are accessible to GET"
    ::pass/target '[[request :request-method #{:get :head :options}]
                    [resource ::pass/classification "PUBLIC"]]
    ::pass/effect ::pass/allow}))

(defn add-home-page! [crux-node]
  (put!
   crux-node
   {:crux.db/id "https://home.juxt.site/index.html"
    ::http/methods #{:get :head :options}
    ::http/representations
    [{::http/content-type "text/html;charset=utf-8"
      ::site/body-generator :juxt.site.alpha.home/home-page}]
    ;; The login page must have a classification of PUBLIC to be accessible.
    ::pass/classification "PUBLIC"}))

(defn add-home-redirect!
  "Redirect from / to /index.html"
  [crux-node]
  (put!
   crux-node
   {:crux.db/id "https://home.juxt.site/"
    ::http/redirect "/index.html"}))

(defn add-login! [crux-node]
  (put!
   crux-node
   {:crux.db/id "https://home.juxt.site/_site/login"
    ::http/methods #{:get :head :options :post}
    ::http/representations
    [{::http/content-type "text/html;charset=utf-8"
      ::http/content (slurp (io/resource "juxt/pass/alpha/login.html"))}]
    ;; The login page must have a classification of PUBLIC to be accessible.
    ::pass/classification "PUBLIC"
    ::http/acceptable "application/x-www-form-urlencoded"
    ::site/purpose ::site/post-login-credentials
    ::pass/expires-in (* 3600 24 30)}

   ;; Along with a special rule to allow anyone to POST to it.
   ;; TODO: Making the post handler PUBLIC should be OK
   {:crux.db/id "https://home.juxt.site/_site/rules/anyone-can-post-login-credentials"
    ::site/type "Rule"
    ::site/description "The login POST handler must be accessible by all"
    ::pass/target '[[request :request-method #{:post}]
                    [resource ::site/purpose ::site/post-login-credentials]]
    ::pass/effect ::pass/allow}

   ;; We've got a login, we should have a logout too.
   {:crux.db/id "https://home.juxt.site/_site/logout"
    ::http/methods #{:post}
    ::http/acceptable "application/x-www-form-urlencoded"
    ::site/purpose ::site/logout}

   {:crux.db/id "https://home.juxt.site/_site/rules/those-logged-in-can-logout"
    ::site/type "Rule"
    ::site/description "The logout POST handler must be accessible by those logged in"
    ::pass/target '[[subject ::pass/username]
                    [resource ::site/purpose ::site/logout]]
    ::pass/effect ::pass/allow}))

(defn add-tailwind-stylesheets! [crux-node dir]
  (put!
   crux-node
   {:crux.db/id "https://home.juxt.site/css/tailwind/styles.css"
    ::http/methods #{:get :head :option}
    ::http/representations
    [(let [bytes (slurp-file-as-bytes dir "styles.css")]
       {::http/content-type "text/css"
        ::http/content-length (count bytes)
        ::http/body bytes})
     (let [bytes (slurp-file-as-bytes dir "styles.css.gz")]
       {::http/content-type "text/css"
        ::http/content-encoding "gzip"
        ::http/content-length (count bytes)
        ::http/body bytes})
     (let [bytes (slurp-file-as-bytes dir "styles.css.br")]
       {::http/content-type "text/css"
        ::http/content-encoding "br"
        ::http/content-length (count bytes)
        ::http/body bytes})]
    ;; If we want to use these stylesheets with public resources, they'll need
    ;; to be PUBLIC too.
    ::pass/classification "PUBLIC"}))

(defn add-swagger-ui!
  "After the webmaster has logged in, they may want to add new users. For this,
  they need at least need access to the Swagger UI. These function requires that
  a webjar containing the Swagger UI is on the classpath."
  [crux-node]
  (let [jarpath
        (some
         #(re-matches #".*swagger-ui.*" %)
         (str/split (System/getProperty "java.class.path") #":"))
        fl (io/file jarpath)
        jar (java.util.jar.JarFile. fl)]
    (doseq
        [je (enumeration-seq (.entries jar))
         :let [nm (.getRealName je)
               [_ suffix] (re-matches #".*\.(.*)" nm)
               size (.getSize je)
               path (second
                     (re-matches #"META-INF/resources/webjars/swagger-ui/[0-9.]+/(.*)"
                                 nm))]
         :when path
         :let [uri (format "https://home.juxt.site/_site/swagger-ui/%s" path)]
         ;; TODO: Do we still need this to defend against 0 size web entries?
         :when (pos? size)]
        (let [body (.readAllBytes (.getInputStream jar je))]
          (put!
           crux-node
           {:crux.db/id uri
            ::http/methods #{:get :head :options}
            ::http/representations [{::http/content-type (get util/mime-types suffix "application/octet-stream")
                                     ::http/last-modified (java.util.Date. (.getTime je))
                                     ::http/content-length (count body)
                                     ::http/content-location uri
                                     ::http/body body}]})))))

(defn add-site-api!
  "Add the Site API"
  [crux-node]
  (let [res (io/resource "juxt/site/alpha/openapi.edn")
        json (json/write-value-as-string (edn/read-string (slurp res)))
        openapi (json/read-value json)
        bytes (.getBytes json "UTF-8")]
    (put!
     crux-node
     {:crux.db/id "https://home.juxt.site/_site/apis/site/openapi.json"
      ::http/methods #{:get :head :options}
      ::http/representations
      [{::http/content-type "application/vnd.oai.openapi+json;version=3.0.2"
        ;; TODO: Get last modified from resource - check JDK javadocs
        ;;::http/last-modified (java.util.Date. (.lastModified f))
        ::http/content-length (count bytes)
        ::http/body bytes}]
      ::site/type "OpenAPI"
      :juxt.apex.alpha/openapi openapi})))

(defn add-favicon! [crux-node favicon]
  (put!
   crux-node
   (let [bytes (.readAllBytes (io/input-stream favicon))]
     {:crux.db/id "https://home.juxt.site/favicon.ico"
      ::pass/classification "PUBLIC"
      ::http/methods #{:get :head :options}
      ::http/representations
      [{::http/content-type "image/x-icon"
        ::http/content-length (count bytes)
        ::http/body bytes}]})))

(defn add-webmaster-home-page! [crux-node]
  (put!
   crux-node
   {:crux.db/id "https://home.juxt.site/~webmaster/"
    :juxt.site.alpha.home/owner "https://home.juxt.site/_site/users/webmaster"
    ::http/methods #{:get :head :options}
    ::http/representations
    [{::http/content-type "text/html;charset=utf-8"
      ::site/body-generator :juxt.site.alpha.home/user-home-page}]}))

(defn add-openid-token-endpoint! [crux-node]
  (let [token-endpoint "https://home.juxt.site/_site/token"
         grant-types #{"client_credentials"}]
     (put!
      crux-node
      {:crux.db/id token-endpoint
       ::http/methods #{:post}
       ::http/acceptable "application/x-www-form-urlencoded"
       ::pass/expires-in 60})

     (let [content
           (str
            (json/write-value-as-string
             {"issuer" "https://juxt.site" ; draft
              "token_endpoint" token-endpoint
              "token_endpoint_auth_methods_supported" ["client_secret_basic"]
              "grant_types_supported" (vec grant-types)}
             (json/object-mapper
              {:pretty true}))
            "\r\n")]
       (put!
        crux-node
        {:crux.db/id "https://home.juxt.site/.well-known/openid-configuration"

         ;; OpenID Connect Discovery documents are publically available
         ::pass/classification "PUBLIC"

         ::http/methods #{:get :head :options}
         ::http/representations
         [{::http/content-type "application/json"
           ::http/last-modified (java.util.Date.)
           ::http/etag (subs (util/hexdigest (.getBytes content)) 0 32)
           ::http/content content}]}))))

(defn init-db!
  "Initialize the database. You usually call this as part of setting up a new Site
  instance. It's generally idempotent, so if you call this by mistake so you
  won't damage anything except resetting the webmaster's password."
  ([crux-node webmaster-password]
   (init-db! crux-node webmaster-password {}))
  ([crux-node webmaster-password {:keys [style-dir favicon]}]

   (println "Initializing Site Database")
   (create-webmaster! crux-node webmaster-password)
   (allow-public-access-to-public-resources! crux-node)
   (add-home-page! crux-node)
   (add-home-redirect! crux-node)
   (add-login! crux-node)
   ;; The login form is styled with Tailwind CSS
   (add-tailwind-stylesheets! crux-node (or style-dir "style/target"))
   (add-swagger-ui! crux-node)
   (add-site-api! crux-node)
   (add-favicon! crux-node (io/resource "juxt/favicon.ico"))

   #_(put
      crux-node
      {:crux.db/id "https://home.juxt.site/_site/pass/rules/users-can-post-their-own-home-pages"
       ::site/type "Rule"
       ::pass/target '[[subject ::pass/user user]
                       [resource ::owner user]]
       ::pass/effect ::pass/allow})

   ;; Authentication resources
   (add-openid-token-endpoint! crux-node)
   (add-webmaster-home-page! crux-node)))
