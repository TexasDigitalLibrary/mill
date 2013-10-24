/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.mill.noop;

import org.duracloud.mill.domain.Task;
import org.duracloud.mill.workman.TaskExecutionFailedException;
import org.duracloud.mill.workman.TaskProcessor;

import java.util.Map;

/**
 * A task processor which does nothing more than print a bit of output about
 * the task. This is intended to be used for testing and experimentation.
 *
 * @author Bill Branan
 *         Date: 10/23/13
 */
public class NoopTaskProcessor implements TaskProcessor {

    private Task task;

    public NoopTaskProcessor(Task task) {
        this.task = task;
    }

    @Override
    public void execute() throws TaskExecutionFailedException {
        StringBuilder results = new StringBuilder();
        results.append("Executing NOOP Task Processor\nTask Properties:\n");

        Map<String, String> props = task.getProperties();
        for(String key : props.keySet()) {
            results.append(key);
            results.append(": ");
            results.append(props.get(key));
            results.append("\n");
        }

        System.out.println(results.toString());
    }
}