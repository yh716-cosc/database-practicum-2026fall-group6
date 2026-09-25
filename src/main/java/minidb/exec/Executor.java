package minidb.exec;

import minidb.plan.LogicalOperator;
import minidb.storage.TableStore;

public final class Executor {
    @SuppressWarnings("unused")
    private final TableStore store;

    public Executor(TableStore store) {
        this.store = store;
    }

    public Operator compile(LogicalOperator plan) {
        throw new UnsupportedOperationException("Execution is not implemented");
    }
}
