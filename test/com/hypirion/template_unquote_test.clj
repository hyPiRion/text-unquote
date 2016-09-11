(ns com.hypirion.template-unquote-test
  (:require [clojure.test :refer :all]
            [com.hypirion.template-unquote :refer :all])
  (:import (java.io StringReader)))

(defn parse-string [s]
  (parsed-seq (StringReader. s)))

(deftest parse-tests
  (testing "basic parse tests"
    (are [s forms] (= (parse-string s) (quote forms))
      "foo bar" ([:string "foo bar"])
      "~~escaped~~" ([:string "~escaped~"])
      "~] ~)" ([:string "] )"])
      "~(foo bar)" ([:form (foo bar)])
      "~@(foo bar)" ([:splice-form (foo bar)])
      "~(baz)zap" ([:form (baz)] [:string "zap"])
      "foo ~(bar) baz" ([:string "foo "] [:form (bar)] [:string " baz"])
      "~#(foo /[v.v]/) quux" ([:recur-form (foo [[:string "/[v.v]/"]])]
                             [:string " quux"])
      "~#(foo~))" ([:recur-form (foo [[:string ")"]])])
      ;; nesting in recur forms
      "~#(foo bar~(baz)zap)" ([:recur-form (foo [[:string "bar"]
                                                 [:form (baz)]
                                                 [:string "zap"]])])
      "~#(foo~#[bar~#(baz)])" ([:recur-form (foo [[:recur-form
                                                 [bar [[:recur-form
                                                        (baz [])]]]]])])
      ;; Uh, it's legal because we do clojure.core/read so yeah
      "~ #=(clojure.lang.PersistentArrayMap/create {})" ([:form {}])))
  (testing "other symbols for evaluating"
    (binding [*unquote-char* \$
              *splice-char* \^
              *recur-char* \>]
      (are [s forms] (= (parse-string s) (quote forms))
        "foo bar" ([:string "foo bar"])
        "tilde: ~" ([:string "tilde: ~"])
        "$(foo bar)" ([:form (foo bar)])
        "$^(foo bar)" ([:splice-form (foo bar)])
        "$>(foo~bar~)" ([:recur-form (foo [[:string "~bar~"]])])
        "$>(a $>[:b $(c)])" ([:recur-form (a [[:recur-form [:b [[:form (c)]]]]])]))))
  (testing "errors"
    (is (thrown-with-msg? java.io.EOFException #"Stream ends with ~#"
                          (dorun (parse-string "~#"))))
    (is (thrown-with-msg? RuntimeException #"Inside ~# form: Expected \) at some point"
                          (dorun (parse-string "~#(foo bar baz banana"))))
    (is (thrown-with-msg? RuntimeException #"~# only supports \[ and \( as delimiters"
                          (dorun (parse-string "~#{}"))))
    (is (thrown-with-msg? RuntimeException #"Unmatched delimiter: \]"
                          (dorun (parse-string "~(]"))))
    (is (thrown-with-msg? RuntimeException #"Unmatched delimiter: \]"
                          (dorun (parse-string "~@(]"))))
    (is (thrown-with-msg? RuntimeException #"EOF while reading"
                          (dorun (parse-string "~"))))))

(deftest eval-test
  (testing "basic evaluation order and the like"
    (are [input output] (with-tmp-ns [s]
                          (eval-in-ns s '(clojure.core/refer-clojure))
                          (= (render-string input #(eval-in-ns s %))
                             output))
      "1 + 2 = 3" "1 + 2 = 3"
      "1 + 2 = ~(+ 1 2)" "1 + 2 = 3"
      "~(do (defonce counter (atom 0)) @counter) ~(swap! counter inc)" "0 1"
      "~(do (defonce counter (atom 0)) @counter) ~(swap! counter inc)" "0 1"
      "~@(do (def counter (atom 0)) nil)~#(str ~(swap! counter inc))" "1")))
