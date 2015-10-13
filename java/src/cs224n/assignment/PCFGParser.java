package cs224n.assignment;

import cs224n.assignment.*;
import cs224n.ling.Tree;
import cs224n.ling.Trees;
import cs224n.util.*;
import java.util.*;

/**
 * The CKY PCFG Parser you will implement.
 */
public class PCFGParser implements Parser {
    private Grammar grammar;
    private Lexicon lexicon;

    // TODO "score" in the pseudocode
    public RuleScores[][] scores;

    private int numWords;

    public void train(List<Tree<String>> trainTrees) {
        // Binarize trees so rules are at most binary.
        List<Tree<String>> newTrees = new ArrayList<Tree<String>>();
        for (Tree<String> trainTree : trainTrees) {
            Tree<String> newTree = TreeAnnotations.annotateTree(trainTree);
            newTrees.add(newTree);
        }
        // Lexicon is the set of rules from X -> x (a terminal).
        lexicon = new Lexicon(newTrees);
        //System.out.println("lexicon:");
        //System.out.println(lexicon); // TODO remove
        // Grammar is a set of rules from X -> Y or X -> Y Z (non-terminals).
        grammar = new Grammar(newTrees);
        //System.out.println("grammar:");
        //System.out.println(grammar);
    }

    public Tree<String> getBestParse(List<String> sentence) {
        // Go through CKY algorithm as detailed in Manning's slides.

        /**
                  j
        +---+---+---+
        |   |   |   | i
        +---+---+---+
            |   |   |
            +---+---+
                |   |
                +---+

                */
        // Construct 2-D array of scores.
        numWords = sentence.size();
        scores = new RuleScores[numWords + 1][numWords + 1]; // TODO fencepost stuff?

        initScores(sentence);

        //System.out.println("do pans====");
        doSpans();

        //printTable();

        return buildParseTree(sentence);
    }

    private Tree<String> buildParseTree(List<String> words) {
        // STart from the root of tree.
        Rule root = scores[0][numWords].getRule("ROOT");
        Tree<String> rootTree = new Tree<String>("ROOT");

        // create list of things to process
        // pairs of (rules, tree which they are based from)
        Stack<Pair<Rule, Tree<String>>> originList = new Stack<Pair<Rule, Tree<String>>>();
        originList.add(new Pair<Rule, Tree<String>>(root, rootTree));

        while (!originList.empty()) {
            Pair<Rule, Tree<String>> pair = originList.pop();
            Rule rule = pair.getFirst();
            Tree<String> treeBase = pair.getSecond();

            Object ruleOrigin = rule.getOrigin();


            // if no children, this is leaf.
            if (ruleOrigin == null) {
                TerminalRule terminalRule = (TerminalRule) rule;


                Tree<String> childTree = new Tree<String>(terminalRule.getWord());
                List<Tree<String>> children = new ArrayList<Tree<String>>();
                children.add(childTree);
                treeBase.setChildren(children);
            }

            // binary rule
            else if (ruleOrigin instanceof Triplet) {
                Rule left = ((Triplet<Rule, Rule, Integer>) ruleOrigin).getFirst();
                Rule right = ((Triplet<Rule, Rule, Integer>) ruleOrigin).getSecond();

                Tree<String> leftTree = new Tree<String>(left.getParent());
                Tree<String> rightTree = new Tree<String>(right.getParent());

                List<Tree<String>> children = new ArrayList<Tree<String>>();
                children.add(leftTree);
                children.add(rightTree);
                treeBase.setChildren(children);

                // Add the left and right branches to stack for processing
                originList.push(new Pair<Rule, Tree<String>>(left, leftTree));
                originList.push(new Pair<Rule, Tree<String>>(right, rightTree));

            }
            //unary rule. collapse with the next rule.
            else if (ruleOrigin instanceof Rule) {


                Rule child = (Rule) ruleOrigin;

                // TODO for over-binarization
                /*if (rule.getParent().startsWith("@")) {
                    //System.out.println("here");
                    treeBase.setLabel(child.getParent());
                    originList.push(new Pair<Rule, Tree<String>>(child, treeBase));
                }
                else {*/



                Tree<String> childTree = new Tree<String>(child.getParent());
                List<Tree<String>> children = new ArrayList<Tree<String>>();
                children.add(childTree);
               treeBase.setChildren(children);


                // Add child tree to stack for preocessing
                originList.push(new Pair<Rule, Tree<String>>(child, childTree));
               // }



            }
        }

       // rootTree.setWords(words);
        return TreeAnnotations.unAnnotateTree(rootTree);

    }


    private void printTable() {
        for (int i = 0; i < numWords + 1; i++) {
            for (int j = 0; j < numWords + 1; j++) {
                System.out.print(scores[i][j] + "\t");
            }
            //System.out.println("\n");
        }
    }

    private void doSpans() {
        RuleScores ruleScore;
        for (int span = 2; span <= numWords; span++) {
            for (int begin = 0; begin <= numWords - span; begin++) {
                int end = begin + span;
                // ruleScore = scores[begin][end];

                ruleScore = new RuleScores();
                scores[begin][end] = ruleScore;

                for (int split = begin + 1; split <= end - 1; split++) {
                    //handle binary rules.
                    RuleScores leftRuleScore = scores[begin][split];
                    RuleScores rightRuleScore = scores[split][end];

                    // go through all binary rules
                    for (Map.Entry<String, Rule> entry : leftRuleScore.get()) {
                        String leftTag = entry.getKey();
                        Rule leftRule = entry.getValue();

                        for (Grammar.BinaryRule binary : grammar.getBinaryRulesByLeftChild(leftTag)) {
                            // see if the right child exists in the right rulescore
                            if (!rightRuleScore.containsRule(binary.getRightChild())) {
                                continue;
                            }

                            String rightTag = binary.getRightChild();
                            Rule rightRule = rightRuleScore.getRule(rightTag);

                            double prob = binary.getScore() *leftRule.getProb() * rightRule.getProb();
                            String binaryParent = binary.getParent();

                            // Rule already exists. Update that one if it's bigger
                            if (ruleScore.containsRule(binaryParent)) {
                                Rule existingRule = ruleScore.getRule(binaryParent);
                                if (prob > existingRule.getProb()) {
                                    // update existing rule
                                    existingRule.setProb(prob);
                                    // Update rule origin
                                    existingRule.setOrigin(leftRule, rightRule, split);
                                }
                            }
                            else {
                                //System.out.println("no rule");
                                // Otherwise add new rule
                                Rule newRule = new Rule(binary, prob, leftRule,
                                    rightRule, split); // TODO origin
                                ruleScore.addRule(newRule);
                            }
                        }

                    }
                }

                handleUnaries(ruleScore);
            }
        }
    }


    private void initScores(List<String> sentence) {
        //System.out.println("-----initing");
        RuleScores ruleScore;
        String word;
        for (int wordIdx = 0; wordIdx < numWords; wordIdx++) {
            // find word in lexicon and add it to cell
            word = sentence.get(wordIdx);
            ruleScore = new RuleScores();

            for (String tag : lexicon.getAllTags()) {
                double probability = lexicon.scoreTagging(word, tag);
                TerminalRule r = new TerminalRule(tag, word, probability);
                ruleScore.addRule(r);
            }

            // whty is it [i][i + 1] ?
            scores[wordIdx][wordIdx + 1] = ruleScore;

            handleUnaries(ruleScore);
            //System.out.println("\n\n");
        }


    }

    private void handleUnaries(RuleScores ruleScore) {
        boolean added = true;
        while (added) {
            added = false;
            // Go through all current rules A -> B or A -> word or A -> B C
            Set<Map.Entry<String, Rule>> entries = new HashSet<Map.Entry<String, Rule>>(ruleScore.get());
            for (Map.Entry<String, Rule> entry : entries) {
                String ruleParent = entry.getKey();
                Rule r = entry.getValue();

                //System.out.println("rule: " + r);
                // Find unary rules X -> A
                for (Grammar.UnaryRule unary : grammar.getUnaryRulesByChild(ruleParent)) {
                    //System.out.println("checking unary rule: " + unary);
                    double prob = unary.getScore() * r.getProb(); // TODO add the logs of the probs?
                    // see if this beats the current rule X -> A (if that rule already exists)
                    String unaryRuleParent = unary.getParent();

                    // Rule already exists. Update that one if it's bigger
                    if (ruleScore.containsRule(unaryRuleParent)) {
                        Rule existingRule = ruleScore.getRule(unaryRuleParent);
                        //System.out.println("existing rule: " + existingRule);
                        if (prob > existingRule.getProb()) {
                            // update existing rule
                            existingRule.setProb(prob);
                            // Update rule origin
                            existingRule.setOrigin(r);
                            //System.out.println("updated to : "+ existingRule);

                            added = true;
                        }
                    }
                    else {
                        //System.out.println("no rule");
                        // Otherwise add new rule
                        Rule newRule = new Rule(unary, prob, r); // TODO origin
                        //System.out.println("added : " + newRule);
                        ruleScore.addRule(newRule);
                        added = true;
                    }
                }
                //System.out.println();
            }
        }

    }



    // for each square, keep a list of the rules and their probabilities
    private class RuleScores {
        // <rule (binary or unary), probability, origin (the two square they came from>
        // Set of form X -> Y Z , s is X for fast lookup
        Map<String, Rule> ruleScores;

        public RuleScores() {
            ruleScores = new HashMap<String, Rule>();
        }

        public void addRule(Rule rule) {
            ruleScores.put(rule.getParent(), rule);
        }

        public Set<Map.Entry<String, Rule>> get() {
            return ruleScores.entrySet();
        }

        public Rule getRule(String parent) {
            return ruleScores.get(parent);
        }

        // Contains rule X -> ?, checks if starts iwth X
        public boolean containsRule(String parent) {
            return ruleScores.keySet().contains(parent);
        }

        // Contains rule X -> A, checks if the whole rule is there (doesn't compare
        // things like probability)
        public boolean containsRule(Rule r) {
            return ruleScores.entrySet().contains(r);
        }
    }

    private class RuleOrigin {
        // TODO might not need to store RuleOrigin (lots of space overhead)
        public int i1, j1, i2, j2;
        public RuleOrigin(int i1, int j1, int i2, int j2) {
            this.i1 = i1;
            this.j1 = j1;
            this.i2 = i2;
            this.j2 = j2;
        }
    }
    public class Rule {
        boolean isBinaryRule;
        Object rule;
        String parent;

        double probability;
        Object origin;
        //RuleOrigin origin;

        public Rule() { }
        public Rule(Object rule, double probability, Rule origin) {
        //public Rule(Object rule, double probability, RuleOrigin origin) {
            if (rule instanceof Grammar.BinaryRule) {
                isBinaryRule = true;
                parent = ((Grammar.BinaryRule) rule).getParent();
                this.rule = rule;
            }
            else if (rule instanceof Grammar.UnaryRule) {
                isBinaryRule = false;
                parent = ((Grammar.UnaryRule) rule).getParent();
                this.rule = rule;
            }

            this.probability = probability;
            this.origin = origin;
        }
        public Rule(Object rule, double probability, Rule origin1,
                    Rule origin2, int splitOrigin) {
            this(rule, probability, null);
            setOrigin(origin1, origin2, splitOrigin);
        }

        public Grammar.BinaryRule getBinaryRule() {
            assert(isBinaryRule);
            return (Grammar.BinaryRule) rule;
        }

        public Grammar.UnaryRule getUnaryRule() {
            assert(!isBinaryRule);
            return (Grammar.UnaryRule) rule;
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
        //public RuleOrigin getOrigin() {
      //  public Rule getOrigin() {
      //      return origin;
       // }


        public void setOrigin(Rule origin) {
            this.origin = origin;
        }

        public void setOrigin(Rule origin1, Rule origin2, int splitOrigin) {
            this.origin = new Triplet<Rule, Rule, Integer>(origin1, origin2, splitOrigin);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Rule))
                return false;

            Rule other = (Rule) o;
            return this.parent == other.parent && this.rule == other.rule;
        }

        @Override
        public String toString() {
            if (isBinaryRule) {
                Grammar.BinaryRule r = (Grammar.BinaryRule) rule;
                return r.getParent() + " -> " + r.getLeftChild() + " " +
                       r.getRightChild() + " %% " + probability;
            }
            else {
                Grammar.UnaryRule r = (Grammar.UnaryRule) rule;
                return r.getParent() + " -> " + r.getChild() + " %% " +
                       probability;
            }
        }

    }
    private class TerminalRule extends Rule {
        String tag;
        String word;
        double probability;
        public TerminalRule(String tag, String word, double probability) {
            this.tag = tag;
            this.word = word;
            this.probability = probability;
            this.origin = null;
        }

        @Override
        public String getParent() {
            return tag;
        }

        public String getWord() {
            return word;
        }

        @Override
        public String toString() {
            return tag + " -> " + word + " %% " + probability;
        }
    }
}
