(ns katamari.roll.reader
  "Tools for reading Rollfiles and providing partial parsing and
  refreshing of a project/repo's dependency graph."
  {:authors ["Reid 'arrdem' McKenzie <me@arrdem.com>"]}
  (:require [me.raynes.fs :as fs]
            [clojure.java.io :as jio]
            [clojure.data :refer [diff]]
            [katamari.roll.specs :as rs]
            [clojure.spec.alpha :as s]
            [clojure.tools.deps.alpha.reader :refer [canonicalize-all-syms]]
            [pandect.algo.sha256 :as hash])
  (:import [java.io File]))

(defn find-rollfiles [root-file]
  (->> (file-seq root-file)
       (filter #(= "Rollfile" (.getName ^File %)))
       sort))

(defn read-all
  "Read all the available forms from the argument reader, returning a sequence of them."
  [rdr]
  (let [eof (Object.)]
    (take-while #(not= % eof)
                (repeatedly #(read rdr false eof)))))

(defn read-rollfile [{:keys [repo-root] :as config} ^File rollfile]
  (->> (read-all ((comp #(java.io.PushbackReader. %) jio/reader) rollfile))
       (mapv (fn [read-data]
               (if-let [explain (s/explain-data ::rs/def read-data)]
                 (throw (ex-info "Unable to parse rollfile!"
                                 (merge explain
                                        (meta read-data)
                                        {:file (.getCanonicalPath rollfile)
                                         :repo repo-root})))
                 (-> (s/conform ::rs/def read-data)
                     (update :paths (partial map (comp #(.getCanonicalPath %)
                                                       (partial fs/file (.getParent rollfile)))))
                     (update :deps canonicalize-all-syms)))))
       (map (juxt :name
                  #(assoc % :rollfile (.getCanonicalPath rollfile))))
       (into {})))

(defn error-on-conflicts [{:keys [name] :as l l-file :path} {r-file :path :as r}]
  (if-not (= l r)
    (throw (IllegalStateException.
            (format "Found conflicting definitions of %s in files %s, %s"
                    name l-file r-file)))
    r))

(defn targets-to-buildgraph [targets]
  {:targets targets
   :rollfiles (->> (vals targets)
                   (group-by :rollfile)
                   (map (fn [[f targets]]
                          [f {:mtime (.lastModified ^File (fs/file f))
                              :sha256sum (hash/sha256-file f)
                              :targets (mapv :name targets)}]))
                   (into {}))})

(defn compute-buildgraph
  "Given a repository, (re)compute the entire build graph non-incrementally."
  [{:keys [repo-root] :as config}]
  (let [root-file (fs/file repo-root)]
    (->> (find-rollfiles root-file)
         (map (partial read-rollfile config))
         (apply merge-with error-on-conflicts)
         targets-to-buildgraph)))

(defn- refresh*
  "Implementation detail of `refresh-whole-buildgraph` and `refresh-buildgraph-for-target`."
  [config
   {old-files :rollfiles
    old-targets :targets
    :as previous-graph}
   changed-rollfiles
   changed-paths]

  (let [changed-targets
        (mapcat #(get-in previous-graph [:rollfiles % :targets]) changed-paths)

        {new-targets :targets,
         new-files :rollfiles}
        (->> changed-rollfiles
             (map (partial read-rollfile config))
             (apply merge-with error-on-conflicts)
             targets-to-buildgraph)]
    {:targets
     (as-> (transient old-targets) %
       (reduce dissoc! % changed-targets)
       (reduce conj! % new-targets)
       (persistent! %))

     :rollfiles
     (as-> (transient old-files) %
       (reduce dissoc! % changed-paths)
       (reduce conj! % new-files)
       (persistent! %))

     ;; Overview of changes
     :diff
     (let [[added-targets deleted-targets updated-targets]
           (diff (set (keys new-targets)) (set changed-targets))

           [added-paths deleted-paths changed-paths]
           (diff (set (keys new-files)) (set changed-paths))]
       {:added-targets added-targets
        :deleted-targets deleted-targets
        :changed-targets changed-targets

        :added-rollfiles added-paths
        :changed-rolfiles changed-paths
        :deleted-rollfiles deleted-paths})}))

(defn refresh-buildgraph-for-changes
  "Given a repository and a previous build graph, refresh any targets
  whose definitions could have changed as observed via mtime or
  content hash."
  [{:keys [repo-root] :as config}
   {old-files :rollfiles
    old-targets :targets
    :as previous-graph}]
  (let [[changed-rollfiles changed-paths]
        (->> (find-rollfiles (fs/file repo-root))
             (keep (fn [^File rollfile]
                     (let [path (.getCanonicalPath rollfile)]
                       (if-let [old-meta (get old-files path)]
                         (let [{old-mtime :mtime
                                old-shasum :sha256sum} old-meta]
                           (when (or (not= old-mtime (.lastModified rollfile))
                                     (not= old-shasum (hash/sha256-file rollfile)))
                             [rollfile path]))
                         [rollfile path]))))
             ((juxt (partial map first) (partial map second))))]
    (refresh* config previous-graph changed-rollfiles changed-paths)))

(defn refresh-buildgraph-for-targets
  "Given a repository, a previous build graph, and a list of targets in
  that graph, refresh only the parts of the graph required to rebuild
  the selected targets."
  [config
   {old-files :rollfiles
    old-targets :targets
    :as previous-graph}
   refresh-targets]
  (let [refresh-paths (into #{} (map #(get-in previous-graph [:targets % :rollfile])) refresh-targets)]
    (refresh* config previous-graph (map fs/file refresh-paths) refresh-paths)))
