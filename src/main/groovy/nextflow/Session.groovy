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

package nextflow
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsConfig
import groovyx.gpars.dataflow.operator.DataflowProcessor
import nextflow.exception.MissingLibraryException
import nextflow.processor.TaskDispatcher
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.script.ScriptBinding
import nextflow.trace.TimelineObserver
import nextflow.trace.TraceFileObserver
import nextflow.trace.TraceObserver
import nextflow.util.Barrier
import nextflow.util.ConfigHelper
import nextflow.util.Duration
/**
 * Holds the information on the current execution
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class Session implements ISession {

    static class TaskFault {
        Throwable error
        String report
        TaskRun task
    }

    static final String EXTRAE_TRACE_CLASS = 'nextflow.extrae.ExtraeTraceObserver'

    /**
     * Keep a list of all processor created
     */
    final List<DataflowProcessor> allProcessors = []

    /**
     * Dispatch tasks for executions
     */
    TaskDispatcher dispatcher

    /**
     * Holds the configuration object
     */
    Map config

    /**
     * Enable / disable tasks result caching
     */
    boolean cacheable

    /**
     * whenever it has been launched in resume mode
     */
    boolean resumeMode

    /**
     * The folder where tasks temporary files are stored
     */
    Path workDir

    /**
     * The folder where the main script is contained
     */
    Path baseDir

    /**
     * The pipeline script name (without parent path)
     */
    String scriptName

    /**
     * Folder(s) containing libs and classes to be added to the classpath
     */
    List<Path> libDir

    /**
     * The unique identifier of this session
     */
    private UUID uniqueId

    private Barrier processesBarrier = new Barrier()

    private Barrier monitorsBarrier = new Barrier()

    private volatile boolean aborted

    private volatile boolean terminated

    private volatile ExecutorService execService

    private volatile TaskFault fault

    private ScriptBinding binding

    private ClassLoader classLoader

    private List<Closure<Void>> shutdownCallbacks = []

    private int poolSize

    private List<TraceObserver> observers = []

    private boolean statsEnabled

    boolean getStatsEnabled() { statsEnabled }

    TaskFault getFault() { fault }

    /**
     * Creates a new session with an 'empty' (default) configuration
     */
    Session() {
        create(new ScriptBinding([:]))
    }

    /**
     * Create a new session given the {@link ScriptBinding} object
     *
     * @param binding
     */
    Session(ScriptBinding binding) {
        create(binding)
    }

    /**
     * Create a new session given the configuration specified
     *
     * @param config
     */
    Session(Map cfg) {
        final config = cfg instanceof ConfigObject ? cfg.toMap() : cfg
        create(new ScriptBinding(config))
    }

    /**
     * @return The current session {@link UUID}
     */
    UUID getUniqueId() { uniqueId }

    /**
     * @return The session max number of thread allowed
     */
    int getPoolSize() { poolSize }

    /**
     * @return The session {@link TaskDispatcher}
     */
    TaskDispatcher getDispatcher() { dispatcher }

    /**
     * Creates a new session using the configuration properties provided
     *
     * @param binding
     */
    private void create( ScriptBinding binding ) {
        assert binding != null

        this.binding = binding
        this.config = binding.config

        // poor man session object dependency injection
        Global.setSession(this)
        Global.setConfig(config)

        cacheable = config.cacheable

        // sets resumeMode and uniqueId
        if( config.resume ) {
            resumeMode = true
            uniqueId = UUID.fromString(config.resume as String)
        }
        else {
           uniqueId = UUID.randomUUID()
        }
        log.debug "Session uuid: $uniqueId"

        // normalize taskConfig object
        if( config.process == null ) config.process = [:]
        if( config.env == null ) config.env = [:]

        if( !config.poolSize ) {
            def cpus = Runtime.getRuntime().availableProcessors()
            config.poolSize = cpus >= 3 ? cpus-1 : 2
        }

        //set the thread pool size
        this.poolSize = config.poolSize as int
        log.debug "Executor pool size: ${poolSize}"

        // create the task dispatcher instance
        this.dispatcher = new TaskDispatcher(this)

    }

    /**
     * Initialize the session workDir, libDir, baseDir and scriptName variables
     */
    void init( Path scriptPath ) {

        this.workDir = ((config.workDir ?: 'work') as Path).complete()
        this.setLibDir( config.libDir as String )

        if( scriptPath ) {
            // the folder that contains the main script
            this.baseDir = scriptPath.parent
            // set the script name attribute
            this.scriptName = scriptPath.name
        }

        this.observers = createObservers()
        this.statsEnabled = observers.size()>0
    }

    /**
     * Given the `run` command line options creates the required {@link TraceObserver}s
     *
     * @param runOpts The {@code CmdRun} object holding the run command options
     * @return A list of {@link TraceObserver} objects or an empty list
     */
    @PackageScope
    List createObservers() {
        def result = []

        createTraceFileObserver(result)
        createTimelineObserver(result)
        createExtraeObserver(result)

        return result
    }

    /**
     * create the Extrae trace observer
     */
    protected void createExtraeObserver(List result) {
        Boolean isEnabled = config.navigate('extrae.enabled') as Boolean
        if( isEnabled ) {
            try {
                result << (TraceObserver)Class.forName(EXTRAE_TRACE_CLASS).newInstance()
            }
            catch( Exception e ) {
                log.warn("Unable to load Extrae profiler",e)
            }
        }
    }

    /**
     * Create timeline report file observer
     */
    protected void createTimelineObserver(List result) {
        Boolean isEnabled = config.navigate('timeline.enabled') as Boolean
        if( isEnabled ) {
            String fileName = config.navigate('timeline.file')
            if( !fileName ) fileName = TimelineObserver.DEF_FILE_NAME
            def traceFile = (fileName as Path).complete()
            def observer = new TimelineObserver(traceFile)
            result << observer
        }
    }

    /*
     * create the execution trace observer
     */
    protected void createTraceFileObserver(List result) {
        Boolean isEnabled = config.navigate('trace.enabled') as Boolean
        if( isEnabled ) {
            String fileName = config.navigate('trace.file')
            if( !fileName ) fileName = TraceFileObserver.DEF_FILE_NAME
            def traceFile = (fileName as Path).complete()
            def observer = new TraceFileObserver(traceFile)
            config.navigate('trace.raw') { it -> observer.useRawNumbers(it == true) }
            config.navigate('trace.sep') { observer.separator = it }
            config.navigate('trace.fields') { observer.setFieldsAndFormats(it) }
            result << observer
        }
    }

    def Session start() {
        log.debug "Session start invoked"

        /*
         * - register all of them in the dispatcher class
         * - register the onComplete event
         */
        for( int i=0; i<observers.size(); i++ ) {
            def trace = observers.get(i)
            log.debug "Registering observer: ${trace.class.name}"
            dispatcher.register(trace)
            onShutdown { trace.onFlowComplete() }
        }

        // register shut-down cleanup hooks
        Global.onShutdown { cleanUp() }
        // create tasks executor
        execService = Executors.newFixedThreadPool(poolSize)
        // signal start to tasks dispatcher
        dispatcher.start()
        // signal start to trace observers
        observers.each { trace -> trace.onFlowStart(this) }

        return this
    }

    ScriptBinding getBinding() { binding }

    ClassLoader getClassLoader() { classLoader }

    Session setClassLoader( ClassLoader loader ) {
        this.classLoader = loader
        return this
    }

    @PackageScope
    Barrier getBarrier() { monitorsBarrier }

    /**
     * The folder where script binaries file are located, by default the folder 'bin'
     * in the script base directory
     */
    @Memoized
    def Path getBinDir() {
        if( !baseDir ) {
            log.debug "Script base directory is null";
            return null
        }

        def path = baseDir.resolve('bin')
        if( !path.exists() || !path.isDirectory() ) {
            log.debug "Script base path does not exist or is not a directory: ${path}"
            return null
        }

        return path
    }


    def void setLibDir( String str ) {

        if( !str ) return

        def files = str.split( File.pathSeparator ).collect { String it -> Paths.get(it) }
        if( !files ) return

        libDir = []
        for( Path file : files ) {
            if( !file.exists() )
                throw new MissingLibraryException("Cannot find specified library: ${file.complete()}")

            libDir << file
        }
    }

    def List<Path> getLibDir() {
        if( libDir )
            return libDir

        libDir = []
        def localLib = baseDir ? baseDir.resolve('lib') : Paths.get('lib')
        if( localLib.exists() ) {
            log.debug "Using default localLib path: $localLib"
            libDir << localLib
        }
        return libDir
    }

    /**
     * Await the termination of all processors
     */
    void await() {
        log.debug "Session await"
        processesBarrier.awaitCompletion()
        log.debug "Session await > processes completed"
        terminated = true
        monitorsBarrier.awaitCompletion()
        log.debug "Session await > done"
    }

    void destroy() {
        log.trace "Session > destroying"
        cleanUp()
        log.trace "Session > after cleanup"

        if( !aborted ) {
            allProcessors *. join()
            log.trace "Session > after processors join"
        }

        execService.shutdown()
        log.trace "Session > executor shutdown"
        execService = null
        log.debug "Session destroyed"
    }

    final protected void cleanUp() {

        log.trace "Shutdown: $shutdownCallbacks"
        List<Closure<Void>> all = new ArrayList<>(shutdownCallbacks)
        for( def hook : all ) {
            try {
                hook.call()
            }
            catch( Exception e ) {
                log.debug "Failed executing shutdown hook: $hook", e
            }
        }

        // -- after the first time remove all of them to avoid it's called twice
        shutdownCallbacks.clear()
    }

    void abort(Throwable cause, TaskRun task, String message) {
        this.fault = new TaskFault(error: cause, task: task, report: message)
        abort(cause)
    }

    void abort(Throwable cause = null) {
        log.debug "Session aborted -- Cause: ${cause}"
        aborted = true
        dispatcher.signal()
        processesBarrier.forceTermination()
        monitorsBarrier.forceTermination()
        allProcessors *. terminate()
    }

    void forceTermination() {
        terminated = true
        processesBarrier.forceTermination()
        monitorsBarrier.forceTermination()
        allProcessors *. terminate()

        execService?.shutdownNow()
        GParsConfig.shutdown()
    }

    boolean isTerminated() { terminated }

    boolean isAborted() { aborted }

    def void taskRegister(TaskProcessor process) {
        log.debug ">>> barrier register (process: ${process.name})"
        processesBarrier.register(process)
        for( TraceObserver it : observers ) { it.onProcessCreate(process) }
    }

    def void taskDeregister(TaskProcessor process) {
        log.debug "<<< barrier arrive (process: ${process.name})"
        for( TraceObserver it : observers ) { it.onProcessDestroy(process) }
        processesBarrier.arrive(process)
    }

    def ExecutorService getExecService() { execService }

    /**
     * Register a shutdown hook to close services when the session terminates
     * @param Closure
     */
    def void onShutdown( Closure shutdown ) {
        if( !shutdown )
            return

        shutdownCallbacks << shutdown
    }

    @Memoized
    public getExecConfigProp( String execName, String name, Object defValue, Map env = null  ) {
        def result = ConfigHelper.getConfigProperty(config.executor, execName, name )
        if( result != null )
            return result

        // -- try to fallback sys env
        def key = "NXF_EXECUTOR_${name.toUpperCase().replaceAll(/\./,'_')}".toString()
        if( env == null ) env = System.getenv()
        return env.containsKey(key) ? env.get(key) : defValue
    }

    /**
     * Defines the number of tasks the executor will handle in a parallel manner
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return The value of tasks to handle in parallel
     */
    @Memoized
    public int getQueueSize( String execName, int defValue ) {
        getExecConfigProp(execName, 'queueSize', defValue) as int
    }

    /**
     * Determines how often a poll occurs to check for a process termination
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '1 second'
     */
    @Memoized
    public Duration getPollInterval( String execName, Duration defValue = Duration.of('1sec') ) {
        getExecConfigProp( execName, 'pollInterval', defValue ) as Duration
    }

    /**
     *  Determines how long the executors waits before return an error status when a process is
     *  terminated but the exit file does not exist or it is empty. This setting is used only by grid executors
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '90 second'
     */
    @Memoized
    public Duration getExitReadTimeout( String execName, Duration defValue = Duration.of('90sec') ) {
        getExecConfigProp( execName, 'exitReadTimeout', defValue ) as Duration
    }

    /**
     * Determines how often the executor status is written in the application log file
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '5 minutes'
     */
    @Memoized
    public Duration getMonitorDumpInterval( String execName, Duration defValue = Duration.of('5min')) {
        getExecConfigProp(execName, 'dumpInterval', defValue) as Duration
    }

    /**
     * Determines how often the queue status is fetched from the cluster system. This setting is used only by grid executors
     *
     * @param execName The executor name
     * @param defValue  The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '1 minute'
     */
    @Memoized
    public Duration getQueueStatInterval( String execName, Duration defValue = Duration.of('1min') ) {
        getExecConfigProp(execName, 'queueStatInterval', defValue) as Duration
    }


//    /**
//     * Create a table report of all executed or running tasks
//     *
//     * @return A string table formatted displaying the tasks information
//     */
//    String tasksReport() {
//
//        TableBuilder table = new TableBuilder()
//                .head('name')
//                .head('id')
//                .head('status')
//                .head('path')
//                .head('exit')
//
//        tasks.entries().each { Map.Entry<Processor, TaskDef> entry ->
//            table << entry.key.name
//            table << entry.value.id
//            table << entry.value.status
//            table << entry.value.workDirectory
//            table << entry.value.exitCode
//            table << table.closeRow()
//        }
//
//        table.toString()
//
//    }

}
