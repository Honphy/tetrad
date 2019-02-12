/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.cmu.tetradapp.editor.datamanip;

import edu.cmu.tetrad.data.DataModel;
import edu.cmu.tetrad.data.DataModelList;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.graph.NodeVariableType;
import edu.cmu.tetrad.util.Parameters;
import edu.cmu.tetradapp.editor.FinalizingParameterEditor;
import edu.cmu.tetradapp.model.DataWrapper;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 *
 * Feb 11, 2019 4:17:17 PM
 *
 * @author Zhou Yuan <zhy19@pitt.edu>
 */
public class DeterminismEditor extends JPanel implements FinalizingParameterEditor {

    private static final long serialVersionUID = 6513664419620810219L;

    private DataSet sourceDataSet;

    private DataModelList dataSets = null;

    private Parameters parameters;

    private final List<Map> interventionalVarPairs = new LinkedList<>();
    private final List<String> interventionalVars = new LinkedList<>();
    private final List<String> nonInterventionalVars = new LinkedList<>();

    public DeterminismEditor() {
    }

    @Override
    public void setup() {
        // Container
        Box container = Box.createVerticalBox();
        container.setPreferredSize(new Dimension(640, 460));

        // Group variables based ont their NodeVariableType
        groupVariables();

        // Domain variables
        Box domainVarsBox = Box.createVerticalBox();

        domainVarsBox.add(new JLabel("Domain variables:"));

        nonInterventionalVars.forEach(e -> {
            domainVarsBox.add(new JLabel(e));
        });

        // Intervention variables
        Box interventionVarsBox = Box.createVerticalBox();

        interventionVarsBox.add(new JLabel("Intervention variables:"));

        interventionalVarPairs.forEach(e -> {
            System.out.println("=======================");
            System.out.println(e.get("status"));
            System.out.println(e.get("value"));
            interventionVarsBox.add(new JLabel("Status variable: " + e.get("status") + " Value variable: " + e.get("value")));
        });

        // Add data type box to container
        container.add(new JLabel("Merge deterministic variables"));

        container.add(domainVarsBox);
        
        container.add(interventionVarsBox);

        // Adds the specified component to the end of this container.
        add(container, BorderLayout.CENTER);
    }

    private void groupVariables() {
        sourceDataSet.getVariables().forEach(e -> {
            // For tiers
            if (e.getNodeVariableType() == NodeVariableType.INTERVENTION_STATUS) {
                interventionalVars.add(e.getName());

                // Get the interventional variable pairs for required groups
                Map<String, String> interventionalVarPair = new HashMap<>();

                // Keep the pair info
                interventionalVarPair.put("status", e.getName());
                interventionalVarPair.put("value", e.getPairedInterventionalNode().getName());

                // Add to the list
                interventionalVarPairs.add(interventionalVarPair);
            } else if (e.getNodeVariableType() == NodeVariableType.INTERVENTION_VALUE) {
                interventionalVars.add(e.getName());
            } else {
                nonInterventionalVars.add(e.getName());
            }
        });
    }

    @Override
    public void setParams(Parameters params) {
        this.parameters = params;
    }

    /**
     * The parent model should be a <code>DataWrapper</code>.
     *
     * @param parentModels
     */
    @Override
    public void setParentModels(Object[] parentModels) {
        if (parentModels == null || parentModels.length == 0) {
            throw new IllegalArgumentException("There must be parent model");
        }

        DataWrapper dataWrapper = null;

        for (Object parent : parentModels) {
            if (parent instanceof DataWrapper) {
                dataWrapper = (DataWrapper) parent;
            }
        }
        if (dataWrapper == null) {
            throw new IllegalArgumentException("Should have have a data wrapper as a parent");
        }
        DataModel model = dataWrapper.getSelectedDataModel();
        if (!(model instanceof DataSet)) {
            throw new IllegalArgumentException("The dataset must be a rectangular dataset");
        }

        this.sourceDataSet = (DataSet) model;

        // All loaded datasets
        this.dataSets = dataWrapper.getDataModelList();
    }

    @Override
    public boolean finalizeEdit() {
        return true;
    }

    @Override
    public boolean mustBeShown() {
        return true;
    }

}
