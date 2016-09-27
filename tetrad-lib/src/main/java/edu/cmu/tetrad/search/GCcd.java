///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.IKnowledge;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.util.ChoiceGenerator;
import edu.cmu.tetrad.util.DepthChoiceGenerator;
import edu.cmu.tetrad.util.ForkJoinPoolInstance;
import edu.cmu.tetrad.util.TetradLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveTask;

/**
 * This class provides the data structures and methods for carrying out the Cyclic Causal Discovery algorithm (CCD)
 * described by Thomas Richardson and Peter Spirtes in Chapter 7 of Computation, Causation, & Discovery by Glymour and
 * Cooper eds.  The comments that appear below are keyed to the algorithm specification on pp. 269-271. </p> The search
 * method returns an instance of a Graph but it also constructs two lists of node triples which represent the underlines
 * and dotted underlines that the algorithm discovers.
 *
 * @author Frank C. Wimberly
 * @author Joseph Ramsey
 */
public final class GCcd implements GraphSearch {
    private IndependenceTest independenceTest;
    private int depth = -1;
    private IKnowledge knowledge;
    private List<Node> nodes;
    private boolean applyR1 = true;
    private boolean verbose;

    public GCcd(IndependenceTest test) {
        if (test == null) throw new NullPointerException();
        this.independenceTest = test;
        this.nodes = test.getVariables();
    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * The search method assumes that the IndependenceTest provided to the constructor is a conditional independence
     * oracle for the SEM (or Bayes network) which describes the causal structure of the population. The method returns
     * a PAG instantiated as a Tetrad GaSearchGraph which represents the equivalence class of digraphs which are
     * d-separation equivalent to the digraph of the underlying model (SEM or BN). </p> Although they are not returned
     * by the search method it also computes two lists of triples which, respectively store the underlines and dotted
     * underlines of the PAG.
     */
    public Graph search() {
        Map<Triple, List<Node>> supSepsets = new HashMap<>();

        Fgs fgs = new Fgs(new SemBicScore(independenceTest.getCov()));
        fgs.setVerbose(verbose);
        fgs.setNumPatternsToStore(0);
        fgs.setFaithfulnessAssumed(false);
        Graph psi = fgs.search();

        SepsetProducer sepsets0 = new SepsetsGreedy(new EdgeListGraphSingleConnections(psi),
                independenceTest, null, -1);

        for (Edge edge : psi.getEdges()) {
            Node a = edge.getNode1();
            Node c = edge.getNode2();

            if (psi.isAdjacentTo(a, c)) {
                if (sepsets0.getSepset(a, c) != null) {
                    psi.removeEdge(a, c);
                }
            }
        }

//        FasStableConcurrent fas = new FasStableConcurrent(independenceTest);
//        Graph psi = fas.search();

        psi.reorientAllWith(Endpoint.CIRCLE);

        SepsetProducer sepsets = new SepsetsMinScore(psi, independenceTest, -1);

        addColliders(psi);

        orientR1(psi);

        stepC(psi, sepsets, null);
        stepD(psi, sepsets, supSepsets, null);
        if (stepE(supSepsets, psi)) return psi;
        stepF(psi, sepsets, supSepsets);

        return psi;
    }

    private void orientR1(Graph graph) {
        for (Edge edge : graph.getEdges()) {
            Node n1 = edge.getNode1();
            Node n2 = edge.getNode2();

            edge = graph.getEdge(n1, n2);

            if (edge.pointsTowards(n1)) {
                orientR1(n2, n1, graph);
            } else if (edge.pointsTowards(n2)) {
                orientR1(n1, n2, graph);
            }
        }
    }

    public IKnowledge getKnowledge() {
        return knowledge;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setKnowledge(IKnowledge knowledge) {
        if (knowledge == null) {
            throw new NullPointerException();
        }
        this.knowledge = knowledge;
    }

    public long getElapsedTime() {
        return 0;
    }

    //======================================== PRIVATE METHODS ====================================//

    private void addColliders(Graph graph) {
        final Map<Triple, Double> colliders = new ConcurrentHashMap<>();
        final Map<Triple, Double> noncolliders = new ConcurrentHashMap<>();

        List<Node> nodes = graph.getNodes();

        class Task extends RecursiveTask<Boolean> {
            private final Map<Triple, Double> colliders;
            private final Map<Triple, Double> noncolliders;
            private int from;
            private int to;
            private int chunk = 20;
            private List<Node> nodes;
            private Graph graph;

            public Task(List<Node> nodes, Graph graph, Map<Triple, Double> colliders,
                        Map<Triple, Double> noncolliders, int from, int to) {
                this.nodes = nodes;
                this.graph = graph;
                this.from = from;
                this.to = to;
                this.colliders = colliders;
                this.noncolliders = noncolliders;
            }

            @Override
            protected Boolean compute() {
                if (to - from <= chunk) {
                    for (int i = from; i < to; i++) {
                        doNode(graph, colliders, noncolliders, nodes.get(i));
                    }

                    return true;
                } else {
                    int mid = (to + from) / 2;

                    Task left = new Task(nodes, graph, colliders, noncolliders, from, mid);
                    Task right = new Task(nodes, graph, colliders, noncolliders, mid, to);

                    left.fork();
                    right.compute();
                    left.join();

                    return true;
                }
            }
        }

        Task task = new Task(nodes, graph, colliders, noncolliders, 0, nodes.size());

        ForkJoinPoolInstance.getInstance().getPool().invoke(task);

        List<Triple> collidersList = new ArrayList<>(colliders.keySet());
        List<Triple> noncollidersList = new ArrayList<>(noncolliders.keySet());

        Collections.sort(collidersList, new Comparator<Triple>() {

            @Override
            public int compare(Triple o1, Triple o2) {
                return -Double.compare(colliders.get(o1), colliders.get(o1));
            }
        });

        for (Triple triple : collidersList) {
            Node a = triple.getX();
            Node b = triple.getY();
            Node c = triple.getZ();

            if (!(graph.getEndpoint(b, a) == Endpoint.ARROW || graph.getEndpoint(b, c) == Endpoint.ARROW)) {
                graph.removeEdge(a, b);
                graph.removeEdge(c, b);
                graph.addDirectedEdge(a, b);
                graph.addDirectedEdge(c, b);
            }
        }

        for (Triple triple : noncollidersList) {
            Node a = triple.getX();
            Node b = triple.getY();
            Node c = triple.getZ();

            graph.addUnderlineTriple(a, b, c);
        }
    }

    private void doNode(Graph graph, Map<Triple, Double> colliders, Map<Triple, Double> noncolliders, Node b) {
        List<Node> adjacentNodes = graph.getAdjacentNodes(b);

        if (adjacentNodes.size() < 2) {
            return;
        }

        ChoiceGenerator cg = new ChoiceGenerator(adjacentNodes.size(), 2);
        int[] combination;

        while ((combination = cg.next()) != null) {
            Node a = adjacentNodes.get(combination[0]);
            Node c = adjacentNodes.get(combination[1]);

            // Skip triples that are shielded.
            if (graph.isAdjacentTo(a, c)) {
                continue;
            }

            List<Node> adja = graph.getAdjacentNodes(a);
            double score = Double.POSITIVE_INFINITY;
            List<Node> S = null;

            DepthChoiceGenerator cg2 = new DepthChoiceGenerator(adja.size(), -1);
            int[] comb2;

            while ((comb2 = cg2.next()) != null) {
                List<Node> s = GraphUtils.asList(comb2, adja);
                independenceTest.isIndependent(a, c, s);
                double _score = independenceTest.getScore();

                if (_score < score) {
                    score = _score;
                    S = s;
                }
            }

            List<Node> adjc = graph.getAdjacentNodes(c);

            DepthChoiceGenerator cg3 = new DepthChoiceGenerator(adjc.size(), -1);
            int[] comb3;

            while ((comb3 = cg3.next()) != null) {
                List<Node> s = GraphUtils.asList(comb3, adjc);
                independenceTest.isIndependent(c, a, s);
                double _score = independenceTest.getScore();

                if (_score < score) {
                    score = _score;
                    S = s;
                }
            }

            if (S == null) {
                throw new NullPointerException();
            }

            // S actually has to be non-null here, but the compiler doesn't know that.
            if (S.contains(b)) {
                noncolliders.put(new Triple(a, b, c), score);
            } else {
                colliders.put(new Triple(a, b, c), score);
            }
        }
    }

    private void stepC(Graph psi, SepsetProducer sepsets, SepsetMap sepsetsFromFas) {
        TetradLogger.getInstance().log("info", "\nStep C");

        EDGE:
        for (Edge edge : psi.getEdges()) {
            Node x = edge.getNode1();
            Node y = edge.getNode2();

            // x and y are adjacent.

            List<Node> adjx = psi.getAdjacentNodes(x);
            List<Node> adjy = psi.getAdjacentNodes(y);

            for (Node node : adjx) {
                if (psi.getEdge(node, x).getProximalEndpoint(x) == Endpoint.ARROW && psi.isUnderlineTriple(y, x, node)) {
                    continue EDGE;
                }
            }

            // Check each A
            for (Node a : nodes) {
                if (a == x) continue;
                if (a == y) continue;

                //...A is not adjacent to X and A is not adjacent to Y...
                if (adjx.contains(a)) continue;
                if (adjy.contains(a)) continue;

                // Orientable...
                if (!(psi.getEndpoint(y, x) == Endpoint.CIRCLE &&
                        (psi.getEndpoint(x, y) == Endpoint.CIRCLE || psi.getEndpoint(x, y) == Endpoint.TAIL))) {
                    continue;
                }

                if (wouldCreateBadCollider(x, y, psi)) {
                    continue;
                }

                //...X is not in sepset<A, Y>...
                if (!sepsets.isIndependent(a, x, sepsets.getSepset(a, y))) {
                    psi.removeEdge(x, y);
                    psi.addDirectedEdge(y, x);
                    orientR1(y, x, psi);
                    break;
                }
            }
        }
    }

    private boolean wouldCreateBadCollider(Node x, Node y, Graph psi) {
        for (Node z : psi.getAdjacentNodes(y)) {
            if (x == z) continue;
            if (psi.getEndpoint(x, y) != Endpoint.ARROW && psi.getEndpoint(z, y) == Endpoint.ARROW) return true;
        }

        return false;
    }

    private void stepD(Graph psi, SepsetProducer sepsets, Map<Triple, List<Node>> supSepsets, SepsetMap fasSepsets) {
        TetradLogger.getInstance().log("info", "\nStep D");

        Map<Node, List<Node>> local = new HashMap<>();

        for (Node node : psi.getNodes()) {
            local.put(node, local(psi, node));
        }

        int m = 1;

        //maxCountLocalMinusSep is the largest cardinality of all sets of the
        //form Loacl(psi,A)\(SepSet<A,C> union {B,C})
        while (maxCountLocalMinusSep(psi, sepsets, local) >= m) {
            for (Node b : nodes) {
                List<Node> adj = psi.getAdjacentNodes(b);

                if (adj.size() < 2) continue;

                ChoiceGenerator gen1 = new ChoiceGenerator(adj.size(), 2);
                int[] choice1;

                while ((choice1 = gen1.next()) != null) {
                    Node a = adj.get(choice1[0]);
                    Node c = adj.get(choice1[1]);

                    if (psi.isAdjacentTo(a, c)) {
                        continue;
                    }

                    if (b == c || b == a) {
                        continue;
                    }

                    // This should never happen..
                    if (supSepsets.get(new Triple(a, b, c)) != null) {
                        continue;
                    }

                    // A-->B<--C
                    if (!psi.isDefCollider(a, b, c)) {
                        continue;
                    }

                    //Compute the number of elements (count)
                    //in Local(psi,A)\(sepset<A,C> union {B,C})
                    Set<Node> localMinusSep = countLocalMinusSep(sepsets, local, a, b, c);

                    int count = localMinusSep.size();

                    if (count < m) {
                        continue; //If not >= m skip to next triple.
                    }

                    //Compute the set T (setT) with m elements which is a subset of
                    //Local(psi,A)\(sepset<A,C> union {B,C})
                    Object[] v = new Object[count];
                    for (int i = 0; i < count; i++) {
                        v[i] = (localMinusSep.toArray())[i];
                    }

                    ChoiceGenerator generator = new ChoiceGenerator(count, m);
                    int[] choice;

                    while ((choice = generator.next()) != null) {
                        Set<Node> setT = new LinkedHashSet<>();
                        for (int i = 0; i < m; i++) {
                            setT.add((Node) v[choice[i]]);
                        }

                        setT.add(b);
                        List<Node> sepset = sepsets.getSepset(a, c);
                        setT.addAll(sepset);

                        List<Node> listT = new ArrayList<>(setT);

                        //Note:  B is a collider between A and C (see above).
                        //If anode and cnode are d-separated given T union
                        //sep[a][c] union {bnode} create a dotted underline triple
                        //<A,B,C> and record T union sepset<A,C> union {B} in
                        //supsepset<A,B,C> and in supsepset<C,B,A>

                        if (independenceTest.isIndependent(a, c, listT)) {
                            supSepsets.put(new Triple(a, b, c), listT);

                            psi.addDottedUnderlineTriple(a, b, c);
                            TetradLogger.getInstance().log("underlines", "Adding dotted underline: " +
                                    new Triple(a, b, c));

                            break;
                        }
                    }
                }
            }

            m++;
        }
    }

    /**
     * Computes and returns the size (cardinality) of the largest set of the form Local(psi,A)\(SepSet<A,C> union {B,C})
     * where B is a collider between A and C and where A and C are not adjacent.  A, B and C should not be a dotted
     * underline triple.
     */
    private static int maxCountLocalMinusSep(Graph psi, SepsetProducer sep,
                                             Map<Node, List<Node>> loc) {
        List<Node> nodes = psi.getNodes();
        int maxCount = -1;

        for (Node b : nodes) {
            List<Node> adjacentNodes = psi.getAdjacentNodes(b);

            if (adjacentNodes.size() < 2) {
                continue;
            }

            ChoiceGenerator cg = new ChoiceGenerator(adjacentNodes.size(), 2);
            int[] combination;

            while ((combination = cg.next()) != null) {
                Node a = adjacentNodes.get(combination[0]);
                Node c = adjacentNodes.get(combination[1]);

                if (psi.isAdjacentTo(a, c)) {
                    continue;
                }

                // Want B to be a collider between A and C but not for
                //A, B, and C to be an underline triple.
                if (psi.isUnderlineTriple(a, b, c)) {
                    continue;
                }

                //Is B a collider between A and C?
                if (!psi.isDefCollider(a, b, c)) {
                    continue;
                }

                Set<Node> localMinusSep = countLocalMinusSep(sep, loc, a, b, c);
                int count = localMinusSep.size();

                if (count > maxCount) {
                    maxCount = count;
                }
            }
        }

        return maxCount;
    }

    /**
     * For a given GaSearchGraph psi and for a given set of sepsets, each of which is associated with a pair of vertices
     * A and C, computes and returns the set Local(psi,A)\(SepSet<A,C> union {B,C}).
     */
    private static Set<Node> countLocalMinusSep(SepsetProducer sepset,
                                                Map<Node, List<Node>> local, Node anode,
                                                Node bnode, Node cnode) {
        Set<Node> localMinusSep = new HashSet<>();
        localMinusSep.addAll(local.get(anode));
        List<Node> sepset1 = sepset.getSepset(anode, cnode);
        localMinusSep.removeAll(sepset1);
        localMinusSep.remove(bnode);
        localMinusSep.remove(cnode);

        return localMinusSep;
    }


    private boolean stepE(Map<Triple, List<Node>> supSepset, Graph psi) {
        TetradLogger.getInstance().log("info", "\nStep E");

        if (nodes.size() < 4) {
            return true;
        }

        for (Triple triple : psi.getDottedUnderlines()) {
            Node a = triple.getX();
            Node b = triple.getY();

            List<Node> aAdj = psi.getAdjacentNodes(a);

            for (Node d : aAdj) {
                if (d == b) continue;

                if (supSepset.get(triple).contains(d)) {
                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    // Orient B*-oD as B*-D
                    psi.setEndpoint(b, d, Endpoint.TAIL);
                } else {
                    if (psi.getEndpoint(d, b) == Endpoint.ARROW) {
                        continue;
                    }

                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    if (wouldCreateBadCollider(b, d, psi)) {
                        return false;
                    }

                    // Or orient Bo-oD or B-oD as B->D...
                    psi.removeEdge(b, d);
                    psi.addDirectedEdge(b, d);
                    orientR1(b, d, psi);
                }

            }

            for (Node d : aAdj) {
                if (d == b) continue;

                if (supSepset.get(triple).contains(d)) {
                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    // Orient B*-oD as B*-D
                    psi.setEndpoint(b, d, Endpoint.TAIL);
                } else {
                    if (psi.getEndpoint(d, b) == Endpoint.ARROW) {
                        continue;
                    }

                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    if (wouldCreateBadCollider(b, d, psi)) {
                        return false;
                    }

                    // Or orient Bo-oD or B-oD as B->D...
                    psi.removeEdge(b, d);
                    psi.addDirectedEdge(b, d);
                    orientR1(b, d, psi);
                }
            }
        }

        return false;
    }


    private void stepF(Graph psi, SepsetProducer sepsets, Map<Triple, List<Node>> supSepsets) {
        for (Triple triple : psi.getDottedUnderlines()) {
            Node a = triple.getX();
            Node b = triple.getY();
            Node c = triple.getZ();

            Set<Node> adj = new HashSet<>(psi.getAdjacentNodes(a));
            adj.addAll(psi.getAdjacentNodes(c));

            for (Node d : adj) {
                if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                    continue;
                }

                if (psi.getEndpoint(d, b) == Endpoint.ARROW) {
                    continue;
                }

                //...and D is not adjacent to both A and C in psi...
                if (psi.isAdjacentTo(a, d) && psi.isAdjacentTo(c, d)) {
                    continue;
                }

                //...and B and D are adjacent...
                if (!psi.isAdjacentTo(b, d)) {
                    continue;
                }

                Set<Node> supSepUnionD = new HashSet<>();
                supSepUnionD.add(d);
                supSepUnionD.addAll(supSepsets.get(triple));
                List<Node> listSupSepUnionD = new ArrayList<>(supSepUnionD);

                if (wouldCreateBadCollider(b, d, psi)) {
                    continue;
                }

                //If A and C are a pair of vertices d-connected given
                //SupSepset<A,B,C> union {D} then orient Bo-oD or B-oD
                //as B->D in psi.
                if (!sepsets.isIndependent(a, c, listSupSepUnionD)) {
                    psi.removeEdge(b, d);
                    psi.addDirectedEdge(b, d);
                    orientR1(b, d, psi);
                }
            }
        }
    }

    private List<Node> local(Graph psi, Node z) {
        List<Node> local = new ArrayList<>();

        //Is X p-adjacent to v in psi?
        for (Node x : nodes) {
            if (x == z) {
                continue;
            }

            if (psi.isAdjacentTo(z, x)) {
                local.add(x);
            }

            //or is there a collider between X and v in psi?
            for (Node y : nodes) {
                if (y == z || y == x) {
                    continue;
                }

                if (psi.isDefCollider(x, y, z)) {
                    if (!local.contains(x)) {
                        local.add(x);
                    }
                }
            }
        }

        return local;
    }

    private void orientR1(Node a, Node b, Graph graph) {
        if (!isApplyR1()) return;

        for (Node c : graph.getAdjacentNodes(b)) {
            if (c == a) continue;
            orientR1Visit(a, b, c, graph);
        }
    }

    private boolean orientR1Visit(Node a, Node b, Node c, Graph graph) {
        if (!Edges.isNondirectedEdge(graph.getEdge(b, c))) {
            return false;
        }

        if (!(graph.isUnderlineTriple(a, b, c))) {
            return false;
        }


        if (graph.getEdge(b, c).pointsTowards(b)) {
            return false;
        }

        graph.removeEdge(b, c);
        graph.addDirectedEdge(b, c);

        for (Node d : graph.getAdjacentNodes(c)) {
            if (d == b) return true;

            Edge bc = graph.getEdge(b, c);

            if (!orientR1Visit(b, c, d, graph)) {
                graph.removeEdge(b, c);
                graph.addEdge(bc);
            }
        }

        return true;
    }

    public boolean isApplyR1() {
        return applyR1;
    }

    public void setApplyR1(boolean applyR1) {
        this.applyR1 = applyR1;
    }
}





