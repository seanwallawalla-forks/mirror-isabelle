(*<*)
theory termination = examples:
(*>*)

text{*
When a function~$f$ is defined via \isacommand{recdef}, Isabelle tries to prove
its termination with the help of the user-supplied measure.  Each of the examples
above is simple enough that Isabelle can automatically prove that the
argument's measure decreases in each recursive call. As a result,
$f$@{text".simps"} will contain the defining equations (or variants derived
from them) as theorems. For example, look (via \isacommand{thm}) at
@{thm[source]sep.simps} and @{thm[source]sep1.simps} to see that they define
the same function. What is more, those equations are automatically declared as
simplification rules.

Isabelle may fail to prove the termination condition for some
recursive call.  Let us try the following artificial function:*}

consts f :: "nat\<times>nat \<Rightarrow> nat"
recdef (*<*)(permissive)(*>*)f "measure(\<lambda>(x,y). x-y)"
  "f(x,y) = (if x \<le> y then x else f(x,y+1))"

text{*\noindent
Isabelle prints a
\REMARK{error or warning?  change this part?  rename g to f?}
message showing you what it was unable to prove. You will then
have to prove it as a separate lemma before you attempt the definition
of your function once more. In our case the required lemma is the obvious one:
*}

lemma termi_lem: "\<not> x \<le> y \<Longrightarrow> x - Suc y < x - y"

txt{*\noindent
It was not proved automatically because of the awkward behaviour of subtraction
on type @{typ"nat"}. This requires more arithmetic than is tried by default:
*}

apply(arith)
done

text{*\noindent
Because \isacommand{recdef}'s termination prover involves simplification,
we include in our second attempt a hint: the \attrdx{recdef_simp} attribute 
says to use @{thm[source]termi_lem} as
a simplification rule.  
*}

consts g :: "nat\<times>nat \<Rightarrow> nat"
recdef g "measure(\<lambda>(x,y). x-y)"
  "g(x,y) = (if x \<le> y then x else g(x,y+1))"
(hints recdef_simp: termi_lem)

text{*\noindent
This time everything works fine. Now @{thm[source]g.simps} contains precisely
the stated recursion equation for @{term g}, which has been stored as a
simplification rule.  Thus we can automatically prove results such as this one:
*}

theorem "g(1,0) = g(1,1)"
apply(simp)
done

text{*\noindent
More exciting theorems require induction, which is discussed below.

If the termination proof requires a new lemma that is of general use, you can
turn it permanently into a simplification rule, in which case the above
\isacommand{hint} is not necessary. But our @{thm[source]termi_lem} is not
sufficiently general to warrant this distinction.

The attentive reader may wonder why we chose to call our function @{term g}
rather than @{term f} the second time around. The reason is that, despite
the failed termination proof, the definition of @{term f} did not
fail, and thus we could not define it a second time. However, all theorems
about @{term f}, for example @{thm[source]f.simps}, carry as a precondition
the unproved termination condition. Moreover, the theorems
@{thm[source]f.simps} are not stored as simplification rules. 
However, this mechanism
allows a delayed proof of termination: instead of proving
@{thm[source]termi_lem} up front, we could prove 
it later on and then use it to remove the preconditions from the theorems
about @{term f}. In most cases this is more cumbersome than proving things
up front.
\REMARK{FIXME, with one exception: nested recursion.}
*}

(*<*)
end
(*>*)
