(ns com.hypirion.template-unquote-test
  (:require [clojure.test :refer :all]
            [com.hypirion.template-unquote :refer :all])
  (:import (java.io StringReader)))

(deftest parse-tests
  (testing "basic parse tests"
    (are [s forms] (= (parsed-seq (StringReader. s))
                      (quote forms))
      "foo bar" ([:string "foo bar"])
      "~~quoting~~" ([:string "~quoting~"])
      "~(foo bar)" ([:form (foo bar)])
      "~@(foo bar)" ([:splice-form (foo bar)])
      "~(baz)zap" ([:form (baz)] [:string "zap"])
      "foo ~(bar) baz" ([:string "foo "] [:form (bar)] [:string " baz"])
      "~#(foo /[v.v]/) quux" ([:inline-form (foo [[:string " /[v.v]/"]])]
                             [:string " quux"])
      ;; nesting in inline forms
      "~#(foo bar~(baz)zap)" ([:inline-form (foo [[:string " bar"]
                                                 [:form (baz)]
                                                 [:string "zap"]])])
      "~#(foo~#[bar~#(baz)])" ([:inline-form (foo [[:inline-form
                                                 [bar [[:inline-form
                                                        (baz [])]]]]])])
      ;; Uh, it's legal because we do clojure.core/read so yeah
      "~ #=(clojure.lang.PersistentArrayMap/create {})" ([:form {}])))
  (testing "other symbols for evaluating"
    (binding [*unquote-char* (int \$)
              *splice-char* (int \^)
              *inline-char* (int \>)]
      (are [s forms] (= (parsed-seq (StringReader. s))
                        (quote forms))
        "foo bar" ([:string "foo bar"])
        "tilde: ~" ([:string "tilde: ~"])
        "$(foo bar)" ([:form (foo bar)])
        "$^(foo bar)" ([:splice-form (foo bar)])
        "$>(foo~bar~)" ([:inline-form (foo [[:string "~bar~"]])])
        "$>(a $>[:b $(c)])" ([:inline-form (a [[:string " "]
                                              [:inline-form [:b [[:string " "]
                                                                 [:form (c)]]]]])])))))
