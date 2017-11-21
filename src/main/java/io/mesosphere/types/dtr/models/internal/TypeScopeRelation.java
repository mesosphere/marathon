package io.mesosphere.types.dtr.models.internal;

/**
 * Description of relations between types
 */
public class TypeScopeRelation {

    /**
     * The relation constants
     */
    public enum Relation {
        ChildOf,
        ParentOf,
        UnionOf
    }

    /**
     * The relation between super and child
     */
    public Relation relation;

    /**
     * The child type the child type that takes part in the relation
     */
    public Type target;

    /**
     * Constructor
     * @param relation
     * @param target
     */
    public TypeScopeRelation(Relation relation, Type target) {
        this.relation = relation;
        this.target = target;
    }
}
