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

package nextflow.util
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
/**
 * Helper methods to handle Docker containers
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class DockerBuilder {

    final String image

    final List env = []

    List<Path> mounts = []

    private boolean sudo

    private List<String> options = []

    private List<String> engineOptions = []

    private boolean remove = true

    private String temp

    private boolean userEmulation

    private String registry

    private String name

    private boolean tty

    private String entryPoint

    private static final String USER_AND_HOME_EMULATION = '-u $(id -u) -e "HOME=${HOME}" -v /etc/passwd:/etc/passwd:ro -v /etc/shadow:/etc/shadow:ro -v /etc/group:/etc/group:ro -v $HOME:$HOME'

    private String runCommand

    private String removeCommand

    private String killCommand

    private String cpus

    private String memory

    private kill = true

    DockerBuilder( String name ) {
        this.image = name
    }

    DockerBuilder addEnv( entry ) {
        env.add(entry)
        return this
    }

    DockerBuilder addMount( Path path ) {
        if( path )
            mounts.add(path)
        return this
    }

    DockerBuilder addMountForInputs( Map<String,Path> inputFiles ) {

        mounts.addAll( inputFilesToPaths(inputFiles) )
        return this
    }

    DockerBuilder params( Map params ) {
        if( !params ) return this

        if( params.containsKey('temp') )
            this.temp = params.temp

        if( params.containsKey('engineOptions') )
            addEngineOptions(params.engineOptions.toString())

        if( params.containsKey('runOptions') )
            addRunOptions(params.runOptions.toString())

        if ( params.containsKey('userEmulation') )
            this.userEmulation = params.userEmulation?.toString() == 'true'

        if ( params.containsKey('remove') )
            this.remove = params.remove?.toString() == 'true'

        if( params.containsKey('sudo') )
            this.sudo = params.sudo?.toString() == 'true'

        if( params.containsKey('tty') )
            this.tty = params.tty?.toString() == 'true'

        if( params.containsKey('entry') )
            this.entryPoint = params.entry

        if( params.containsKey('kill') )
            this.kill = params.kill

        return this
    }

    DockerBuilder setTemp( String value ) {
        this.temp = value
        return this
    }

    DockerBuilder setName( String name ) {
        this.name = name
        return this
    }

    DockerBuilder setCpus( String value ) {
        this.cpus = value
        return this
    }

    DockerBuilder setMemory( value ) {
        if( value instanceof MemoryUnit )
            this.memory = "${value.toMega()}m"

        else if( value instanceof String )
            this.memory = value

        else
            throw new IllegalArgumentException("Not a supported memory value")

        return this
    }

    DockerBuilder addRunOptions(String str) {
        options.add(str)
        return this
    }

    DockerBuilder addEngineOptions(String str) {
        engineOptions.add(str)
        return this
    }

    String build(StringBuilder result = new StringBuilder()) {
        assert image

        if( sudo )
            result << 'sudo '

        result << 'docker '

        if( engineOptions )
            result << engineOptions.join(' ') << ' '

        result << 'run -i '

        if( cpus )
            result << "--cpuset ${cpus} "

        if( memory )
            result << "--memory ${memory} "

        if( tty )
            result << '-t '

        // add the environment
        for( def entry : env ) {
            result << makeEnv(entry) << ' '
        }

        if( temp )
            result << "-v $temp:/tmp "

        if( userEmulation )
            result << USER_AND_HOME_EMULATION << ' '

        // mount the input folders
        result << makeVolumes(mounts)
        result << ' -w $PWD '

        if( entryPoint )
            result << '--entrypoint ' << entryPoint << ' '

        if( options )
            result << options.join(' ') << ' '

        // the name is after the user option so it has precedence over any options provided by the user
        if( name )
            result << '--name ' << name << ' '

        if( registry )
            result << registry

        // finally the container name
        result << image

        // return the run command as result
        runCommand = result.toString()

        // use an explicit 'docker rm' command since the --rm flag may fail. See https://groups.google.com/d/msg/docker-user/0Ayim0wv2Ls/tDC-tlAK03YJ
        if( remove && name ) {
            removeCommand = 'docker rm ' + name
            if( sudo ) removeCommand = 'sudo ' + removeCommand
        }

        if( kill )  {
            killCommand = 'docker kill '
            // if `kill` is a string it is interpreted as a the kill signal
            if( kill instanceof String ) killCommand += "-s $kill "
            killCommand += name
            // prefix with sudo if required
            if( sudo ) killCommand = 'sudo ' + killCommand
        }


        return runCommand
    }


    /**
     * Get the volumes command line options for the given list of input files
     *
     * @param mountPaths
     * @param binDir
     * @param result
     * @return
     */
    @PackageScope
    static CharSequence makeVolumes(List<Path> mountPaths, StringBuilder result = new StringBuilder() ) {

        // find the longest commons paths and mount only them
        def trie = new PathTrie()
        mountPaths.each { trie.add(it) }

        def paths = trie.longest()
        paths.each{ if(it) result << "-v $it:$it " }

        // -- append by default the current path
        result << '-v $PWD:$PWD'

        return result
    }

    /**
     * Get the env command line option for the give environment definition
     *
     * @param env
     * @param result
     * @return
     */
    @PackageScope
    static CharSequence makeEnv( env, StringBuilder result = new StringBuilder() ) {
        // append the environment configuration
        if( env instanceof File ) {
            env = env.toPath()
        }
        if( env instanceof Path ) {
            result << '-e "BASH_ENV=' << env.toString() << '"'
        }
        else if( env instanceof Map ) {
            short index = 0
            for( Map.Entry entry : env.entrySet() ) {
                if( index++ ) result << ' '
                result << ("-e \"${entry.key}=${entry.value}\"")
            }
        }
        else if( env instanceof String && env.contains('=') ) {
            result << '-e "' << env << '"'
        }
        else if( env ) {
            throw new IllegalArgumentException("Not a valid Docker environment value: $env [${env.class.name}]")
        }

        return result
    }


    @PackageScope
    static List<Path> inputFilesToPaths( Map<String,Path> inputFiles ) {

        def List<Path> files = []
        inputFiles.each { name, storePath ->

            def path = storePath.getParent()
            if( path ) files << path

        }
        return files

    }

    /**
     * @return The command string to run a container from Docker image
     */
    String getRunCommand() { runCommand }

    /**
     * @return The command string to remove a container
     */
    String getRemoveCommand() { removeCommand }

    /**
     * @return The command string to kill a running container
     */
    String getKillCommand() { killCommand }


    static boolean isAbsoluteDockerName(String image) {
        def p = image.indexOf('/')
        if( p==-1 )
            return false

        image = image.substring(0,p)
        image.contains('.') || image.contains(':')
    }

    static String normalizeDockerImageName( String imageName, Map dockerConf ) {

        if( !imageName )
            return null

        String reg = dockerConf?.registry
        if( !reg )
            return imageName

        if( isAbsoluteDockerName(imageName) )
            return imageName

        if( !reg.endsWith('/') )
            reg += '/'

        return reg + imageName
    }


}
