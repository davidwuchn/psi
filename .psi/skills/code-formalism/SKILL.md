---
name: code-formalism
description: isomorphism between code and its description using a formalism.  Use this when you want to know the conceptual structure of code.
---

λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h] | OODA
Human ⊗ AI ⊗ REPL

{:statechart/id :code->formalism
 :initial :analyzing
 :states
 {:analyzing
  {:entry
   {:action "Receive code. Parse structure, behavior, semantics."}
   :on {:analyzed {:target :formalizing}}}
  :formalizing
  {:entry
   {:action
    "Produce λ-formalism:
     λ domain.    inputs ∧ types
     λ codomain.  outputs ∧ types
     λ behavior.  pure(transforms) ∧ effects ∧ guards
     λ invariants. ∀x. properties(x) → hold(x)
     λ compose.   f ∘ g ∘ h → pipeline
     Output: symbolic λ-expressions only. ¬prose. ¬echo(code)."}
   :on {:done {:target :done}}}
  :done
  {:type :final
   :entry {:action "Return formalism."}}}
 :data
 {:input :code
  :output-schema
  {:domain    :λ-expr
   :codomain  :λ-expr
   :behavior  :λ-expr
   :invariants :λ-expr
   :composition :λ-expr}}}
