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

import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.util.StatUtils;
import org.apache.commons.math3.distribution.TDistribution;

import java.awt.*;
import java.util.List;

import static java.lang.Math.*;

/**
 * Fast adjacency search followed by robust skew orientation. Checks are done for adding
 * two-cycles. The two-cycle checks do not require non-Gaussianity. The robust skew
 * orientation of edges left or right does.
 *
 * @author Joseph Ramsey
 */
public final class Fang1 implements GraphSearch {

    // Elapsed time of the search, in milliseconds.
    private long elapsed = 0;

    // The data sets being analyzed. They must all have the same variables and the same
    // number of records.
    private DataSet dataSet = null;

    // For the Fast Adjacency Search.
    private int depth = -1;

    // For the SEM BIC score, for the Fast Adjacency Search.
    private double penaltyDiscount = 1;

    // Alpha for orienting 2-cycles. Usually needs to be low.
    private double alpha = 1e-6;

    // Knowledge the the search will obey, of forbidden and required edges.
    private IKnowledge knowledge = new Knowledge2();

    /**
     * @param dataSet These datasets must all have the same variables, in the same order.
     */
    public Fang1(DataSet dataSet) {
        this.dataSet = dataSet;
    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * Runs the search on the concatenated data, returning a graph, possibly cyclic, possibly with
     * two-cycles. Runs the fast adjacency search (FAS, Spirtes et al., 2000) follows by a modification
     * of the robust skew rule (Pairwise Likelihood Ratios for Estimation of Non-Gaussian Structural
     * Equation Models, Smith and Hyvarinen), together with some heuristics for orienting two-cycles.
     *
     * @return the graph. Some of the edges may be undirected (though it shouldn't be many in most cases)
     * and some of the adjacencies may be two-cycles.
     */
    public Graph search() {
        long start = System.currentTimeMillis();

        DataSet dataSet = DataUtils.standardizeData(this.dataSet);

        SemBicScore score = new SemBicScore(new CovarianceMatrixOnTheFly(dataSet));
        score.setPenaltyDiscount(penaltyDiscount);
        IndependenceTest test = new IndTestScore(score, dataSet);
        List<Node> variables = dataSet.getVariables();

        double[][] colData = dataSet.getDoubleData().transpose().toArray();

        System.out.println("FAS");

        FasStable fas = new FasStable(test);
        fas.setDepth(getDepth());
        fas.setVerbose(false);
        fas.setKnowledge(knowledge);
        Graph G0 = fas.search();

        SearchGraphUtils.pcOrientbk(knowledge, G0, G0.getNodes());

        System.out.println("Orientation");

        Graph graph = new EdgeListGraph(variables);

        for (int i = 0; i < variables.size(); i++) {
            for (int j = i + 1; j < variables.size(); j++) {
                Node X = variables.get(i);
                Node Y = variables.get(j);

                // Standardized.
                final double[] x = colData[i];
                final double[] y = colData[j];

                double[] c1 = StatUtils.cov(x, y, x, 0, +1);
                double[] c2 = StatUtils.cov(x, y, y, 0, +1);
                double[] e1 = StatUtils.E(x, y, x, 0, +1);
                double[] e2 = StatUtils.E(x, y, y, 0, +1);

                double e = e1[0];
                double f = e2[0];

                double vxx = c1[2];
                double vxy = c1[3];
                double vyy = c2[3];
                double vyx = c2[2];

                double a = abs(e / vxx);
                double b = abs(f / vxy);
                double c = abs(f / vyy);
                double d = abs(e / vyx);

                double R = abs(c1[1]) - abs(c2[1]);
//                double R = abs((e - vxx)) - abs(f - vxy) - (abs(f - vyy) - abs(e - vyx));

                if (G0.isAdjacentTo(X, Y) || abs(c1[1]) - abs(c2[1]) > .3) {
                    double _c[] =  StatUtils.cov(x, y, x, Double.NEGATIVE_INFINITY, +1);
                    double c3[] =  StatUtils.cov(x, y, x, 0, -1);
                    double c4[] =  StatUtils.cov(x, y, y, 0, -1);

                    if (knowledgeOrients(X, Y)) {
                        graph.addDirectedEdge(X, Y);
                    } else if (knowledgeOrients(Y, X)) {
                        graph.addDirectedEdge(Y, X);
                    }
                    else if (equals(_c, c1) && equals(_c, c2)) {
                        Edge edge1 = Edges.directedEdge(X, Y);
                        Edge edge2 = Edges.directedEdge(Y, X);

                        edge1.setLineColor(Color.GREEN);
                        edge2.setLineColor(Color.GREEN);

                        graph.addEdge(edge1);
                        graph.addEdge(edge2);
                    } else if (!(sameSign(_c, c1) && sameSign(_c, c3)
                            || (sameSign(_c, c2) && sameSign(_c, c4)))) {
                        Edge edge1 = Edges.directedEdge(X, Y);
                        Edge edge2 = Edges.directedEdge(Y, X);

                        edge1.setLineColor(Color.RED);
                        edge2.setLineColor(Color.RED);

                        graph.addEdge(edge1);
                        graph.addEdge(edge2);
                    }
                    else if (R > 0) {
                        graph.addDirectedEdge(X, Y);
                    } else {
                        graph.addDirectedEdge(Y, X);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("Done");

        long stop = System.currentTimeMillis();
        this.elapsed = stop - start;

        return graph;
    }


    private boolean equals(double[] c1, double[] c2) {
        double z = getZ(c1[1]);
        double z1 = getZ(c2[1]);
        double diff1 = z - z1;
        final double t1 = diff1 / (sqrt(1.0 / c1[4] + 1.0 / c2[4]));
        double p1 = 1.0 - new TDistribution(2 * (c1[4] + c2[4]) - 2).cumulativeProbability(abs(t1) / 2.0);
        return p1 <= alpha;
    }

    private boolean sameSign(double[] c1, double[] c2) {
        return signum(c1[1]) == signum(c2[1]);
    }

    /**
     * @return The depth of search for the Fast Adjacency Search (FAS).
     */

    public int getDepth() {
        return depth;
    }

    /**
     * @param depth The depth of search for the Fast Adjacency Search (S). The default is -1.
     *              unlimited. Making this too high may results in statistical errors.
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /**
     * @return The elapsed time in milliseconds.
     */
    public long getElapsedTime() {
        return elapsed;
    }

    /**
     * @return Returns the penalty discount used for the adjacency search. The default is 1,
     * though a higher value is recommended, say, 2, 3, or 4.
     */
    public double getPenaltyDiscount() {
        return penaltyDiscount;
    }

    /**
     * @param penaltyDiscount Sets the penalty discount used for the adjacency search.
     *                        The default is 1, though a higher value is recommended, say,
     *                        2, 3, or 4.
     */
    public void setPenaltyDiscount(double penaltyDiscount) {
        this.penaltyDiscount = penaltyDiscount;
    }

    /**
     * @param alpha Alpha for orienting 2-cycles. Needs to be on the low side usually. Default 1e-6.
     */
    public void setAlpha(double alpha) {
        this.alpha = alpha;
    }

    /**
     * @return the current knowledge.
     */
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    /**
     * @param knowledge Knowledge of forbidden and required edges.
     */
    public void setKnowledge(IKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    //======================================== PRIVATE METHODS ====================================//

    private boolean knowledgeOrients(Node left, Node right) {
        return knowledge.isForbidden(right.getName(), left.getName()) || knowledge.isRequired(left.getName(), right.getName());
    }

    private double getZ(double r) {
        return 0.5 * (log(1.0 + r) - log(1.0 - r));
    }

}






