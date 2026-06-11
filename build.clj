(ns build
  "Builds for fractal-engine.

   - jar: a thin library jar for Clojars consumers.
   - uber: a self-contained CLI jar for GitHub Releases.

   Run:
     clojure -T:build jar
     clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def lib 'net.clojars.deadmeme5441/fractal-engine)
(def version
  (or (System/getenv "RELEASE_VERSION") "0.7.0"))

(def class-dir "target/classes")
(def uber-file "target/fractal.jar")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(def pom-data
  [[:description
    "A recursive language-model compute engine with durable Clojure REPL sessions, child recursion, payload storage, and an agent-operable CLI."]
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

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Build the thin library jar for Clojars."
  [_]
  (b/delete {:path class-dir})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (basis)
                :pom-data pom-data
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "built" jar-file))

(defn uber
  "Build the self-contained CLI jar."
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/compile-clj {:basis (basis)
                  :ns-compile '[fractal.engine.cli]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (basis)
           :main 'fractal.engine.cli})
  (println "built" uber-file))

(defn install
  "Install the library jar into the local Maven repository."
  [_]
  (jar nil)
  (b/install {:basis (basis)
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir})
  (println "installed" lib version))
