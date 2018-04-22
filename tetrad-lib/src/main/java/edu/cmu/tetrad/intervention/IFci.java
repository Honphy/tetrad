package edu.cmu.tetrad.intervention;

import edu.cmu.tetrad.algcomparison.algorithm.Algorithm;
import edu.cmu.tetrad.algcomparison.independence.IndependenceWrapper;
import edu.cmu.tetrad.algcomparison.utils.HasKnowledge;
import edu.cmu.tetrad.algcomparison.utils.TakesIndependenceWrapper;
import edu.cmu.tetrad.algcomparison.utils.TakesInitialGraph;
import edu.cmu.tetrad.annotation.AlgType;
import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.EdgeListGraph;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.graph.GraphUtils;
import edu.cmu.tetrad.graph.Node;
import edu.cmu.tetrad.search.DagToPag;
import edu.cmu.tetrad.util.Parameters;
import edu.pitt.dbmi.algo.bootstrap.BootstrapEdgeEnsemble;
import edu.pitt.dbmi.algo.bootstrap.GeneralBootstrapTest;

import java.util.List;

/**
 * FCI.
 *
 * @author jdramsey
 */
@edu.cmu.tetrad.annotation.Algorithm(
        name = "FCI",
        command = "fci",
        algoType = AlgType.allow_latent_common_causes,
        description = "This method extends the Fast Causal Inference algorithm given in Spirtes, Glymour and Scheines, Causation, Prediction and Search with "
        + "Jiji Zhang's Augmented FCI rules (found in sec. 4.1 of Zhang's 2006 PhD dissertation, \"Causal Inference and Reasoning in Causally Insufficient Systems\").\n\n"
        + "This class is based off a copy of Fci.java taken from the repository on 2008/12/16, revision 7306. "
        + "The extension is done by extending doFinalOrientation() with methods for Zhang's rules R5-R10 which implements the augmented search. (By a remark of Zhang's, the rule applications can be staged in this way.)\n\n"
        + "For more detail about fci implementation, please visit http://cmu-phil.github.io/tetrad/tetrad-lib-apidocs/edu/cmu/tetrad/search/Fci.html"
)
public class IFci implements Algorithm, TakesInitialGraph, HasKnowledge, TakesIndependenceWrapper {

    static final long serialVersionUID = 23L;
    private IndependenceWrapper test;
    private Algorithm algorithm = null;
    private Graph initialGraph = null;
    private IKnowledge knowledge = new Knowledge2();

    public IFci() {
    }

    public IFci(IndependenceWrapper test) {
        this.test = test;
    }

    public IFci(IndependenceWrapper test, Algorithm algorithm) {
        this.test = test;
        this.algorithm = algorithm;
    }

    @Override
    public Graph search(DataModel dataSet, Parameters parameters) {
        if (!parameters.getBoolean("bootstrapping")) {
            if (algorithm != null) {
                initialGraph = algorithm.search(dataSet, parameters);
            }


            //REMOVE EXTRA OBSERVATIONS
            CleanInterventions ci = new CleanInterventions();
            dataSet = ci.removeExtra(dataSet);

            //REMOVE INTERVENTIONAL CONTEXT
            dataSet = ci.removeInterventionContext(dataSet);


            //REMOVE CONTEXT
            if(parameters.getBoolean("removeContext")) {
                dataSet = ci.removeContext(dataSet);
            }



            //KNOWLEDGE ADDED
            Graph nodes = GraphUtils.emptyGraph(0);
            for (Node node : dataSet.getVariables()) {
                nodes.addNode(node);
            }
            InterventionalKnowledge k = new InterventionalKnowledge(nodes, parameters.getInt("hand"));
            this.knowledge = k.getKnowledge();

            edu.cmu.tetrad.search.Fci search = new edu.cmu.tetrad.search.Fci(test.getTest(dataSet, parameters));
            search.setDepth(parameters.getInt("depth"));
            search.setKnowledge(knowledge);
            search.setMaxPathLength(parameters.getInt("maxPathLength"));
            search.setCompleteRuleSetUsed(parameters.getBoolean("completeRuleSetUsed"));

//            if (initialGraph != null) {
//                search.setInitialGraph(initialGraph);
//            }
            return search.search();
        } else {
            IFci algorithm = new IFci(test);
            algorithm.setKnowledge(knowledge);
//          if (initialGraph != null) {
//      		algorithm.setInitialGraph(initialGraph);
//  		}

            DataSet data = (DataSet) dataSet;

            GeneralBootstrapTest search = new GeneralBootstrapTest(data, algorithm, parameters.getInt("bootstrapSampleSize"));

            BootstrapEdgeEnsemble edgeEnsemble = BootstrapEdgeEnsemble.Highest;
            switch (parameters.getInt("bootstrapEnsemble", 1)) {
                case 0:
                    edgeEnsemble = BootstrapEdgeEnsemble.Preserved;
                    break;
                case 1:
                    edgeEnsemble = BootstrapEdgeEnsemble.Highest;
                    break;
                case 2:
                    edgeEnsemble = BootstrapEdgeEnsemble.Majority;
            }
            search.setEdgeEnsemble(edgeEnsemble);
            search.setParameters(parameters);
            search.setVerbose(parameters.getBoolean("verbose"));
            return search.search();
        }
    }

    @Override
    public Graph getComparisonGraph(Graph graph) {
        return new DagToPag(new EdgeListGraph(graph)).convert();
    }

    public String getDescription() {
        return "IFCI (Intervention Fast Causal Inference) using " + test.getDescription()
                + (algorithm != null ? " with initial graph from "
                        + algorithm.getDescription() : "");
    }

    @Override
    public DataType getDataType() {
        return test.getDataType();
    }

    @Override
    public List<String> getParameters() {
        List<String> parameters = test.getParameters();
        parameters.add("depth");
        parameters.add("maxPathLength");
        parameters.add("completeRuleSetUsed");
        parameters.add("hand");
        parameters.add("removeContext");
        // Bootstrapping
        parameters.add("bootstrapping");
        parameters.add("bootstrapSampleSize");
        parameters.add("bootstrapEnsemble");
        parameters.add("verbose");
        return parameters;
    }

    @Override
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    @Override
    public void setKnowledge(IKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public Graph getInitialGraph() {
        return initialGraph;
    }

    @Override
    public void setInitialGraph(Graph initialGraph) {
        this.initialGraph = initialGraph;
    }

    @Override
    public void setInitialGraph(Algorithm algorithm) {
        this.algorithm = algorithm;
    }

    @Override
    public void setIndependenceWrapper(IndependenceWrapper test) {
        this.test = test;
    }

}
