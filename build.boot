(set-env!
 :source-paths    #{"sass" "src/cljs"}
 :resource-paths  #{"resources"}
 :dependencies '[[adzerk/boot-cljs          "1.7.228-2"  :scope "test"]
                 [adzerk/boot-cljs-repl     "0.3.3"      :scope "test"]
                 [adzerk/boot-reload        "0.4.13"     :scope "test"]
                 [pandeiro/boot-http        "0.7.6"      :scope "test"]
                 [com.cemerick/piggieback   "0.2.1"      :scope "test"]
                 [org.clojure/tools.nrepl   "0.2.12"     :scope "test"]
                 [org.clojure/test.check "0.9.0" :exclude [org.clojure/clojure] :scope "test"]
                 [weasel                    "0.7.0"      :scope "test"]
                 [org.clojure/clojurescript "1.9.293"]
                 [binaryage/dirac "0.8.7"]
                 [crisptrutski/boot-cljs-test "0.3.0" :scope "test"]
                 [org.omcljs/om "1.0.0-alpha47"]
                 [deraen/boot-sass  "0.3.0" :scope "test"]
                 [prismatic/plumbing "0.5.3"]
                 [funcool/cuerdas "2.0.2"]
                 [secretary "1.2.3"]

                 [binaryage/devtools      "0.8.3" :scope "test"]
                 [binaryage/dirac         "0.6.3" :scope "test"]
                 [powerlaces/boot-cljs-devtools "0.1.2" :scope "test"]

                 [org.slf4j/slf4j-nop  "1.7.22" :scope "test"]])

(require
 '[adzerk.boot-cljs      :refer [cljs]]
 '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
 '[adzerk.boot-reload    :refer [reload]]
 '[pandeiro.boot-http    :refer [serve]]
 '[crisptrutski.boot-cljs-test :refer [test-cljs]]
 '[deraen.boot-sass    :refer [sass]]
 '[powerlaces.boot-cljs-devtools :refer [cljs-devtools]])

(deftask build []
  (comp (speak)
        (cljs)
        (sass)))

(deftask run []
  (comp (serve)
        (watch)
        (cljs-repl :nrepl-opts {:port 8230
                                :middleware '[dirac.nrepl/middleware]}
                   :port 9009)
        (reload)
        (build)))

(deftask production []
  (task-options! cljs {:optimizations :advanced}
                 sass   {:output-style :compressed})
  identity)

(deftask development []
  (task-options! cljs {:optimizations :none :source-map true}
                 reload {:on-jsload 'livestory.player/init})
  identity)

(deftask editor-development []
  (task-options! cljs {:optimizations :none :source-map true}
                 reload {:on-jsload 'livestory.ide/init})
  identity)

(deftask dev
  "Simple alias to run application in development mode"
  []
  (comp (development)
     (run)))

(deftask editor-dev
  []
  (comp (editor-development)
     (run)))

(deftask testing []
  (set-env! :source-paths #(conj % "test/cljs"))
  identity)

;;; This prevents a name collision WARNING between the test task and
;;; clojure.core/test, a function that nobody really uses or cares
;;; about.
(ns-unmap 'boot.user 'test)

(deftask test []
  (comp (testing)
        (test-cljs :js-env :phantom
                   :exit?  true)))

(deftask auto-test []
  (comp (testing)
        (watch)
        (test-cljs :js-env :phantom)))
