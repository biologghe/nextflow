/*
 * Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor
import groovy.transform.CompileStatic
import nextflow.processor.TaskContext
import nextflow.processor.TaskRun
import nextflow.util.InputStreamDeserializer
import org.apache.commons.lang.SerializationUtils
import org.apache.ignite.IgniteException
import org.apache.ignite.IgniteLogger
import org.apache.ignite.resources.LoggerResource
/**
 * Execute a groovy closure task in a remote Ignite node
 */
@CompileStatic
class IgClosureTask extends IgBaseTask<IgResultData> {

    private static final long serialVersionUID = 5515528753549263068L

    @LoggerResource
    private transient IgniteLogger log

    /**
     * The task closure serialized as a byte array
     */
    final byte[] codeObj

    /**
     * The task delegate context serialized as a byte array
     */
    final byte[] delegateObj

    private transient StagingStrategy stagingStrategy


    IgClosureTask( TaskRun task, UUID sessionId ) {
        super(task,sessionId)
        this.codeObj = SerializationUtils.serialize(task.code.dehydrate())
        this.delegateObj = task.context.dehydrate()
    }

    void beforeExecute() {
        stagingStrategy = new IgFileStagingStrategy( log: log, task: bean, sessionId: sessionId )
        stagingStrategy.stage()
    }

    void afterExecute() {
        stagingStrategy.unstage()
    }

    @Override
    protected IgResultData execute0() throws IgniteException {
        log.debug "Running closure for task > ${bean.name}"

        def loader = getClassLoaderFor(sessionId)
        def delegate = TaskContext.rehydrate(delegateObj,loader)
        Closure closure = (Closure)InputStreamDeserializer.deserialize(codeObj,loader)
        Object result = closure.rehydrate(delegate, delegate.getScript(), delegate.getScript()).call()
        return new IgResultData(value: result, context: delegate?.getHolder())

    }

    @Override
    void cancel() {

    }

}

