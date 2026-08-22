(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def lib 'com.github.clojure-finance/clj-yfinance)
(def version "0.1.8")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(def provided-deps
  "Optional-integration libraries, declared with provided scope in the POM so
   cljdoc can analyse the optional namespaces. Versions come from the
   corresponding deps.edn aliases — the single place they are pinned."
  (let [aliases (:aliases (edn/read-string (slurp "deps.edn")))]
    (->> [:dataset :kindly :parquet :duckdb]
         (mapcat #(get-in aliases [% :extra-deps]))
         (into {})
         (map (fn [[lib {:keys [mvn/version]}]]
                [(namespace lib) (name lib) version])))))

(defn- provided-dep-xml [[group-id artifact-id version]]
  (format "    <dependency>\n      <groupId>%s</groupId>\n      <artifactId>%s</artifactId>\n      <version>%s</version>\n      <scope>provided</scope>\n    </dependency>"
          group-id artifact-id version))

(defn- inject-provided-deps
  "Inject provided-scope dependencies into a POM XML string."
  [pom-xml]
  (let [dep-xml (str/join "\n" (map provided-dep-xml provided-deps))]
    (str/replace pom-xml
                 "</dependencies>"
                 (str dep-xml "\n  </dependencies>"))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/clojure-finance/clj-yfinance"
                      :connection "scm:git:git://github.com/clojure-finance/clj-yfinance.git"
                      :developerConnection "scm:git:ssh://git@github.com/clojure-finance/clj-yfinance.git"
                      :tag (str "v" version)}
                :pom-data [[:licenses
                            [:license
                             [:name "Apache License 2.0"]
                             [:url "https://www.apache.org/licenses/LICENSE-2.0"]
                             [:distribution "repo"]]]]})
  (let [pom-path (str class-dir "/META-INF/maven/com.github.clojure-finance/clj-yfinance/pom.xml")
        pom-xml (slurp pom-path)]
    (spit pom-path (inject-provided-deps pom-xml)))
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (b/copy-file {:src (str class-dir "/META-INF/maven/com.github.clojure-finance/clj-yfinance/pom.xml")
                :target "pom.xml"}))

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact jar-file
              :pom-dir (str class-dir "/META-INF/maven/com.github.clojure-finance/clj-yfinance")}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-dir (str class-dir "/META-INF/maven/com.github.clojure-finance/clj-yfinance")}))
