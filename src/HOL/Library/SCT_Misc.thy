theory SCT_Misc
imports Main
begin


subsection {* Searching in lists *}

fun index_of :: "'a list \<Rightarrow> 'a \<Rightarrow> nat"
where
  "index_of [] c = 0"
| "index_of (x#xs) c = (if x = c then 0 else Suc (index_of xs c))"

lemma index_of_member: 
  "(x \<in> set l) \<Longrightarrow> (l ! index_of l x = x)"
  by (induct l) auto

lemma index_of_length:
  "(x \<in> set l) = (index_of l x < length l)"
  by (induct l) auto



subsection {* Some reasoning tools *}

lemma inc_induct[consumes 1]:
  assumes less: "i \<le> j"
  assumes base: "P j"
  assumes step: "\<And>i. \<lbrakk>i < j; P (Suc i)\<rbrakk> \<Longrightarrow> P i"
  shows "P i"
  using less
proof (induct d\<equiv>"j - i" arbitrary: i)
  case (0 i)
  with `i \<le> j` have "i = j" by simp
  with base show ?case by simp
next
  case (Suc d i)
  hence "i < j" "P (Suc i)"
    by simp_all
  thus "P i" by (rule step)
qed

lemma strict_inc_induct[consumes 1]:
  assumes less: "i < j"
  assumes base: "\<And>i. j = Suc i \<Longrightarrow> P i"
  assumes step: "\<And>i. \<lbrakk>i < j; P (Suc i)\<rbrakk> \<Longrightarrow> P i"
  shows "P i"
  using less
proof (induct d\<equiv>"j - i - 1" arbitrary: i)
  case (0 i)
  with `i < j` have "j = Suc i" by simp
  with base show ?case by simp
next
  case (Suc d i)
  hence "i < j" "P (Suc i)"
    by simp_all
  thus "P i" by (rule step)
qed


lemma three_cases:
  assumes "a1 \<Longrightarrow> thesis"
  assumes "a2 \<Longrightarrow> thesis"
  assumes "a3 \<Longrightarrow> thesis"
  assumes "\<And>R. \<lbrakk>a1 \<Longrightarrow> R; a2 \<Longrightarrow> R; a3 \<Longrightarrow> R\<rbrakk> \<Longrightarrow> R"
  shows "thesis"
  using prems
  by auto


section {* Sequences *}

types
  'a sequence = "nat \<Rightarrow> 'a"

subsection {* Increasing sequences *}

definition increasing :: "(nat \<Rightarrow> nat) \<Rightarrow> bool"
where
  "increasing s = (\<forall>i j. i < j \<longrightarrow> s i < s j)"

lemma increasing_strict:
  assumes "increasing s"
  assumes "i < j"
  shows "s i < s j"
  using prems
  unfolding increasing_def by simp

lemma increasing_weak:
  assumes "increasing s"
  assumes "i \<le> j"
  shows "s i \<le> s j"
  using prems increasing_strict[of s i j]
  by (cases "i<j") auto

lemma increasing_inc:
  assumes [simp]: "increasing s"
  shows "n \<le> s n"
proof (induct n)
  case (Suc n)
  with increasing_strict[of s n "Suc n"]
  show ?case by auto
qed auto


lemma increasing_bij:
  assumes [simp]: "increasing s"
  shows "(s i < s j) = (i < j)"
proof
  assume "s i < s j"
  show "i < j"
  proof (rule classical)
    assume "\<not> ?thesis"
    hence "j \<le> i" by arith
    with increasing_weak have "s j \<le> s i" by simp
    with `s i < s j` show ?thesis by simp
  qed
qed (simp add:increasing_strict)




subsection {* Sections induced by an increasing sequence *}

abbreviation
  "section s i == {s i ..< s (Suc i)}"

definition
  "section_of s n = (LEAST i. n < s (Suc i))"


lemma section_help:
  assumes [intro, simp]: "increasing s"
  shows "\<exists>i. n < s (Suc i)" 
proof -
  from increasing_inc have "n \<le> s n" .
  also from increasing_strict have "\<dots> < s (Suc n)" by simp
  finally show ?thesis ..
qed

lemma section_of2:
  assumes "increasing s"
  shows "n < s (Suc (section_of s n))"
  unfolding section_of_def
  by (rule LeastI_ex) (rule section_help)


lemma section_of1:
  assumes [simp, intro]: "increasing s"
  assumes "s i \<le> n"
  shows "s (section_of s n) \<le> n"
proof (rule classical)
  let ?m = "section_of s n"

  assume "\<not> ?thesis"
  hence a: "n < s ?m" by simp
  
  have nonzero: "?m \<noteq> 0"
  proof
    assume "?m = 0"
    from increasing_weak have "s 0 \<le> s i" by simp
    also note `\<dots> \<le> n`
    finally show False using `?m = 0` `n < s ?m` by simp 
  qed
  with a have "n < s (Suc (?m - 1))" by simp
  with Least_le have "?m \<le> ?m - 1"
    unfolding section_of_def .
  with nonzero show ?thesis by simp
qed

lemma section_of_known: 
  assumes [simp]: "increasing s"
  assumes in_sect: "k \<in> section s i"
  shows "section_of s k = i" (is "?s = i")
proof (rule classical)
  assume "\<not> ?thesis"

  hence "?s < i \<or> ?s > i" by arith
  thus ?thesis
  proof
    assume "?s < i"
    hence "Suc ?s \<le> i" by simp
    with increasing_weak have "s (Suc ?s) \<le> s i" by simp
    moreover have "k < s (Suc ?s)" using section_of2 by simp
    moreover from in_sect have "s i \<le> k" by simp
    ultimately show ?thesis by simp 
  next
    assume "i < ?s" hence "Suc i \<le> ?s" by simp
    with increasing_weak have "s (Suc i) \<le> s ?s" by simp
    moreover 
    from in_sect have "s i \<le> k" by simp
    with section_of1 have "s ?s \<le> k" by simp
    moreover from in_sect have "k < s (Suc i)" by simp
    ultimately show ?thesis by simp
  qed
qed 

  
lemma in_section_of: 
  assumes [simp, intro]: "increasing s"
  assumes "s i \<le> k"
  shows "k \<in> section s (section_of s k)"
  using prems
  by (auto intro:section_of1 section_of2)


end