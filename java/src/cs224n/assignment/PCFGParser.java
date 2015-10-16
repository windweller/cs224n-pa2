package cs224n.assignment;

import cs224n.ling.Tree;
import cs224n.ling.Trees;
import cs224n.util.Pair;
import cs224n.util.Triplet;

import java.lang.reflect.Array;
import java.util.*;

/**
 * The CKY PCFG Parser you will implement.
 */
public class PCFGParser implements Parser {
    private Grammar grammar;
    private Lexicon lexicon;

    int[][] scoreTable;
    ArrayList<Map<String, Double>> scoreList;
    ArrayList<Map<String, Triplet<Integer, String, String>>> backList;

    public void train(List<Tree<String>> trainTrees) {
        // TODO: before you generate your grammar, the training trees
        // need to be binarized so that rules are at most binary

        List<Tree<String>> binarizedTrees = new ArrayList<Tree<String>>();

        for (Tree<String> trainTree : trainTrees) {
            binarizedTrees.add(TreeAnnotations.annotateTree(trainTree));
        }

        this.lexicon = new Lexicon(trainTrees);  //original trees might be shallower
        this.grammar = new Grammar(binarizedTrees);
    }

    public static int[][] getScoreTable(int n) {
        int[][] scoreTable = new int[n + 1][n + 1];

        for (int i = 0; i < n; ++i)
            for (int j = 0; j < n; ++j)
                scoreTable[i][j] = -1;

        return scoreTable;
    }

    //this returns a new B, for backList
    public static Triplet<Integer, String, String> newTag(String B) {
        return new Triplet<Integer, String, String>(-1, B, "");
    }

    public static Triplet<Integer, String, String> newTriplet(int index, String B, String C) {
        return new Triplet<Integer, String, String>(index, B, C);
    }

    //build the best tree
    //currentTag is always the best tag (or possible tag in this lower cell)
    @SuppressWarnings("unchecked")
    public Tree<String> buildTree(int x, int y, String currentTag) {

        //for first iteration, we always go to (0, sentenceSize), parent label as ROOT
        Map<String, Triplet<Integer, String, String>> backChoices = backList.get(scoreTable[x][y]);

        //ROOT already exists, first round we always choose ROOT
        //then we let it free-roll
        Triplet<Integer, String, String> back = backChoices.get(currentTag);

        //base case (leaves)
        if (back == null) {
            return new Tree<String>(currentTag);
        }

        //unary rule (stored in the same cell)
        if (back.getFirst() == -1) {
            if (!currentTag.equals(back.getSecond()))
                return new Tree<String>(currentTag, Collections.singletonList(buildTree(x, y, back.getSecond())));
            else
                return new Tree<String>(currentTag, Collections.singletonList(new Tree<String>(back.getSecond())));
        }
        //binary rule
        else {
            //we figure out the best left/right child
            Tree<String> left = buildTree(x, back.getFirst(), back.getSecond());
            Tree<String> right = buildTree(back.getFirst(), y, back.getThird());
            return new Tree<String>(currentTag, createArrayList(left, right));
        }
    }

    public ArrayList<Tree<String>> createArrayList(Tree<String>... trees) {
        ArrayList<Tree<String>> result = new ArrayList<Tree<String>>();
        Collections.addAll(result, trees);
        return result;
    }

    public Tree<String> getBestParse(List<String> sentence) {
        //We have a grammar already, this is the CYK/CKY

        int[][] scoreTable = PCFGParser.getScoreTable(sentence.size());

        ArrayList<Map<String, Double>> scoreList = new ArrayList<Map<String, Double>>();
        //Map<A, Triple<split, B, C>>
        //processed by the same way as scorelist
        ArrayList<Map<String, Triplet<Integer, String, String>>> backList = new ArrayList<Map<String, Triplet<Integer, String, String>>>();

        //first layer
        for (int i = 0; i < sentence.size(); i++) {

            //this is the score inside every cell
            Map<String, Double> score = new HashMap<String, Double>();
            Map<String, Triplet<Integer, String, String>> back = new HashMap<String, Triplet<Integer, String, String>>();

            //construct a list of all possible tags into terminal
            //onto this specific word (lexicon)
            for (String tag: lexicon.getAllTags()) {
                score.put(sentence.get(i), lexicon.scoreTagging(sentence.get(i), tag));
                back.put(sentence.get(i), new Triplet<Integer, String, String>(-1, tag, ""));
            }

            //handle unaries
            boolean added = true;
            while (added) {
                added = false;
                //finding unaries by see whichever non-term connects with terminal symbol
                for (String nonterm: score.keySet()) {
                    //see if this nonterm is in grammar, and iterate through them
                    for (Grammar.UnaryRule unary : grammar.getUnaryRulesByChild(nonterm)) {
                        //B = nonterm, A = uniary.getParent()
                        double prob = unary.getScore() * score.get(nonterm);
                        if (prob > score.get(unary.getParent())) {
                            //score[i][i+1][A] = prob
                            score.put(unary.getParent(), prob);
                            //back[i][i+1][A] = B
                            back.put(unary.getParent(), new Triplet<Integer, String, String>(-1, unary.getChild(), ""));
                            added = true;
                        }
                    }
                }
            }

            //so we can look up each table
            scoreTable[i][i+1] = score.size();
            scoreList.add(score);
            backList.add(back);
        }

        //then we start construting 2nd and onward
        for (int span = 2; span <= sentence.size(); span++) {
            for (int begin = 0; begin <= sentence.size() - span; begin++) {
                int end = begin + span;
                Map<String, Double> score = new HashMap<String, Double>();
                Map<String, Triplet<Integer, String, String>> back = new HashMap<String, Triplet<Integer, String, String>>();

                for (int split = begin +1; split <= end-1; split++) {

                    //We search for possible combination of B, C to A
                    Map<String, Double> Bs = scoreList.get(scoreTable[begin][split]);
                    Map<String, Double> Cs = scoreList.get(scoreTable[split][end]);

                    for (String B: Bs.keySet()) {
                        for (Grammar.BinaryRule br: grammar.getBinaryRulesByLeftChild(B)) {
                            //search all possible rules if C exist
                            if (Cs.containsKey(br.getRightChild())) continue;
                            //if found, we calculate probability
                            //score[begin][split][B] * score[split][end][C] * Pr(A -> BC)
                            double prob = Bs.get(B) * Cs.get(br.getRightChild()) * br.score;
                            //remember to store this as score[begin][end]
                            if (prob > score.get(br.getParent())) {
                                score.put(br.getParent(), prob);
                                back.put(br.getParent(), PCFGParser.newTriplet(split, br.getLeftChild(), br.getRightChild()));
                            }
                        }
                    }
                }

                // for unary rules
                boolean added = true;
                while (added) {
                    added = false;
                    for (String B: score.keySet()) {
                        for (Grammar.UnaryRule unary: grammar.getUnaryRulesByChild(B)) {
                            //for A, B in nonterms
                            //score's range is [begin][end]
                            //[begin][end][B] * Pr(A -> B)
                            double prob = score.get(B) * unary.getScore();
                            if (prob > score.get(unary.getParent())) {
                                score.put(unary.getParent(), prob);
                                back.put(unary.getParent(), PCFGParser.newTag(B));
                                added = true;
                            }
                        }
                    }
                }
                scoreTable[begin][end] = scoreList.size();
                scoreList.add(score);
                backList.add(back);
            }
        }

        this.scoreList = scoreList;
        this.scoreTable = scoreTable;
        this.backList = backList;

        Tree<String> tree = this.buildTree(0, sentence.size(), "ROOT");
        return TreeAnnotations.unAnnotateTree(tree);
    }


}
