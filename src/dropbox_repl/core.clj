(ns dropbox-repl.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [clj-http.client :refer [post]]
            [cheshire.core :refer [parse-string generate-string]]
            [clojure.walk :refer [keywordize-keys]])
  (:gen-class))

;;; Follow the instructions in the Github page on how
;;; to generate an access token for your Dropbox REPL.
;;; Then put the value of your token in the profiles.clj.
(def access-token (env :access-token))

(defn- merge*
  "Deep-merge a list of maps."
  [& vals]
  (if (every? map? vals)
    (apply merge-with merge* vals)
    (last vals)))

(def ^:private default-req
  {:coerce :always ; exceptions will also be parsed as JSON
   :as :json
   :throw-entire-message? true
   :headers {:authorization (str "Bearer " access-token)}})

(defn- rpc-request
  ([url] (rpc-request url {}))
  ([url params]
   (let [req {:content-type :json
              :body (if (seq params) (generate-string params) "null")}]
     (post url (merge* default-req req)))))

(defn- content-upload-request [url file params]
  (let [req {:content-type "application/octet-stream"
             :body file
             :headers {:Dropbox-API-Arg (generate-string params)}}]
    (post url (merge* default-req req))))

;; REVIEW: I'm pretty sure the input stream from the response (:body resp)
;; will be closed after it's done reading but I should probably double-check.
;; If I include the input stream in the with-open, it throws an exception,
;; probably because the stream is closed twice.
(defn- content-download-request [path dest-dir]
  (let [url "https://content.dropboxapi.com/2/files/download"
        req {:as :stream
             :headers {:Dropbox-API-Arg (generate-string {:path path})}}
        resp (post url (merge* default-req req))
        name (-> resp (get-in [:headers "dropbox-api-result"]) parse-string keywordize-keys :name)
        outfile (io/file dest-dir name)]
    (with-open [out (io/output-stream outfile)]
      (io/copy (:body resp) out))
    outfile))

;;;;;;;;;;;;;;;;;;;; USERS-RELATED ENDPOINTS ;;;;;;;;;;;;;;;;;;;;;;
;; https://www.dropbox.com/developers/documentation/http/documentation#users-get_account

(defn get-current-account []
  (:body (rpc-request "https://api.dropboxapi.com/2/users/get_current_account")))

(defn get-account [account-id]
  (:body (rpc-request "https://api.dropboxapi.com/2/users/get_account"
                       {:account_id account-id})))

(defn get-account-batch [account-ids]
  (:body (rpc-request "https://api.dropboxapi.com/2/users/get_account_batch"
                       {:account_ids account-ids})))

(defn get-space-usage []
  (:body (rpc-request "https://api.dropboxapi.com/2/users/get_space_usage")))

;;;;;;;;;;;;;;;;;;;; FILES-RELATED ENDPOINTS ;;;;;;;;;;;;;;;;;;;;;;
;; https://www.dropbox.com/developers/documentation/http/documentation#files-copy

(defn- offsets [files]
  (loop [lengths (map #(.length %) files)
         offsets []
         cur-offset 0]
    (if (not (seq lengths))
      offsets
      (recur (rest lengths)
             (conj offsets cur-offset)
             (+' cur-offset (first lengths))))))

(defn- upload-offsets [files]
  (partition 2 (interleave (offsets files) files)))

(defn- sanitize-for-list [path]
  (cond (= "/" path) ""
        (= "" path) ""
        (not (str/starts-with? path "/")) (str "/" path)
        :else path))

(defn list-folder
  ([path] (list-folder path {}) )
  ([path optional]
   (let [params (merge {:path (sanitize-for-list path)} optional)]
     (:body (rpc-request "https://api.dropboxapi.com/2/files/list_folder"
                          params)))))

(defn list-folder-continue [cursor]
  (:body (rpc-request "https://api.dropboxapi.com/2/files/list_folder/continue"
                       {:cursor cursor})))

(defn list-entries-lazy
  "Lazily returns the entries given a path. The sequence
  is terminated by nil so to get all of the entries you
  would do (take-while some? (list-entries-lazy path))."
  ([path] (list-entries-lazy path {:recursive true}))
  ([path optional]
   (let [init-results (list-folder path optional)
         nil-entries {:entries [nil]}]
     (mapcat :entries (iterate (fn [r]
                                 (if (:has_more r)
                                   (list-folder-continue (:cursor r))
                                   nil-entries))
                               init-results)))))

(defn list-entries
  ([path] (list-entries path {:recursive true}))
  ([path optional]
    (take-while some? (list-entries-lazy path optional))))

(defn copy [from-path to-path]
  (:body (rpc-request "https://api.dropboxapi.com/2/files/copy"
                       {:from_path from-path
                        :to_path   to-path})))

(defn create-folder [path]
  (:body (rpc-request "https://api.dropboxapi.com/2/files/create_folder"
                       {:path path})))

(defn delete [path]
  (:body (rpc-request "https://api.dropboxapi.com/2/files/delete"
                       {:path path})))

(defn move [from-path to-path]
  (:body (rpc-request "https://api.dropboxapi.com/2/files/move"
                       {:from_path from-path
                        :to_path   to-path})))

(defn search
  ([path query] (search path query {}))
  ([path query optional]
   (let [params (merge {:path path :query query} optional)]
     (:body (rpc-request "https://api.dropboxapi.com/2/files/search"
                          params)))))

(defn get-metadata
  ([path] (get-metadata path {}))
  ([path optional]
   (let [params (merge {:path path} optional)]
     (:body (rpc-request "https://api.dropboxapi.com/2/files/get_metadata"
                          params)))))

(defn upload [file path]
  (:body (content-upload-request
           "https://content.dropboxapi.com/2/files/upload"
           (io/as-file file)
           {:path path})))

(defn download [path dest-dir]
  (content-download-request path dest-dir))

(defn upload-start [file]
  (:body (content-upload-request
           "https://content.dropboxapi.com/2/files/upload_session/start"
           (io/as-file file)
           {})))

(defn upload-append [file session-id offset]
  (content-upload-request
    "https://content.dropboxapi.com/2/files/upload_session/append_v2"
    (io/as-file file)
    {:cursor {:session_id session-id :offset offset}}))

(defn upload-finish [file session-id offset path optional]
  (:body (content-upload-request
           "https://content.dropboxapi.com/2/files/upload_session/finish"
           (io/as-file file)
           (merge* {:cursor {:session_id session-id :offset offset}
                    :commit {:path path}}
                   optional))))

;;;;;;;;;;;;;;;;;;;; SHARING-RELATED ENDPOINTS ;;;;;;;;;;;;;;;;;;;;;;
;; https://www.dropbox.com/developers/documentation/http/documentation#sharing-add_folder_member

(defn get-shared-links
  ([] (get-shared-links ""))
  ([path] (:body (rpc-request "https://api.dropboxapi.com/2/sharing/get_shared_links" {:path path}))))

(defn revoke-shared-link [url]
  (rpc-request "https://api.dropboxapi.com/2/sharing/revoke_shared_link"
                {:url url}))

(defn get-shared-link-metadata
  ([url] (get-shared-link-metadata url {}))
  ([url optional]
   (:body (rpc-request "https://api.dropboxapi.com/2/sharing/get_shared_link_metadata"
                        (merge {:url url} optional)))))

;;;;;;;;;;;;;;;;;;;; USEFUL FNS ;;;;;;;;;;;;;;;;;;;;;;
(defn tag= [tag]
  (fn [e]
    (= (:.tag e) (name tag))))

(defn name-from-path [path]
  (last (str/split path #"/")))


