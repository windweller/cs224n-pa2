package cs224n.assignment;

import cs224n.ling.Tree;
import cs224n.ling.Trees;
import cs224n.ling.Trees.MarkovizationAnnotationStripper;
import cs224n.util.*;

import java.util.*;

/**
 * Class which contains code for annotating and binarizing trees for
 * the parser's use, and debinarizing and unannotating them for
 * scoring.
 */
public class TreeAnnotations {

  private static boolean doHorizontalMarkovization = false;

	public static Tree<String> annotateTree(Tree<String> unAnnotatedTree) {
	  //thirdOrderMarkovize(unAnnotatedTree);
	  //secondOrderMarkovize(unAnnotatedTree);
		return binarizeTree(unAnnotatedTree);
	}

	private static void secondOrderMarkovize(Tree<String> tree) {
	  Deque<Pair<Tree<String>, String>> treeStack =
	    new ArrayDeque<Pair<Tree<String>, String>>();
	  treeStack.add(new Pair(tree, ""));

	  while (treeStack.size() > 0) {
	    Pair<Tree<String>, String> pair = treeStack.pop();
	    Tree<String> currTree = pair.getFirst();
	    String parent = pair.getSecond();
	    String currLabel = currTree.getLabel();

	    if (!currTree.isLeaf())
	      currTree.setLabel(currTree.getLabel() + parent);

	    for (Tree<String> child : currTree.getChildren()) {
	      treeStack.push(new Pair(child, "^" + currLabel));
	    }
	  }
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
    if (doHorizontalMarkovization) {
      int beginIdx = label.lastIndexOf("->");

      String subLabel = label.substring(beginIdx+2);
      int countUnderscores = subLabel.length() -
                             subLabel.replace("_", "").length();
      int idx = subLabel.lastIndexOf("_");
      if (countUnderscores >= 2 && idx != -1) {
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

	private static Tree<String> binarizeTreeHelper(Tree<String> tree,
			int numChildrenGenerated,
			String intermediateLabel) {
		Tree<String> leftTree = tree.getChildren().get(numChildrenGenerated);
		List<Tree<String>> children = new ArrayList<Tree<String>>();
		children.add(binarizeTree(leftTree));
		if (numChildrenGenerated < tree.getChildren().size() - 1) {
		  String label = intermediateLabel + "_" + leftTree.getLabel();
		  if (doHorizontalMarkovization)
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
