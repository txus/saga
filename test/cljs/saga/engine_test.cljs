(ns saga.engine-test
  (:require-macros [cljs.test :refer [deftest testing is]])
  (:require [cljs.test :as t]
            [saga.engine :as engine]))

(deftest test-tautology []
  (is true "Something is wrong with the universe"))
