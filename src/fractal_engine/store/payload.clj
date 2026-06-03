(ns fractal-engine.store.payload
  "Canonical payload byte storage.

  Facts identify payloads; BlobStore owns their bytes. This namespace is the
  small seam between fact rows and content-addressed payload refs."
  (:require [fractal-engine.blob-store :as blob]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.time :as time]))

(defn blob-store [root]
  (blob/file-store (store-io/path root)))

(defn blob-ref? [x]
  (and (map? x) (:blob/id x) (:blob/sha256 x) (:blob/key x)))

(defn blob-lookup [ref]
  (when-let [id (:blob/id ref)]
    [:blob/id id]))

(defn blob-entity
  [ref]
  (when (:blob/id ref)
    (into {:blob/created-at (or (:blob/created-at ref) (time/now-str))}
          (remove (comp nil? val))
          (select-keys ref [:blob/id :blob/sha256 :blob/size :blob/media-type
                            :blob/encoding :blob/compression :blob/store :blob/key]))))

(defn write!
  ([root value] (write! root value {}))
  ([root value opts]
   (blob/put-edn! (blob-store root) value opts)))

(defn read-edn
  [root ref]
  (blob/read-edn (blob-store root) ref))
