package com.openddal.config;

import java.util.Random;

import com.openddal.route.rule.ObjectNode;
import com.openddal.util.StringUtils;

public class MultiNodeTableRule extends TableRule {

    private static final long serialVersionUID = 1L;
    private ObjectNode[] objectNodes;

    private final Random random = new Random();

    public MultiNodeTableRule(String name) {
        super(name, null);
    }

    public MultiNodeTableRule(String name, ObjectNode... objectNode) {
        this(name);
        setObjectNodes(objectNode);
    }

    public MultiNodeTableRule(String name, ObjectNode metadataNode, ObjectNode... objectNode) {
        super(name, metadataNode);
        setObjectNodes(objectNode);
    }

    public ObjectNode[] getObjectNodes() {
        return objectNodes;
    }

    @Override
    public ObjectNode randomMetadataNodeIfNeeded() {
        ObjectNode metadataNode = this.getMetadataNode();
        if(metadataNode == null) {
            int bound = objectNodes.length - 1;
            return objectNodes[random.nextInt(bound)];
        }
        return metadataNode;
    }

    public void setObjectNodes(ObjectNode... objectNode) {
        for (ObjectNode item : objectNode) {
            if (StringUtils.isNullOrEmpty(item.getShardName())) {
                throw new IllegalArgumentException("The shardName attribute of ObjectNode is required.");
            }
            if (StringUtils.isNullOrEmpty(item.getObjectName())) {
                item.setObjectName(getName());
            }

        }
        this.objectNodes = objectNode;
    }

    public void cloneObjectNodes(ObjectNode... objectNode) {
        this.objectNodes = new ObjectNode[objectNode.length];
        for (int i = 0; i < objectNode.length; i++) {
            ObjectNode item = objectNode[i];
            if (StringUtils.isNullOrEmpty(item.getShardName())) {
                throw new IllegalArgumentException("The shardName attribute of ObjectNode is required.");
            }
            this.objectNodes[i] = new ObjectNode(item.getShardName(), item.getCatalog(), item.getSchema(),
                    this.getName(), item.getSuffix());
        }

    }
}
