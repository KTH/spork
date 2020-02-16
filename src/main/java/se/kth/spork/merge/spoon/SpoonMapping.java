package se.kth.spork.merge.spoon;

import com.github.gumtreediff.matchers.Mapping;
import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.tree.ITree;
import com.github.gumtreediff.utils.Pair;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import spoon.reflect.declaration.CtElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A class for storing matches between tree nodes in two Spoon trees. Inspired by the MappingStore class from GumTree.
 *
 * See <a href="https://github.com/GumTreeDiff/gumtree/blob/f20565b6261fe3465cd1b3e0914028d5e87699b2/core/src/main/java/com/github/gumtreediff/matchers/MappingStore.java#L1-L151">
 *     MappingStore.java
 *     </a> in GumTree for comparison.
 *
 * It is my opinion that this file is sufficiently distinct from GumTree's MappingStore that the former does not count
 * as a derivative of the latter, and the similar functionality is trivial. Therefore, I do not think that the
 * LGPL license of the GumTree project needs to be applied to Spork.
 *
 * @author Simon Larsén
 */
public class SpoonMapping {
    private Map<SpoonNode, SpoonNode> srcs;
    private Map<SpoonNode, SpoonNode> dsts;


    // SpoonMapping should only be instantiated with fromGumTreeMapping, which is why the default constructor is private
    private SpoonMapping() {
        srcs = new HashMap<>();
        dsts = new HashMap<>();
    }

    /**
     * Create a Spoon mapping from a GumTree mapping. Every GumTree node must have a "spoon_object" metadata object that
     * refers back to a Spoon node. As this mapping does not cover the whole Spoon tree, additional mappings are
     * inferred.
     *
     * TODO verify that the mapping inference is actually correct
     *
     * @param gumtreeMapping A GumTree mapping in which each mapped node has a "spoon_object" metadata object.
     * @return A SpoonMapping corresponding to the passed GumTree mapping.
     */
    public static SpoonMapping fromGumTreeMapping(MappingStore gumtreeMapping) {
        SpoonMapping mapping = new SpoonMapping();

        for (Mapping m : gumtreeMapping.asSet()) {
            CtElement spoonSrc = getSpoonNode(m.first);
            CtElement spoonDst = getSpoonNode(m.second);
            if (spoonSrc == null || spoonDst == null) {
                if (spoonSrc != spoonDst) { // at least one was non-null
                    throw new IllegalStateException();
                }
                if (m.first.getType() != -1) { // -1 is the type given to root node in SpoonGumTreeBuilder
                    throw new IllegalStateException("non-root node " + m.first.toShortString()
                            + " had no mapped Spoon object");
                }
            } else {
                mapping.put(spoonSrc, spoonDst);
            }
        }

        mapping.inferAdditionalMappings(mapping.asList());
        return mapping;
    }

    private List<Pair<CtElement, CtElement>> asList() {
        return srcs.values().stream()
                .map(dst -> new Pair<>(getSrc(dst).getElement(), dst.getElement()))
                .collect(Collectors.toList());
    }

    /**
     * Infer additional node matches. It is done by iterating over all pairs of matched nodes, and for each pair,
     * descending down into the tree incrementally and matching nodes that gumtree-spoon-ast-diff is known to
     * ignore. See <a href="https://github.com/SpoonLabs/gumtree-spoon-ast-diff/blob/dae908192bee7773b38d149baff831ee616ec524/src/main/java/gumtree/spoon/builder/TreeScanner.java#L71-L84">TreeScanner</a>
     * to see how nodes are ignored in gumtree-spoon-ast-diff. The process is repeated for each pair of newly matched
     * nodes, until no new matches can be found.
     *
     * @param matches Pairs of matched nodes, as computed by GumTree/gumtree-spoon-ast-diff.
     */
    private void inferAdditionalMappings(List<Pair<CtElement, CtElement>> matches) {
        while (!matches.isEmpty()) {
            List<Pair<CtElement, CtElement>> newMatches = new ArrayList<>();
            for (SpoonNode dst : new ArrayList<>(srcs.values())) {
                SpoonNode src = getSrc(dst);
                newMatches.addAll(inferAdditionalMappings(src.getElement(), dst.getElement()));
            }
            matches = newMatches;
        }
    }

    private List<Pair<CtElement, CtElement>> inferAdditionalMappings(CtElement src, CtElement dst) {
        List<CtElement> srcChildren = src.getDirectChildren();
        List<CtElement> dstChildren = dst.getDirectChildren();
        List<Pair<CtElement, CtElement>> newMatches = new ArrayList<>();

        int srcIdx = 0;
        int dstIdx = 0;

        while (srcIdx < srcChildren.size() && dstIdx < dstChildren.size()) {
            CtElement srcChild = srcChildren.get(srcIdx);
            CtElement dstChild = dstChildren.get(dstIdx);

            if (hasSrc(srcChild) || !GumTreeSpoonAstDiff.isToIgnore(srcChild)) {
                srcIdx++;
            } else if (hasDst(dstChild) || !GumTreeSpoonAstDiff.isToIgnore(dstChild)) {
                dstIdx++;
            } else {
                //assert srcChild.equals(dstChild);
                // TODO this is the sketchy part, is it really enough to check for class identity? Actual equality does not appear to work
                assert srcChild.getClass() == dstChild.getClass();

                put(srcChild, dstChild);
                newMatches.add(new Pair<>(srcChild, dstChild));
            }
        }

        return newMatches;
    }

    public boolean hasSrc(SpoonNode src) {
        return srcs.containsKey(src);
    }

    public boolean hasDst(SpoonNode dst) {
        return dsts.containsKey(dst);
    }

    public boolean hasSrc(CtElement src) {
        return hasSrc(NodeFactory.wrap(src));
    }

    public boolean hasDst(CtElement dst) {
        return hasDst(NodeFactory.wrap(dst));
    }

    public SpoonNode getDst(SpoonNode src) {
        return srcs.get(src);
    }

    public CtElement getDst(CtElement src) {
        return getDst(NodeFactory.wrap(src)).getElement();
    }

    public SpoonNode getSrc(SpoonNode dst) {
        return dsts.get(dst);
    }

    public CtElement getSrc(CtElement dst) {
        return getSrc(NodeFactory.wrap(dst)).getElement();
    }

    public void put(CtElement src, CtElement dst) {
        put(NodeFactory.wrap(src), NodeFactory.wrap(dst));
    }

    public void put(SpoonNode src, SpoonNode dst) {
        srcs.put(src, dst);
        dsts.put(dst, src);
    }

    private static CtElement getSpoonNode(ITree gumtreeNode) {
        return (CtElement) gumtreeNode.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);
    }

    private String formatEntry(Map.Entry<SpoonNode, SpoonNode> entry) {
        return "(" + entry.getKey() + ", " + entry.getValue() + ")";
    }

    @Override
    public String toString() {
        return "SpoonMappingStore{" +
                "srcs=" + srcs.entrySet().stream().map(this::formatEntry).collect(Collectors.toList()) +
                ", dsts=" + dsts.entrySet().stream().map(this::formatEntry).collect(Collectors.toList()) +
                '}';
    }
}
