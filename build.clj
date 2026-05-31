(ns build
  "Builds for fractal-engine:
   - `jar`   : a thin library jar (src only) published to Clojars, so Clojure
               consumers depend on `net.clojars.deadmeme5441/fractal-engine`
               via `:mvn/version` instead of a git SHA. The supported facade
               is `fractal-engine.api`.
   - `uber`  : the AOT-compiled `fractal` CLI uberjar (base engine + cheshire,
               no TUI/fulcro), shipped as a GitHub release asset.
   Run: clojure -T:build jar   |   clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def lib 'net.clojars.deadmeme5441/fractal-engine)
;; Release coordinate. CI passes the git tag (minus the `v`) via RELEASE_VERSION
;; so the tag, jar name, and pom never drift; local builds fall back to this.
(def version (or (System/getenv "RELEASE_VERSION") "0.1.1"))
(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/fractal.jar")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(defn- basis [] (b/create-basis {:project "deps.edn"}))

(def ^:private pom-data
  [[:description "A small recursive language-model compute engine: a model drives a persistent Clojure REPL and decomposes work into deterministic, probabilistic, and recursive transformations."]
   [:url "https://github.com/DeadMeme5441/fractal-engine"]
   [:licenses
    [:license
     [:name "Apache License, Version 2.0"]
     [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]
   [:developers
    [:developer
     [:id "DeadMeme5441"]]]
   [:scm
    [:url "https://github.com/DeadMeme5441/fractal-engine"]
    [:connection "scm:git:https://github.com/DeadMeme5441/fractal-engine.git"]
    [:developerConnection "scm:git:ssh://git@github.com/DeadMeme5441/fractal-engine.git"]]])

(defn clean [_] (b/delete {:path "target"}))

(defn jar
  "Build the thin library jar (src + pom) for Clojars."
  [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (basis)
                :pom-data pom-data
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "built" jar-file))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/compile-clj {:basis (basis) :ns-compile '[fractal-engine] :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (basis)
           :main 'fractal-engine})
  (println "built" uber-file))

(defn install
  "Install the library jar into the local ~/.m2 for local consumers."
  [_]
  (jar nil)
  (b/install {:basis (basis)
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))
