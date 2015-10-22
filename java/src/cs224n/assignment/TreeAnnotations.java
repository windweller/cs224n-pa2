package cs224n.assignment;

import java.util.*;

import cs224n.ling.Tree;
import cs224n.ling.Trees;
import cs224n.ling.Trees.MarkovizationAnnotationStripper;
import cs224n.util.Filter;
import cs224n.util.Pair;
import cs224n.util.Triplet;

/**
 * Class which contains code for annotating and binarizing trees for
 * the parser's use, and debinarizing and unannotating them for
 * scoring.
 */
public class TreeAnnotations {

	private static boolean doZeroOrderHorizontalMarkovization = false;
	private static boolean dofirstOrderHorizontalMarkovization = true;
	private static boolean doSecondOrderHorizontalMarkovization = false;

	private static Set<String> verbs = new HashSet<String>();
	private static Set<String> nouns = new HashSet<String>();

	public static Tree<String> annotateTree(Tree<String> unAnnotatedTree) {

		// Currently, the only annotation done is a lossless binarization

		fillVerbs();
		fillNouns();

		secondOrderMarkovize(unAnnotatedTree);

		return binarizeTree(unAnnotatedTree);

	}

	public static void fillVerbs() {
		verbs.add("VB");
		verbs.add("VBD");
		verbs.add("VBG");
		verbs.add("VBN");
		verbs.add("VBP");
		verbs.add("VBZ");
		verbs.add("VP");
	}

	public static void fillNouns() {
		nouns.add("NN");
		nouns.add("NNS");
		nouns.add("NNP");
		nouns.add("NNPS");
		nouns.add("NP");
	}

	//vertical order
	public static void secondOrderMarkovize(Tree<String> tree) {
		Deque<Pair<Tree<String>, String>> treeStack =
				new ArrayDeque<Pair<Tree<String>, String>>();
		treeStack.add(new Pair(tree, ""));

		while (treeStack.size() > 0) {
			Pair<Tree<String>, String> pair = treeStack.pop();
			Tree<String> currTree = pair.getFirst();
			String parentLabel = pair.getSecond();
			String currLabel = currTree.getLabel();

			if (!currTree.isLeaf()) {
				processSplits(tree, parentLabel, currTree); //we embed splits into vertical traversal, but it's preprocessing
				currTree.setLabel(currTree.getLabel() + parentLabel); //we process normally
			}

			for (Tree<String> child : currTree.getChildren()) {
				treeStack.push(new Pair(child, "^" + currLabel));
			}
		}
	}


	public static boolean processSplits(Tree<String> root, String parentTag, Tree<String> currentTree) {
		boolean modified = false;

		if (tagSplit(root, parentTag, currentTree)) modified = true;
		if (yieldSplit(root, parentTag, currentTree)) modified = true;
		if (unarySplit(root, parentTag, currentTree)) modified = true;

		return modified;
	}

	public static boolean unarySplit(Tree<String> root, String parentTag, Tree<String> currentTree) {
		if (parentTag.equals("")) return false;

		//External-unary on DT
		if (currentTree.getLabel().equals("DT")) {
			externalUnaryDeterminer(root, parentTag, currentTree);
			return true;
		}

		//External-unary on RB
		if (currentTree.getLabel().equals("RB")) {
			externalUnaryRB(root, parentTag, currentTree);
			return true;
		}

		int numChildren = currentTree.getChildren().size();
		if (numChildren == 1 && !currentTree.isPreTerminal()) {
			currentTree.setLabel(currentTree.getLabel() + "-U");
			return true; //modified
		}

		else return false;
	}

	public static void externalUnaryDeterminer(Tree<String> root, String parentTag, Tree<String> currentTree) {
		//marking the diff between "the" - determiner and "that" - demonstrative
		//based on whether DT tag has any sibling
		List<Tree<String>> siblings = currentTree.siblings(root);

		if (siblings.size() >= 1)
			currentTree.setLabel(currentTree.getLabel() + "-DET");  //determiner
		else
			currentTree.setLabel(currentTree.getLabel() + "-DEM");  //demonstrative
	}

	public static void externalUnaryRB(Tree<String> root, String parentTag, Tree<String> currentTree) {
		List<Tree<String>> siblings = currentTree.siblings(root);

		if (siblings.size() == 0)
			currentTree.setLabel(currentTree.getLabel() + "-EU");  //External unary
	}

	public static boolean tagSplit(Tree<String> root, String parentTag, Tree<String> currentTree) {
		boolean modified = false;

//		if (inSplit(root, parentTag, currentTree)) modified = true;  //this one does WORSE for the entire thing
//		if (numSplit(root, parentTag, currentTree)) modified = true;
		if (percentSplit(root, parentTag, currentTree)) modified = true;
//		if (ccSplit(root, parentTag, currentTree)) modified = true;

		return modified;
	}

	//NUM_SPLIT is for tags that have more than 2 children, mark those tag with a number like "-3"
	public static boolean numSplit(Tree<String> root, String parentTag, Tree<String> currentTree) {
		if (currentTree.getChildren().size() > 2) {
			currentTree.setLabel(currentTree.getLabel() + "-" + currentTree.getChildren().size());
			return true;
		}

		return false;
	}

	public static boolean percentSplit(Tree<String> root, String parentTag, Tree<String> currentTree) {
		if (currentTree.getChildren().get(0).getLabel().contains("%")) {
			currentTree.setLabel(currentTree.getLabel() + "-PERC"); //percent
			return true;
		}

		return false;
	}

	public static boolean ccSplit(Tree<String> root, String parentTag, Tree<String> currentTree) {
		if (currentTree.getChildren().get(0).getLabel().toLowerCase().contains("but")) {
			currentTree.setLabel(currentTree.getLabel() + "-B");
			return true;
		}

		if (currentTree.getChildren().get(0).getLabel().contains("&")) {
			currentTree.setLabel(currentTree.getLabel() + "-A");
			return true;
		}

		return false;
	}

	//subdivide IN
	//2 methods:
	//1: look at lexicon itself (some lexicons are alone and only in one category)
	//2: look at parent's sibling that can tell you something
	public static boolean inSplit(Tree<String> root, String parentTag, Tree<String> currentTree) {

		boolean modified = false;

		//Split-tag on IN
		if (currentTree.getLabel().equals("IN")) {


			//true preposition
			//  PP
			// /  \
			//IN  NP
			if (parentTag.equals("PP")) {
				currentTree.setLabel(currentTree.getLabel() + "^P");
				return true;
			}

			//sentential complementizer IN:
			//has to track lexicons: that, for
			//it follows a VP:  Christy hopes that Mike wins .
			//    VP
			//   /  \
			//  VB  SBAR
			//      /  \
			//     IN  S
//			if (currentTree.grandParent(root).getLabel().equals("VP")) {
//				currentTree.setLabel(currentTree.getLabel() + "^SNT");
//				modified = true;
//			}

			// Subordinating conjunctions IN occurs under S
			//track lexicons: while, as, if

			//"as" is marked as IN but
			//sometimes take NP sibling, sometimes VP sibling
//			if (currentTree.getChildren().get(0).isLeaf() &&
//					currentTree.getChildren().get(0).getLabel().equals("as")) {
//				//as can take NP or VP (so we mark the sibling on IN)
//
//
//			}

			// === last resort: boilerplate way to differentiate just capture neighbor ===
			List<Tree<String>> siblings = currentTree.siblings(root);

			//we should capture the sibling context in IN
			if (siblings.size() > 0) {
				currentTree.setLabel(currentTree.getLabel() + "^" + getRawLabel(siblings.get(0).getLabel()));
			}
		}

		return modified;
	}

	//for a label like ""
	private static String getRawLabel(String label) {
		String  result = "";
		for (char ch: label.toCharArray()) {
			if (ch != '^' && ch != '-')
				result += ch;
			else break;
		}

		return result;
	}

	//search for if a list of trees contains a tag (on current node)
	public static boolean containsTag(List<Tree<String>> trees, String tag) {
		for (Tree<String> tree: trees) {
			if (tree.getLabel().equals(tag))
				return true;
		}
		return false;
	}

	/**
	 * Yield split looks at the children and decide
	 * how to split a tag. (the reverse of vertical markovization)
	 * @param root
	 * @param parentTag
	 * @param currentTree
	 * @return
	 */
	public static boolean yieldSplit(Tree<String> root, String parentTag, Tree<String> currentTree) {
		boolean modified = false;

//		if (vpSplit(currentTree)) modified = true;
		if (npSplit(currentTree)) modified = true;

		return modified;
	}

	public static boolean vpSplit(Tree<String> currentTree) {

		if (!currentTree.getLabel().equals("VP")) return false;

		//infinite (non-finite) verb is taken by another verb
		//finite verb takes a NP

		//   VP         VP
		//  |  |      |   |
		// VBZ VP    VBZ  NP

		//currently implemented: SPLIT-VP
		List<Tree<String>> children = currentTree.getChildren();

		if (children.size() == 2) {
			//left and right children are both verbs
			//by definition, it's an infinite verb phrase
			if (isVerb(children.get(0)) && isVerb(children.get(1))) {
				currentTree.setLabel(currentTree.getLabel() + "-VBINF");
			}
			else if (isVerb(children.get(0)) && isNoun(children.get(1))) {
				currentTree.setLabel(currentTree.getLabel() + "-VBF");
			}
		}

		return false;
	}

	public static boolean npSplit(Tree<String> currentTree) {
		//       NP
		//    /      \
		//  NP-POS   NNS
		//  / \
		//NNP POS

		if (currentTree.getLabel().equals("NP")) {
		   List<Tree<String>> children = currentTree.getChildren();
		   if (children.size() == 2 && isNoun(children.get(0)) && children.get(1).getLabel().equals("POS")) {
			   currentTree.setLabel(currentTree.getLabel() + "-POS");
			   return true;
		   }
	   }

		return false;
	}

	private static boolean isVerb(Tree<String> node) {
		if (verbs.contains(node.getLabel())) return true;

		for (String verb: verbs) {
			if (node.getLabel().contains(verb)) return true;
		}
		return false;
	}

	private static boolean isNoun(Tree<String> node) {
		if (nouns.contains(node.getLabel())) return true;

		for (String noun: nouns) {
			if (node.getLabel().contains(noun)) return true;
		}
		return false;
	}

	private static void thirdOrderMarkovize(Tree<String> tree) {
		Deque<Triplet<Tree<String>, String, String>> treeStack =
				new ArrayDeque<Triplet<Tree<String>, String, String>>();
		treeStack.add(new Triplet(tree, "", ""));

		while (treeStack.size() > 0) {
			Triplet<Tree<String>, String, String> trip = treeStack.pop();
			Tree<String> currTree = trip.getFirst();
			String parentParent = trip.getSecond();
			String parent = trip.getThird();

			String currLabel = currTree.getLabel();

			if (!currTree.isLeaf())
				currTree.setLabel(currTree.getLabel() + parent + parentParent);

			for (Tree<String> child : currTree.getChildren()) {
				treeStack.push(new Triplet(child, parent, "^" + currLabel));
			}
		}
	}


	private static String horizontalMarkovization(String label) {
		if (doZeroOrderHorizontalMarkovization) {
			int beginIdx = label.lastIndexOf("->");

			String subLabel = label.substring(beginIdx + 2);
			int idx = subLabel.lastIndexOf("_");
			if (idx != -1) {
				subLabel = subLabel.substring(idx + 1);
				label = label.substring(0, beginIdx + 2) + subLabel;
			}
		}

		else if (dofirstOrderHorizontalMarkovization) {
			int beginIdx = label.lastIndexOf("->");

			String subLabel = label.substring(beginIdx + 2);
			int countUnderscores = subLabel.length() -
					subLabel.replace("_", "").length();
			int idx = subLabel.lastIndexOf("_");
			if (countUnderscores >= 2 && idx != -1) {
				idx = subLabel.lastIndexOf("_", idx - 1);
				subLabel = ".." + subLabel.substring(idx + 1);
				label = label.substring(0, beginIdx + 2) + subLabel;
			}
		}

		else if (doSecondOrderHorizontalMarkovization) {
			int beginIdx = label.lastIndexOf("->");

			String subLabel = label.substring(beginIdx + 2);
			int countUnderscores = subLabel.length() -
					subLabel.replace("_", "").length();
			int idx = subLabel.lastIndexOf("_");
			if (countUnderscores >= 3 && idx != -1) {
				idx = subLabel.lastIndexOf("_", idx - 1);
				idx = subLabel.lastIndexOf("_", idx - 1);
				subLabel = ".." + subLabel.substring(idx + 1);
				label = label.substring(0, beginIdx + 2) + subLabel;
			}
		}
		return label;
	}

	private static Tree<String> binarizeTree(Tree<String> tree) {
		String label = tree.getLabel();
		if (tree.isLeaf())
			return new Tree<String>(label);
		if (tree.getChildren().size() == 1) {
			return new Tree<String>
			(label,
					Collections.singletonList(binarizeTree(tree.getChildren().get(0))));
		}
		// otherwise, it's a binary-or-more local tree,
		// so decompose it into a sequence of binary and unary trees.
		String intermediateLabel = "@"+label+"->";
		Tree<String> intermediateTree =
				binarizeTreeHelper(tree, 0, intermediateLabel);
		return new Tree<String>(label, intermediateTree.getChildren());
	}

	//calling horizontal markovization here
	private static Tree<String> binarizeTreeHelper(Tree<String> tree,
												   int numChildrenGenerated,
												   String intermediateLabel) {
		Tree<String> leftTree = tree.getChildren().get(numChildrenGenerated);
		List<Tree<String>> children = new ArrayList<Tree<String>>();
		children.add(binarizeTree(leftTree));
		if (numChildrenGenerated < tree.getChildren().size() - 1) {
			String label = intermediateLabel + "_" + leftTree.getLabel();
			label = horizontalMarkovization(label);

			Tree<String> rightTree =
					binarizeTreeHelper(tree, numChildrenGenerated + 1, label);
			children.add(rightTree);
		}
		return new Tree<String>(intermediateLabel, children);
	}


	public static Tree<String> unAnnotateTree(Tree<String> annotatedTree) {

		// Remove intermediate nodes (labels beginning with "@"
		// Remove all material on node labels which follow their base symbol
		// (cuts at the leftmost - or ^ character)
		// Examples: a node with label @NP->DT_JJ will be spliced out,
		// and a node with label NP^S will be reduced to NP

		Tree<String> debinarizedTree =
				Trees.spliceNodes(annotatedTree, new Filter<String>() {
					public boolean accept(String s) {
						return s.startsWith("@");
					}
				});
		Tree<String> unAnnotatedTree =
				(new Trees.FunctionNodeStripper()).transformTree(debinarizedTree);
    Tree<String> unMarkovizedTree =
        (new Trees.MarkovizationAnnotationStripper()).transformTree(unAnnotatedTree);
		return unMarkovizedTree;
	}
}
