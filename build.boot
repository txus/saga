(set-env!
 :source-paths    #{"sass" "src/cljs"}
 :dependencies '[[adzerk/boot-cljs          "2.1.5"  :scope "test"]
                 [adzerk/boot-cljs-repl     "0.4.0"      :scope "test"]
                 [adzerk/boot-reload        "0.6.0"     :scope "test"]
                 [pandeiro/boot-http        "0.8.3"      :scope "test"]
                 [cider/piggieback   "0.4.1"      :scope "test"]
                 [com.cemerick/url "0.1.1"]
                 [nrepl "0.4.5" :scope "test"]
                 [org.clojure/test.check "0.9.0" :exclude [org.clojure/clojure] :scope "test"]
                 [weasel                    "0.7.0"      :scope "test"]
                 [org.clojure/clojurescript "1.10.520"]
                 [binaryage/dirac "RELEASE" :scope "test"]
                 [crisptrutski/boot-cljs-test "0.3.4" :scope "test"]
                 [org.omcljs/om "1.0.0-beta4"]
                 [deraen/boot-sass  "0.3.1" :scope "test"]
                 [prismatic/plumbing "0.5.5"]
                 [funcool/cuerdas "2.2.0"]
                 [secretary "1.2.3"]

                 [binaryage/dirac         "1.2.15" :scope "test"]
                 [powerlaces/boot-cljs-devtools "0.2.0" :scope "test"]

                 [org.slf4j/slf4j-nop  "1.7.25" :scope "test"]])

(require
 '[adzerk.boot-cljs      :refer [cljs]]
 '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
 '[adzerk.boot-reload    :refer [reload]]
 '[pandeiro.boot-http    :refer [serve]]
 '[crisptrutski.boot-cljs-test :refer [test-cljs]]
 '[deraen.boot-sass    :refer [sass]]
 '[powerlaces.boot-cljs-devtools :refer [cljs-devtools]])

(task-options! repl {:eval '(do (require 'dirac.agent)
                                (dirac.agent/boot!))})

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
  (task-options!
   cljs {:optimizations :simple
         :compiler-options {:parallel-build true
                            :compiler-stats true
                            :pretty-print false}}
   sass {:output-style :compressed})
  identity)

(deftask player-development []
  (set-env! :resource-paths #(into % #{"dev-resources" "player-resources"}))
  (task-options! reload {:on-jsload 'saga.player/init}
                 cljs {:optimizations :none
                       :compiler-options
                       {:asset-path "js/player.out"
                        :source-map true
                        :source-map-timestamp true
                        :preloads '[dirac.runtime.preload]
                        :parallel-build true
                        :cache-analysis true}})
  identity)

(deftask ide-development []
  (set-env! :resource-paths #(into % #{"dev-resources" "ide-resources"}))
  (task-options! cljs {:optimizations :none
                       :compiler-options
                       {:source-map true
                        :source-map-timestamp true
                        :asset-path "js/ide.out"
                        :preloads '[dirac.runtime.preload]
                        :parallel-build true
                        :cache-analysis true}}
                 reload {:on-jsload 'saga.ide/init})
  identity)

(deftask package [t build-target VAL str "The target to build. Can be 'ide' or 'player'"]
  (set-env! :resource-paths #(conj % (str build-target "-resources")))
  (comp
   (production)
   (cljs :ids #{(str "js/" build-target)})
   (sass)
   (sift :include #{#"cache\.manifest"
                    #"css\/fonts\.css"
                    #"css\/material\.css"
                    #"js\/material\.js"
                    #"index.html"
                    (re-pattern (str "css/" build-target "\\.css"))
                    (re-pattern (str "js/" build-target "\\.js"))})
   (target :dir #{(str "target-" build-target)})))

(deftask player-dev
  "Simple alias to run application in development mode"
  []
  (comp (player-development)
     (run)))

(deftask ide-dev
  []
  (comp (ide-development)
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
