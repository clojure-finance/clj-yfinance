(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.string :as str]))

(def lib 'com.github.clojure-finance/clj-yfinance)
(def version "0.1.6")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(def provided-deps
  [["techascent" "tech.ml.dataset" "7.032"]
   ["org.scicloj" "kindly" "4-beta23"]
   ["com.techascent" "tmd-parquet" "1.000-beta-39"]
   ["com.techascent" "tmducken" "0.10.1-01"]])

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
                             [:name "Eclipse Public License 2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]
                             [:distribution "repo"]]]]})
  (let [pom-path (str class-dir "/META-INF/maven/com.github.clojure-finance/clj-yfinance/pom.xml")
        pom-xml (slurp pom-path)]
    (spit pom-path (inject-provided-deps pom-xml)))
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (b/copy-file {:src (str class-dir "/META-INF/maven/com.github.clojure-finance/clj-yfinance/pom.xml")
                :target "pom.xml"}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-dir (str class-dir "/META-INF/maven/com.github.clojure-finance/clj-yfinance")}))
