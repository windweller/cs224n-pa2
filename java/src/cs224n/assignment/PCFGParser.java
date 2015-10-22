package cs224n.assignment;

import cs224n.assignment.*;
import cs224n.assignment.Grammar.BinaryRule;
import cs224n.assignment.Grammar.UnaryRule;
import cs224n.ling.Tree;
import cs224n.ling.Trees;
import cs224n.util.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A CKY PCFG parser based on the pseudocode in Manning's slides and videos.
 */
public class PCFGParser implements Parser {
    private Grammar grammar;
    private Lexicon lexicon;
    private int numWords;

    // Keeps the Viterbi (max) scores ("score" in the pseudocode). Each
    // RuleScore keeps a list of rules and their scores.
    public RuleScores[] scores;

    public void train(List<Tree<String>> trainTrees) {
        // Binarize trees so rules are at most binary.
        for (int i = 0; i < trainTrees.size(); i++) {
            Tree<String> trainTree = trainTrees.get(i);
            trainTrees.set(i, TreeAnnotations.annotateTree(trainTree));
        }
        // Lexicon is the set of rules from X -> x (a terminal).
        lexicon = new Lexicon(trainTrees);
        // Grammar is a set of rules from X -> Y or X -> Y Z (non-terminals).
        grammar = new Grammar(trainTrees);

        /* We used these to get the number of rules and tags. */
        //System.out.println("Num Rules: " + grammar.ruleSize());
        //System.out.println("Num Tags: " + grammar.tagSize());
    }

    public Tree<String> getBestParse(List<String> sentence) {
        // Initialize 2D array of scores. +1 for the fencepost to incorporate
        // empties.
        numWords = sentence.size();
        scores = new RuleScores[(numWords + 2) * (numWords + 1) / 2];

        initScores(sentence);
        doSpans();
        return buildParseTree();
    }

    private int idx(int row, int col) {
        int sum = 0;
        for (int i =0 ; i < row; i++) {
            sum += (numWords + 1) - (i );
        }
        return sum + col - row;
    }

    private void initScores(List<String> sentence) {
        RuleScores ruleScore;
        String word;

        // Loop through all words in sentence to initialize the RuleScore.
        for (int wordIdx = 0; wordIdx < numWords; wordIdx++) {
            // Find word in lexicon and add it to cell.
            word = sentence.get(wordIdx);
            ruleScore = new RuleScores();

            // Find this word in the lexicon. Initialize the rules of the form
            // A -> word.
            for (String tag : lexicon.getAllTags()) {
                double probability = lexicon.scoreTagging(word, tag);
                TerminalRule rule = new TerminalRule(tag, word, probability);
                ruleScore.addRule(rule);
            }
            scores[idx(wordIdx, wordIdx + 1)] = ruleScore;

            // Handle unary rules.
            handleUnaries(ruleScore);
        }
    }

    private void doSpans() {
        RuleScores ruleScore;
        for (int span = 2; span <= numWords; span++) {
            for (int begin = 0; begin <= numWords - span; begin++) {
                int end = begin + span;
                ruleScore = new RuleScores();
                scores[idx(begin, end)] = ruleScore;

                for (int split = begin + 1; split <= end - 1; split++) {
                    // Handle binary rules that end in ? -> L R.
                    RuleScores leftScore = scores[idx(begin, split)];
                    RuleScores rightScore = scores[idx(split, end)];

                    // Go through all binary rules L -> ?.
                    for (Map.Entry<String, Rule> entry : leftScore.get()) {
                        String leftTag = entry.getKey();
                        Rule leftRule = entry.getValue();

                        // Find rules of the form ? -> L R'.
                        for (BinaryRule binary : grammar.getBinaryRulesByLeftChild(leftTag)) {
                            // See if there is a rule R' -> ?. If not, move on
                            // to the next rule.
                            if (!rightScore.containsRule(binary.getRightChild())) {
                                continue;
                            }
                            String ruleParent = binary.getParent();
                            String rightTag = binary.getRightChild();
                            Rule rightRule = rightScore.getRule(rightTag);

                            // See if this one beats the current probability.
                            double prob = binary.getScore() *
                                          leftRule.getProb() *
                                          rightRule.getProb();
                            if (ruleScore.containsRule(ruleParent)) {
                                Rule existingRule = ruleScore.getRule(ruleParent);
                                if (prob > existingRule.getProb()) {
                                    existingRule.setProb(prob);
                                    existingRule.setOrigin(leftRule, rightRule);
                                }
                            }
                            // Otherwise, add a new rule.
                            else {
                                Rule newRule =
                                    new Rule(binary, prob, leftRule, rightRule);
                                ruleScore.addRule(newRule);
                            }
                        }
                    }
                }

                // Handle unary rules.
                handleUnaries(ruleScore);
            }
        }
    }

    private void handleUnaries(RuleScores ruleScore) {
        boolean added = true;
        while (added) {
            added = false;

            // Get all rules that exist for this cell.
            for (Map.Entry<String, Rule> entry : ruleScore.get()) {
                // A -> B: ruleParent is A, rule is (A -> B).
                String parent = entry.getKey();
                Rule rule = entry.getValue();

                // Find unary rules X -> A, where A starts a rule we already
                // have in the ruleScore.
                for (UnaryRule unary : grammar.getUnaryRulesByChild(parent)) {
                    // Compute the new probability of applying this rule.
                    double prob = unary.getScore() * rule.getProb();

                    // See if it beats the current probability X -> ?.
                    String unaryRuleParent = unary.getParent();
                    if (ruleScore.containsRule(unaryRuleParent)) {
                        Rule existingRule = ruleScore.getRule(unaryRuleParent);

                        // If so, update the probability of the existing rule
                        // and its origin.
                        if (prob > existingRule.getProb()) {
                            existingRule.setProb(prob);
                            existingRule.setOrigin(rule);
                            added = true;
                        }
                    }
                    // This rule doesn't exist yet. Add it.
                    else {
                        Rule newRule = new Rule(unary, prob, rule);
                        ruleScore.addRule(newRule);
                        added = true;
                    }
                }
            }
        }
    }

    private Tree<String> buildParseTree() {
        // Start from the root of tree.
        Rule root = scores[idx(0, numWords)].getRule("ROOT");
        Tree<String> rootTree = new Tree<String>("ROOT");

        // Create stack of things to process. Contains pairs of (rule, tree),
        // where tree is where this rule is rooted upon.
        Stack<Pair<Rule, Tree<String>>> originList
            = new Stack<Pair<Rule, Tree<String>>>();
        originList.add(new Pair<Rule, Tree<String>>(root, rootTree));

        // Process until finished.
        while (!originList.empty()) {
            Pair<Rule, Tree<String>> pair = originList.pop();
            Rule rule = pair.getFirst();
            Tree<String> treeBase = pair.getSecond();

            // Get the origin/branch/leaves of this rule.
            Object ruleOrigin = rule.getOrigin();

            // If there is no origin, then we are on leaf/terminal rule.
            //     A
            //     |
            //   leaf
            if (ruleOrigin == null) {
                TerminalRule terminalRule = (TerminalRule) rule;

                // Add this leaf to the current tree.
                Tree<String> childTree = new Tree<String>(terminalRule.getWord());
                List<Tree<String>> children = new ArrayList<Tree<String>>();
                children.add(childTree);
                treeBase.setChildren(children);
            }

            // Binary rule the form A -> B C. We then create a tree as follows:
            //       A
            //      / \
            //     B   C
            else if (ruleOrigin instanceof Pair) {
                Rule left = ((Pair<Rule, Rule>) ruleOrigin).getFirst();
                Rule right = ((Pair<Rule, Rule>) ruleOrigin).getSecond();
                Tree<String> leftTree = new Tree<String>(left.getParent());
                Tree<String> rightTree = new Tree<String>(right.getParent());

                // Add both rules to the current tree.
                List<Tree<String>> children = new ArrayList<Tree<String>>();
                children.add(leftTree);
                children.add(rightTree);
                treeBase.setChildren(children);

                // Add the left and right branches to stack for processing.
                originList.push(new Pair<Rule, Tree<String>>(left, leftTree));
                originList.push(new Pair<Rule, Tree<String>>(right, rightTree));
            }

            // Unary rule of the form A -> B.
            //     A
            //     |
            //     B
            else if (ruleOrigin instanceof Rule) {
                Rule child = (Rule) ruleOrigin;

                // Add rule to tree.
                Tree<String> childTree = new Tree<String>(child.getParent());
                List<Tree<String>> children = new ArrayList<Tree<String>>();
                children.add(childTree);
                treeBase.setChildren(children);

                // Add child to stack for processing.
                originList.push(new Pair<Rule, Tree<String>>(child, childTree));
            }
        }

        // Un-annotate tree to remove the @ rules.
        return TreeAnnotations.unAnnotateTree(rootTree);
    }

    ////////////////////////////// HELPER CLASSES //////////////////////////////

    /**
     * For each cell in the CKY parsing, keey track of all the rules and
     * their probailities.
     */
    private class RuleScores {
        /** Mapping of a rule parent to a rule. e.g. if the rule is A => B X,
            we store key: A and value: Rule(A -> B X). */
        ConcurrentHashMap<String, Rule> ruleScores;

        public RuleScores() {
            ruleScores = new ConcurrentHashMap<String, Rule>();
        }

        public Set<Map.Entry<String, Rule>> get() {
            return ruleScores.entrySet();
        }

        public void addRule(Rule rule) {
            ruleScores.put(rule.getParent(), rule);
        }

        public Rule getRule(String parent) {
            return ruleScores.get(parent);
        }

        /** Returns whether or not we already have this rule parent. For a
            rule X -> A, checks if we have any rules X -> ?. */
        public boolean containsRule(String parent) {
            return ruleScores.keySet().contains(parent);
        }

        /** Returns whether or not we already have this rule X -> A. */
        public boolean containsRule(Rule r) {
            return ruleScores.entrySet().contains(r);
        }
    }

    /**
     * Represents a CFG rule. Can be a unary, binary, or terminal rule.
     */
    public class Rule {
        /** Actual BinaryRule or UnaryRule object. */
        Object rule;
        /** Rule "parent". i.e. is A in the rule A -> B C. */
        String parent;
        /** Probability of this rule. */
        double probability;
        /** Origin of this rule (which one it came from). Used for back-tracing
            to build the best parse at the end. */
        Object origin;

        public Rule() { }

        public Rule(Object rule, double probability, Rule origin) {
            if (rule instanceof BinaryRule)
                parent = ((BinaryRule) rule).getParent();
            else if (rule instanceof UnaryRule)
                parent = ((UnaryRule) rule).getParent();

            this.rule = rule;
            this.probability = probability;
            this.origin = origin;
        }
        public Rule(Object rule, double probability, Rule origin1, Rule origin2) {
            this(rule, probability, null);
            setOrigin(origin1, origin2);
        }

        public String getParent() {
            return parent;
        }

        public double getProb() {
            return probability;
        }

        public void setProb(double prob) {
            probability = prob;
        }

        public Object getOrigin() {
            return origin;
        }

        public void setOrigin(Rule origin) {
            this.origin = origin;
        }

        public void setOrigin(Rule origin1, Rule origin2) {
            this.origin = new Pair<Rule, Rule>(origin1, origin2);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Rule))
                return false;

            // We only compare equality using the actual rule, not the
            // probabilities or origin.
            Rule other = (Rule) o;
            return this.parent == other.parent && this.rule == other.rule;
        }

        @Override
        public String toString() {
            if (rule instanceof BinaryRule) {
                BinaryRule r = (BinaryRule) rule;
                return r.getParent() + " -> " + r.getLeftChild() + " " +
                       r.getRightChild() + " %% " + probability;
            }
            else {
                UnaryRule r = (UnaryRule) rule;
                return r.getParent() + " -> " + r.getChild() + " %% " +
                       probability;
            }
        }
    }

    /**
     * Represents a rule that terminates (e.g. A -> word).
     */
    private class TerminalRule extends Rule {
        String tag;
        String word;

        public TerminalRule(String tag, String word, double probability) {
            this.tag = tag;
            this.word = word;
            this.probability = probability;
            this.origin = null;
        }

        public String getWord() {
            return word;
        }

        @Override
        public String getParent() {
            return tag;
        }

        @Override
        public String toString() {
            return tag + " -> " + word + " %% " + probability;
        }
    }
}
