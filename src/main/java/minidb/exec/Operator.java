package minidb.exec;

import minidb.storage.Row;

/** Physical operator. Pull rows with next so execution can stay out of memory. */
public interface Operator {
    void open();

    Row next();

    void close();
}
