(ns fractal-engine.lineage
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.time :as time]))

(def lineage-version 1)

(defn path-string [dir]
  (str (artifacts/path dir)))

(defn session-handle [dir]
  (str "session:" (path-string dir)))

(defn turn-handle [dir turn-id]
  (format "turn:%s#turn-%04d" (path-string dir) (long turn-id)))

(defn eval-handle [dir eval-id]
  (format "eval:%s#eval-%04d" (path-string dir) (long eval-id)))

(defn call-handle [dir call-id]
  (format "call:%s#call-%04d" (path-string dir) (long call-id)))

(defn child-handle [dir child-rel]
  (str "child:" (path-string (artifacts/path dir child-rel))))

(defn final-handle [dir]
  (str "final:" (path-string dir) "#latest-final"))

(defn parse-handle [handle]
  (let [[kind rest] (str/split (str handle) #":" 2)
        [path fragment] (str/split (or rest "") #"#" 2)]
    {:handle/kind (keyword kind)
     :handle/path path
     :handle/fragment fragment}))

(defn root-lineage [dir session-id kind]
  {:lineage/version lineage-version
   :lineage/session-id session-id
   :lineage/kind kind
   :lineage/source nil
   :lineage/parents []
   :lineage/created-at (time/now-str)
   :lineage/events []})

(defn write-lineage! [dir lineage]
  (artifacts/write-edn-file! (artifacts/path dir "lineage.edn") lineage))

(defn read-lineage [dir]
  (artifacts/read-edn-file (artifacts/path dir "lineage.edn") nil))

(defn append-lineage-event! [dir event]
  (let [lineage (or (read-lineage dir)
                    (root-lineage dir nil :unknown))
        event' (assoc event :event/at (or (:event/at event) (time/now-str)))]
    (write-lineage! dir (update lineage :lineage/events (fnil conj []) event'))))
