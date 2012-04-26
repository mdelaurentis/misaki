(ns misaki.core
  "misaki: Jekyll inspired static site generator in Clojure"
  (:use
    [misaki template config]
    [misaki.util file code seq]
    [hiccup.core :only [html]]
    [hiccup.page-helpers :only [html5 xhtml html4]]
    [cljs.closure :only [build]])
  (:require
    conv
    [clojure.string :as str]
    [clojure.java.io :as io])
  (:import [java.io File]))

(declare generate-html)

;; ### Utilities

; =escape-content
(defn escape-content
  "Escape content"
  [content]
  (-> content
      (str/replace #"&" "&amp;")
      (str/replace #"\"" "&quot;")
      (str/replace #"<" "&lt;")
      (str/replace #">" "&gt;")))

;; ## Templates

; =get-post-options
(defn get-post-options
  "Get post's template options from post file(java.io.File)"
  [#^File file]
  (->> file io/reader slurp parse-template-options))

; =get-content
(defn get-content
  "Get post content without layout"
  [#^File file]
  (html (generate-html (file->template-name file)
                       :allow-layout? false)))

; =get-posts
(defn get-posts
  "Get posts data from *post-dir* directory.
  Content data is delayed."
  [& {:keys [tag]}]
  (for [file  (filter-extension ".clj" (find-files *post-dir*))
        :let  [option (get-post-options file)
               tagset (set (map :name (get option :tag [])))]
        :when (or (nil? tag)
                  (every? #(contains? tagset %) tag))]
    (assoc
      option
      :file file
      :url  (make-post-url file)
      :date (get-date-from-file file)
      :lazy-content (delay (escape-content (get-content file))))))

;; ## Tags

; =get-unfiltered-tags
(defn get-unfiltered-tags
  "Get unfiltered all tags from post list."
  [posts]
  (remove nil? (mapcat :tag posts)))

; =get-counted-tags
(defn get-counted-tags
  "Get counted tags from post list."
  [posts]
  (let [tag-group (group-by :name (get-unfiltered-tags posts))]
    (sort-alphabetically
      :name (map #(assoc (first %) :count (count %)) (vals tag-group)))))

; =get-tags
(defn get-tags
  "Get all tags from post list."
  ([] (get-tags (get-posts)))
  ([posts]
   (->> (get-unfiltered-tags posts)
        (sort-alphabetically :name)
        distinct)))

;; ## S-exp HTML Generater

; =generate-html
(defn generate-html
  "Generate HTML from template."
  [tmpl-name & {:keys [allow-layout?] :or {allow-layout? true}}]
  (let [filename   (str *template-dir* tmpl-name)
        tmpl-fn    (load-template filename allow-layout?)
        posts      (sort-by-date (get-posts))
        site-data  (assoc *site* :posts posts
                                 :tags  (get-counted-tags posts)
                                 :date  (get-date-from-file (io/file filename)))
        empty-data (with-meta '("") site-data)]

    (apply-template tmpl-fn empty-data)))

; =generate-tag-html
(defn generate-tag-html
  "Generate tag HTML from *tag-layout*."
  [tag-name]
  (let [tmpl-fn (load-template *tag-layout*)
        site-data (assoc *site* :posts (sort-by-date (get-posts :tag [tag-name]))
                                :tag-name tag-name)
        empty-data (with-meta '("") site-data)]
    (apply-template tmpl-fn empty-data)))

;; ## HTML Compiler

; =get-compile-fn
(defn get-compile-fn
  "Get hiccup functon to compile sexp."
  [fmt]
  (case fmt
    "html5" #(html5 {:lang *lang*} %)
    "xhtml" #(xhtml {:lang *lang*} %)
    "html4" #(html4 %)
    #(html %)))

; =compile*
(defn- compile* [filename data]
  (let [compile-fn (-> data meta :format get-compile-fn)]
    (write-data (str *public-dir* filename)
                (compile-fn data))
    true))

; =compile-tag
(defn compile-tag
  "Compile a tag page.
  return true if compile succeeded."
  [tag-name]
  (try
    (compile* (make-tag-output-filename tag-name)
              (generate-tag-html tag-name))
    (catch Exception e (.printStackTrace e) false)))

; =compile-template
(defn compile-template
  "Compile a specified template, and write compiled data to *public-dir*.
  return true if compile succeeded."
  [tmpl-name]
  (try
    (compile* (make-template-output-filename tmpl-name)
              (generate-html tmpl-name))
    (catch Exception e (.printStackTrace e) false)))

; =compile-clojurescripts
(defn compile-clojurescripts
  "Compile clojurescripts.
  return true if compile succeeded."
  []
  (try
    ; make directory if not exists
    (make-directories (:output-to *cljs-compile-options*))
    ; build clojurescript
    (build (:src-dir *cljs-compile-options*)
           *cljs-compile-options*)
    true
    (catch Exception e (.printStackTrace e) false)))

; =compile-all-tags
(defn compile-all-tags
  "Compile all tag page.
  return true if all compile succeeded."
  []
  (every? #(compile-tag (:name %)) (get-tags)))

; =get-template-files
(defn get-template-files
  "Get all template files(java.io.File) from *template-dir*."
  []
  (remove layout-file?
          (filter-extension ".clj" (find-files *template-dir*))))

; =compile-all-templates
(defn compile-all-templates
  "Compile all template files.
  return true if all compile succeeded."
  []
  (every? #(compile-template %)
          (map file->template-name (get-template-files))))

